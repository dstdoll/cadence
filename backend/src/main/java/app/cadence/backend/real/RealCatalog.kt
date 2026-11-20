package app.cadence.backend.real

import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.Ttml
import app.cadence.backend.lyrics.LrcParser
import app.cadence.backend.engine.Mapping
import app.cadence.backend.links.AppleLink
import app.cadence.backend.links.LinkTarget
import app.cadence.backend.model.*
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchEngine
import app.cadence.backend.search.SearchHit
import app.cadence.backend.search.SearchResults
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Catalogue backed by amp-api with a small in-memory cache.
 *
 * `MusicRepository` is synchronous, so a cache miss on a detail lookup performs a
 * short blocking fetch (4 s cap). That is deliberate for now: it lets the real
 * backend land behind the existing contracts without touching a single screen.
 */
class RealCatalog(
    private val engine: AmpEngine,
    /** Where the last good catalogue is snapshotted, so first paint works offline. */
    private val cacheFile: java.io.File? = null,
    private val favoritesStore: FavoritesStore? = null,
) : app.cadence.backend.api.MusicRepository {

    // The UI reads these from the main thread while the seed, searches and detail loads write
    // from background threads, so every shared structure here is safe for concurrent use.
    private val artistMap = OrderedCache<String, Artist>()
    private val albumMap = OrderedCache<String, Album>()
    private val trackMap = OrderedCache<String, TrackInfo>()
    private val loadedAlbums: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val loadedArtists: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /**
     * display artist id -> numeric catalogue id. First binding wins (see [bindCatalogId]); the base ids
     * (`ar:<name>`) are persisted with the catalogue so that "who owns the name" survives a restart.
     * A qualified id (`arx:<id>:<name>`) carries its catalogue id and needs no entry.
     */
    private val realArtistIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The album ids an artist's own calls returned (its views and its albums endpoint), by display id. */
    private val artistAlbums = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val saveLock = Any()
    private val saveScheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cadence-catalog-save").apply { isDaemon = true } }
    }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    override val artists: List<Artist> get() = artistMap.values
    override val albums: List<Album> get() = albumMap.values
    override val tracks: List<TrackInfo> get() = trackMap.values

    override val storefront: String get() = engine.storefrontNow()

    private fun rawStationId(id: String) = id.removePrefix("st:")

    private fun seededStation(root: JSONObject?, title: String, like: TrackInfo?): Playlist? {
        val ra = StationIds.stationIds(root).firstOrNull { it.startsWith("ra.") } ?: return null
        val p = Playlist(
            id = "st:$ra", title = title, description = "Apple Music Radio", trackIds = emptyList(),
            colors = like?.album?.colors ?: listOf("#6750A4", "#21005D"), cover = like?.album?.cover, curator = "Apple Music",
        )
        foundPlaylists[p.id] = p
        return p
    }

    override suspend fun stationOfSong(track: TrackInfo): Playlist? {
        if (!track.id.all(Char::isDigit)) return null
        return seededStation(engine.get("songs/${track.id}?include=station"), "${track.track.title} Station", track)
            .also { CadenceLog.i("RADIO", "station of song ${track.id} -> ${it?.id ?: "none"}") }
    }

    override suspend fun stationOfArtist(artistId: String): Playlist? {
        val real = realArtistId(artistId)?.takeIf { it.all(Char::isDigit) } ?: return null
        val a = artist(artistId)
        return seededStation(engine.get("artists/$real?include=station"), "${a?.name.orEmpty()} Station", tracks.firstOrNull { it.artist.id == artistId })
            .also { CadenceLog.i("RADIO", "station of artist $real -> ${it?.id ?: "none"}") }
    }

    override suspend fun personalStation(): Playlist? {
        val data = engine.get("stations?filter%5Bidentity%5D=personal")?.optJSONArray("data") ?: return null
        val p = data.optJSONObject(0)?.let(Mapping::station) ?: return null
        foundPlaylists[p.id] = p
        return p
    }

    override suspend fun genreStations(): List<Playlist> {
        val genres = engine.get("station-genres")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Playlist>()
        for (i in 0 until genres.length()) {
            val g = genres.optJSONObject(i) ?: continue
            val id = g.optString("id").takeIf { it.isNotBlank() } ?: continue
            val first = engine.get("station-genres/$id/stations")?.optJSONArray("data")?.optJSONObject(0)?.let(Mapping::station) ?: continue
            if (first.available) { foundPlaylists[first.id] = first; out += first }
        }
        return out
    }

    override suspend fun stationFeed(stationId: String): List<TrackInfo> {
        val data = engine.stationNextTracks(rawStationId(stationId))?.optJSONArray("data") ?: return emptyList()
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::mapPlaylistTrack) }
        return ids.mapNotNull(::track)
    }

    override suspend fun resolveLink(link: AppleLink): LinkTarget =
        resolveLink(link) { engine.get(it) }

    /**
     * What a link points at: the matching catalogue resource is fetched ([fetch] is `engine.get` in the app) and registered
     * through [hitsOf], the same path search results take, so the page can open from the cache. Ids were validated by
     * [app.cadence.backend.links.AppleLinks.parse]; nothing is registered unless the resource parsed.
     */
    internal suspend fun resolveLink(
        link: app.cadence.backend.links.AppleLink,
        fetch: suspend (String) -> JSONObject?,
    ): LinkTarget {
        return when (link) {
            is AppleLink.Unsupported -> LinkTarget.Unsupported(link.kind)
            is AppleLink.Album -> {
                val hit = hitsOf(fetch("albums/${link.id}")?.optJSONArray("data")).filterIsInstance<SearchHit.AlbumHit>().firstOrNull()
                if (hit == null) LinkTarget.NotFound else LinkTarget.Open(LinkTarget.Kind.Album, hit.album.id, link.songId)
            }
            is AppleLink.Song -> {
                val hit = hitsOf(fetch("songs/${link.id}")?.optJSONArray("data")).filterIsInstance<SearchHit.SongHit>().firstOrNull()
                if (hit == null) LinkTarget.NotFound else LinkTarget.Open(LinkTarget.Kind.Album, hit.track.album.id, hit.track.id)
            }
            is AppleLink.Playlist -> {
                val hit = hitsOf(fetch("playlists/${link.token}")?.optJSONArray("data")).filterIsInstance<SearchHit.PlaylistHit>().firstOrNull()
                if (hit == null) LinkTarget.NotFound else LinkTarget.Open(LinkTarget.Kind.Playlist, hit.playlist.id)
            }
            is AppleLink.Artist -> {
                val hit = hitsOf(fetch("artists/${link.id}")?.optJSONArray("data")).filterIsInstance<SearchHit.ArtistHit>().firstOrNull()
                if (hit == null) LinkTarget.NotFound else LinkTarget.Open(LinkTarget.Kind.Artist, hit.artist.id)
            }
        }
    }

    /** Recommendations rendered as "Made for you" cards (album art + its tracklist). */
    /** An immutable list swapped whole, so Home can iterate it while a refresh publishes the next one. */
    @Volatile private var personalPlaylists: List<Playlist> = emptyList()
    private val playlistsLock = Any()
    override val playlists: List<Playlist> get() = personalPlaylists

    /** Registers a name-only artist unless a richer record is already cached. */
    private fun placeholderArtist(artistId: String) =
        artistMap.putIfAbsent(artistId, Artist(artistId, ArtistIds.nameOf(artistId).ifBlank { "Unknown Artist" }, "", "#888888"))

    @Volatile private var homeShelves: List<HomeShelf> = emptyList()
    override val shelves: List<HomeShelf> get() = homeShelves

    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    override val revision: kotlinx.coroutines.flow.StateFlow<Int> get() = changes
    private fun changed() { changes.value = changes.value + 1 }

    override fun track(id: String): TrackInfo? = trackMap[id]
    override fun album(id: String): Album? = albumMap[id]
    override fun artist(id: String): Artist? = artistMap[id]

    /**
     * Cache-only: never blocks, so a synchronous lookup cannot stall the main thread
     * waiting on the network. Screens that need the tracklist call [prepareAlbum] first.
     */
    override fun tracksOfAlbum(albumId: String): List<TrackInfo> =
        trackMap.values.filter { it.track.albumId == albumId }

    /**
     * Album pages and their download button must never act on a cold cache: load
     * the tracklist first, then return it (cheap when already loaded).
     */
    private val albumLoad = kotlinx.coroutines.sync.Mutex()

    override suspend fun prepareAlbum(albumId: String): List<TrackInfo> = albumLoad.withLock {
        // "Has some tracks" is not "is loaded": search and playlists cache single songs of an
        // album, and the page must still show the whole tracklist.
        if (albumId !in loadedAlbums) loadAlbumTracks(albumId)
        tracksOfAlbum(albumId)
    }

    private val playlistLoad = kotlinx.coroutines.sync.Mutex()

    /**
     * Loads a recommendation/editorial playlist's songs on open (the rail cards carry no
     * tracklist). Each song may be from a different album, so an album is synthesised per song.
     * Stations are dynamic radio with no tracklist, so they resolve to empty.
     */
    override suspend fun preparePlaylist(playlistId: String, forceRefresh: Boolean): List<TrackInfo> = playlistLoad.withLock {
        val existing = playlist(playlistId)
        if (!forceRefresh) {
            existing?.takeIf { it.trackIds.isNotEmpty() }?.let { return@withLock it.trackIds.mapNotNull(::track) }
        }
        if (playlistId.startsWith("st:")) {
            val ids = loadStationTracks(playlistId.removePrefix("st:"), existing)
                ?: return@withLock existing?.trackIds?.mapNotNull(::track).orEmpty()
            synchronized(playlistsLock) {
                personalPlaylists = personalPlaylists.map { if (it.id == playlistId) it.copy(trackIds = ids) else it }
            }
            changes.value += 1
            CadenceLog.i("playlist", "open $playlistId (station) tracks=${ids.size}")
            return@withLock ids.mapNotNull(::track)
        }
        if (playlistId.startsWith("lp:")) {
            val ids = libraryPlaylistSongs(playlistId)
                ?: return@withLock existing?.trackIds?.mapNotNull(::track).orEmpty()
            accountPlaylists = accountPlaylists.map { if (it.id == playlistId) it.copy(trackIds = ids) else it }
            changes.value += 1
            CadenceLog.i("playlist", "open $playlistId (library) tracks=${ids.size}")
            return@withLock ids.mapNotNull(::track)
        }
        val appleId = playlistId.removePrefix("pl:")
        val root = engine.playlistTracks(appleId)
            ?: return@withLock existing?.trackIds?.mapNotNull(::track).orEmpty()
        val tracksRel = root.optJSONArray("data")?.optJSONObject(0)
            ?.optJSONObject("relationships")?.optJSONObject("tracks")
            ?: return@withLock existing?.trackIds?.mapNotNull(::track).orEmpty()
        val ids = ArrayList<String>()
        tracksRel.optJSONArray("data")?.let { page ->
            for (i in 0 until page.length()) {
                page.optJSONObject(i)?.let(::mapPlaylistTrack)?.let(ids::add)
            }
        }
        if (tracksRel.has("next")) {
            var offset = ids.size
            while (offset < MAX_LIBRARY_PLAYLIST_SONGS) {
                val page = engine.playlistTracksPage(appleId, offset) ?: break
                val data = page.optJSONArray("data") ?: break
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.let(::mapPlaylistTrack)?.let(ids::add)
                }
                if (data.length() == 0 || !page.has("next")) break
                offset += data.length()
            }
        }
        synchronized(playlistsLock) {
            val idx = personalPlaylists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) {
                personalPlaylists = personalPlaylists.toMutableList().also { it[idx] = it[idx].copy(trackIds = ids) }
            }
        }
        foundPlaylists[playlistId]?.let { foundPlaylists[playlistId] = it.copy(trackIds = ids) }
        changes.value += 1
        CadenceLog.i("playlist", "open $playlistId tracks=${ids.size}")
        ids.mapNotNull(::track)
    }

    private fun mapPlaylistTrack(song: JSONObject): String? {
        val attrs = song.optJSONObject("attributes") ?: return null
        val artistId = resolveArtistId(ArtistIds.base(attrs.optString("artistName")), song)
        placeholderArtist(artistId)
        val artist = artistMap.getValue(artistId)
        val albumId = song.optJSONObject("relationships")?.optJSONObject("albums")
            ?.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            ?: "plalb:${attrs.optString("albumName")}:${artist.id}"
        val album = albumMap[albumId] ?: Mapping.albumFromSong(song, albumId, artist.id).also { albumMap[albumId] = it }
        val tr = Mapping.track(song, albumId)?.copy(artistId = artistId) ?: return null
        trackMap[tr.id] = TrackInfo(tr, album, artist)
        return tr.id
    }

    /**
     * The artist's own albums once their page has loaded (what Apple returned for that very artist),
     * so two artists with one name never share a list; before that, the albums credited to the id.
     */
    override fun albumsOfArtist(artistId: String): List<Album> =
        artistAlbums[artistId]?.mapNotNull(albumMap::get)?.takeIf { it.isNotEmpty() }
            ?: albumMap.values.filter { it.artistId == artistId }

    /** Fills album tracklists for the visible search results, off the main thread. */
    suspend fun prefetchAlbums(albumIds: Collection<String>, limit: Int = 4) {
        albumIds.take(limit).forEach { loadAlbumTracks(it) }
    }

    /**
     * The catalogue holds Apple's protected HLS master, which a player cannot read. Playback
     * goes through the decrypting stream cache instead, addressed by track id.
     */
    override fun audioUri(track: TrackInfo): String = when {
        track.id.isBlank() -> ""
        track.track.audio.isBlank() -> app.cadence.backend.engine.stream.WebPlayback.uriFor(track.id)
        else -> app.cadence.backend.engine.stream.StreamPlayback.uriFor(track.id)
    }

    override fun motionUri(path: String?): String? = path

    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    override suspend fun lyrics(track: TrackInfo): Lyrics {
        lyricsCache[track.id]?.let { return it }
        val ttml = engine.lyrics(track.id)
        if (ttml.isNullOrBlank()) {
            CadenceLog.i("lyrics", "track=${track.id} none (no TTML)")
            return Lyrics.None
        }
        val lrc = Ttml.toLrc(ttml)
        val parsed = runCatching { LrcParser.parse(lrc) }.getOrDefault(Lyrics.None)
        val lines = when (parsed) {
            is Lyrics.Synced -> parsed.lines.size
            is Lyrics.Plain -> parsed.text.lines().size
            Lyrics.None -> 0
        }
        CadenceLog.i("lyrics", "track=${track.id} ttml=${ttml.length}b lrc=$lines lines parsed=${parsed::class.simpleName}")
        if (parsed != Lyrics.None) lyricsCache[track.id] = parsed
        return parsed
    }

    // ---- disk cache ----

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Restores the last snapshot. Returns true when the catalogue is usable. */
    fun loadCache(): Boolean {
        hydrateFavoritesFromStore()
        val file = cacheFile ?: return false
        if (!file.exists()) return false
        return runCatching {
            val doc = json.decodeFromString<Catalog>(file.readText())
            if (doc.albums.isEmpty()) return false
            doc.artists.forEach { artistMap[it.id] = it }
            doc.artistBindings.forEach { (id, catalogId) -> realArtistIds.putIfAbsent(id, catalogId) }
            doc.albums.forEach { albumMap[it.id] = it }
            synchronized(playlistsLock) { personalPlaylists = personalPlaylists + doc.playlists }
            account = doc.account
            doc.tracks.forEach { t ->
                val album = albumMap[t.albumId] ?: return@forEach
                val artist = artistMap[t.artistId] ?: Artist(t.artistId, ArtistIds.nameOf(t.artistId), "", "#888888")
                artistMap[artist.id] = artist
                trackMap[t.id] = TrackInfo(t, album, artist)
            }
            CadenceLog.i("cache", "restored albums=" + albumMap.size + " tracks=" + trackMap.size)
            changed()
            true
        }.getOrDefault(false)
    }

    fun saveCache() {
        val file = cacheFile ?: return
        synchronized(saveLock) {
            runCatching {
                val doc = Catalog(
                    artists = artistMap.values.toList(),
                    albums = albumMap.values.toList(),
                    tracks = trackMap.values.map { it.track },
                    playlists = personalPlaylists.toList(),
                    account = account,
                    artistBindings = realArtistIds.toMap(),
                )
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(Catalog.serializer(), doc))
                CadenceLog.i("cache", "saved albums=" + doc.albums.size + " bytes=" + file.length())
            }
        }
    }

    /**
     * The snapshot is otherwise written only by the login load sequence, so an artist-id binding made by
     * a search would be lost if the process died: save it shortly after (several bindings, one write).
     */
    private fun scheduleSave() {
        if (cacheFile == null) return
        synchronized(saveLock) {
            pendingSave?.cancel(false)
            pendingSave = saveScheduler.schedule({ saveCache() }, 2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // ---- loading ----

    /**
     * Home renders [albums]/[artists]/[tracks] directly, so the catalogue must be
     * populated without a search. Seeds from Apple's album charts, then fills the
     * tracklists for the first few albums.
     */
    suspend fun seedBrowse() {
        val root = engine.charts()
        if (root == null) { CadenceLog.e("browse", "charts unavailable"); return }
        // The charts endpoint nests differently from search: `results.albums` is an ARRAY of chart
        // groups, each with its own `data`. Older/other shapes put the array directly under `data`.
        val albumsNode = root.optJSONObject("results")?.opt("albums") ?: root.opt("albums")
        val chart: JSONArray? = when (albumsNode) {
            is JSONArray -> albumsNode.optJSONObject(0)?.optJSONArray("data") ?: albumsNode
            is JSONObject -> albumsNode.optJSONArray("data")
            else -> null
        }
        if (chart == null || chart.length() == 0) {
            CadenceLog.e("browse", "no album chart in response; results keys=${root.optJSONObject("results")?.keys()?.asSequence()?.toList()}")
            return
        }
        val ids = ArrayList<String>()
        for (i in 0 until chart.length()) {
            val alb = Mapping.album(chart.optJSONObject(i) ?: continue) ?: continue
            albumMap[alb.id] = alb
            placeholderArtist(alb.artistId)
            ids += alb.id
        }
        CadenceLog.i("browse", "seed albums=${albumMap.size} artists=${artistMap.size}")
        albumMap.values.firstOrNull()?.let { CadenceLog.i("browse", "first album '${it.title}' cover=${it.cover}") }
        prefetchAlbums(ids, limit = 4)
        CadenceLog.i("browse", "seed done tracks=${trackMap.size}")
        changed()
    }

    /** Home pull-to-refresh: re-run the personalised feed fetch (recommendations rotate; the rail
     *  structure is deterministic). */
    override suspend fun refresh() = loadPersonal()

    /**
     * Personal rails from the signed-in account:
     *  - /v1/me/recommendations  -> "Made for you" / "Albums you might like"
     *  - /v1/me/recent/played    -> "Recently played"
     * Albums are re-inserted recent-first so the rails read newest first.
     */
    suspend fun loadPersonal() {
        val recent = ArrayList<Album>()
        val recommended = ArrayList<Album>()

        engine.me("recent/played?types=albums&limit=10")?.optJSONArray("data")?.let { data ->
            for (i in 0 until data.length()) {
                Mapping.album(data.optJSONObject(i) ?: continue)?.let { recent += it }
            }
        }
        CadenceLog.i("personal", "recent played albums=${recent.size}")

        // Real recommendation shelves carry a MIX of content. Dispatch each item by its
        // `type` into the right rail instead of forcing everything into albums: playlists
        // become real playlists and stations become real radio cards.
        val recommendedPlaylists = ArrayList<Playlist>()
        val stations = ArrayList<Playlist>()
        // Each recommendation is a shelf with its own title and order; keep that structure for
        // Home instead of flattening everything into two rails.
        val shelves = ArrayList<HomeShelf>()
        engine.me("recommendations?limit=30&extend=extendedAssetUrls")?.optJSONArray("data")?.let { data ->
            for (i in 0 until data.length()) {
                val shelf = data.optJSONObject(i) ?: continue
                val attrs = shelf.optJSONObject("attributes")
                val contents = shelf.optJSONObject("relationships")
                    ?.optJSONObject("contents")
                    ?.optJSONArray("data") ?: continue
                val items = ArrayList<ShelfItem>(contents.length())
                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j) ?: continue
                    when (item.optString("type")) {
                        "albums", "library-albums" -> Mapping.album(item)?.let { recommended += it; items += ShelfItem.AlbumItem(it) }
                        "playlists", "library-playlists" -> Mapping.playlist(item)?.let { recommendedPlaylists += it; items += ShelfItem.PlaylistItem(it) }
                        "stations" -> Mapping.station(item)?.let { stations += it; items += ShelfItem.PlaylistItem(it) }
                    }
                }
                val title = attrs?.optJSONObject("title")?.optString("stringForDisplay").orEmpty()
                if (title.isNotBlank() && items.isNotEmpty()) {
                    shelves += HomeShelf(
                        id = shelf.optString("id").ifBlank { "shelf$i" },
                        title = title,
                        hero = attrs?.optJSONObject("display")?.optString("kind") == "MusicNotesHeroShelf",
                        items = items.distinctBy { it.key },
                    )
                }
            }
        }
        CadenceLog.i("personal", "recommended albums=${recommended.size} playlists=${recommendedPlaylists.size} stations=${stations.size}")

        // recent first, then recommendations, then whatever the charts already gave us
        // One atomic step: a reader never sees the catalogue emptied, and an album added by a
        // concurrent search between "read" and "write" is not dropped.
        albumMap.mutate { map ->
            val merged = LinkedHashMap<String, Album>()
            (recent + recommended + map.values).forEach { merged.putIfAbsent(it.id, it) }
            map.clear()
            map.putAll(merged)
        }

        (recent.map { it.artistId } + recommended.map { it.artistId }).forEach { id ->
            placeholderArtist(id)
        }
        // Cards advertise a track count, so their tracklists must actually be loaded.
        runCatching { prefetchAlbums(recommended.take(10).map { it.id }, limit = 10) }
        // Purpose-specific data first: real recommended playlists, then real radio stations.
        val fresh = ArrayList<Playlist>(recommendedPlaylists.size + stations.size)
        fresh += recommendedPlaylists
        fresh += stations
        // Fallback only when the account returned no real playlists/stations, so the
        // "Made for you" rail is never empty (cards derived from album recommendations).
        if (fresh.isEmpty()) {
            val cached = trackMap.values
            recommended.take(10).forEach { a ->
                fresh += Playlist(
                    id = "rec:" + a.id,
                    title = a.title,
                    description = "Made for you",
                    trackIds = cached.filter { it.track.albumId == a.id }.map { it.id },
                    colors = a.colors,
                    cover = a.cover,
                )
            }
        }
        // Published in one step so Home never observes a half-built rail. A playlist that was
        // already opened keeps its loaded tracklist across the refresh.
        synchronized(playlistsLock) {
            val loaded = personalPlaylists.filter { it.trackIds.isNotEmpty() }.associateBy { it.id }
            // The same station/playlist appears in several recommendation shelves, so `fresh` can
            // carry duplicate ids. The Home rail keys its LazyRow by id, and duplicate keys crash
            // Compose ("Key ... was already used"), so keep only the first of each id.
            personalPlaylists = fresh.distinctBy { it.id }
                .map { p -> loaded[p.id]?.let { p.copy(trackIds = it.trackIds) } ?: p }
        }
        if (shelves.isNotEmpty()) homeShelves = shelves
        changed()
        CadenceLog.i("personal", "made-for-you cards=${personalPlaylists.size} shelves=${shelves.map { it.title + ":" + it.items.size }}")
        CadenceLog.i("personal", "catalog albums=${albumMap.size} artists=${artistMap.size}")
        prefetchAlbums(recent.take(3).map { it.id } + recommended.take(1).map { it.id }, limit = 4)
    }

    // ---- search ----

    /**
     * Playlists surfaced by search. Kept apart from [personalPlaylists], which is exactly the
     * "Made for you" rail on Home and must not grow with whatever was last searched.
     */
    private val foundPlaylists = java.util.concurrent.ConcurrentHashMap<String, Playlist>()

    override fun playlist(id: String): Playlist? =
        personalPlaylists.firstOrNull { it.id == id } ?: accountPlaylists.firstOrNull { it.id == id } ?: foundPlaylists[id]

    // ---- profile: the account's own playlists and favourites ----

    @Volatile private var accountPlaylists: List<Playlist> = emptyList()
    @Volatile private var favoriteIds: Set<String> = emptySet()
    @Volatile private var libraryLoaded = false
    private val favLock = Any()
    override val libraryPlaylists: List<Playlist> get() = accountPlaylists
    override val favoriteTrackIds: Set<String> get() = favoriteIds

    internal fun hydrateFavoritesFromStore() {
        val store = favoritesStore ?: return
        val (ids, infos) = runCatching { store.loadAll() }.getOrNull() ?: return
        if (infos.isNotEmpty()) {
            for (info in infos) {
                artistMap.putIfAbsent(info.artist.id, info.artist)
                albumMap.putIfAbsent(info.album.id, info.album)
                trackMap.putIfAbsent(info.id, info)
            }
        }
        synchronized(favLock) {
            if (!libraryLoaded) {
                favoriteIds = ids
            }
        }
        if (ids.isNotEmpty()) changed()
    }

    /** Clears in-memory and persisted account favourites (e.g. on sign-out or account switch). */
    fun clearFavorites() {
        runCatching { favoritesStore?.clear() }
        synchronized(favLock) {
            libraryLoaded = false
            favoriteIds = emptySet()
        }
        changed()
    }

    /**
     * Persists the favourite locally first (`pending_add` / `pending_remove`) so cold boots and
     * offline toggles never lose state, then pushes to Apple's ratings endpoint.
     */
    override suspend fun setFavorite(trackId: String, favorite: Boolean): Boolean {
        val info = track(trackId)
        favoritesStore?.let { store ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { store.upsertLocal(info, trackId, favorite) }
            }
        }
        synchronized(favLock) {
            favoriteIds = if (favorite) favoriteIds + trackId else favoriteIds - trackId
        }
        changed()
        val ok = engine.rateSong(trackId, if (favorite) 1 else null)
        if (ok) {
            favoritesStore?.let { store ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { store.markSynced(trackId, favorite) }
                }
            }
        }
        return ok
    }

    /**
     * Reads the account's playlists. Apple has no favourites endpoint for this token; favourites
     * are the system playlist the user cannot edit ("Favourite Songs", localised), so its songs
     * are loaded here and exposed as [favoriteTrackIds].
     */
    suspend fun loadLibrary() {
        favoritesStore?.let { store ->
            val pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { store.pendingOps() }.getOrDefault(emptyList())
            }
            for ((id, fav) in pending) {
                if (engine.rateSong(id, if (fav) 1 else null)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { store.markSynced(id, fav) }
                    }
                }
            }
        }
        val data = engine.libraryPlaylists()?.optJSONArray("data") ?: return
        val loaded = accountPlaylists.filter { it.trackIds.isNotEmpty() }.associateBy { it.id }
        val fresh = ArrayList<Playlist>(data.length())
        val favorites: String? = Mapping.favouritesPlaylistId(data)
        for (i in 0 until data.length()) {
            val json = data.optJSONObject(i) ?: continue
            val playlist = Mapping.libraryPlaylist(json) ?: continue
            fresh += loaded[playlist.id]?.let { playlist.copy(trackIds = it.trackIds) } ?: playlist
        }
        accountPlaylists = fresh
        CadenceLog.i("library", "account playlists=${fresh.size} favourites=${favorites != null}")
        if (favorites != null) {
            val favTracks = preparePlaylist(favorites)
            if (favoritesStore != null) {
                val mergedIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        favoritesStore.reconcileRemote(favTracks)
                        favoritesStore.loadAll().first
                    }.getOrNull()
                }
                synchronized(favLock) {
                    libraryLoaded = true
                    if (mergedIds != null) favoriteIds = mergedIds
                }
            } else {
                synchronized(favLock) {
                    libraryLoaded = true
                    favoriteIds = favTracks.map { it.id }.toSet()
                }
            }
        }
        CadenceLog.i("library", "favourite songs=${favoriteIds.size}")
        changed()
    }

    // ---- the account's own library ----

    @Volatile private var account = AccountLibrary.EMPTY
    override val accountLibrary: AccountLibrary get() = account

    /**
     * Reads the Apple account's library (songs, albums, artists) through their catalogue
     * counterparts, which is what can be played. Each kind is published as soon as it is read,
     * so Library fills in while the rest loads. A kind that fails to load keeps what the cache
     * restored instead of emptying the screen.
     */
    suspend fun loadAccountLibrary() {
        libraryIds("songs", account.trackIds.isEmpty()) { (songHit(it, 0) as? SearchHit.SongHit)?.track?.id }
            ?.let { account = account.copy(trackIds = it); changed() }
        libraryIds("albums", account.albumIds.isEmpty()) { (albumHit(it, 0) as? SearchHit.AlbumHit)?.album?.id }
            ?.let { account = account.copy(albumIds = it); changed() }
        libraryIds("artists", account.artistIds.isEmpty()) { (artistHit(it, 0) as? SearchHit.ArtistHit)?.artist?.id }
            ?.let { account = account.copy(artistIds = it); changed() }
        CadenceLog.i("library", "account songs=${account.trackIds.size} albums=${account.albumIds.size} artists=${account.artistIds.size}")
    }

    /**
     * Catalogue ids of one kind of the account library, page by page. Null when a page fails and
     * there is something to keep; with nothing to keep, whatever was read is better than nothing.
     */
    private suspend fun libraryIds(kind: String, nothingToKeep: Boolean, map: (JSONObject) -> String?): Set<String>? {
        val ids = LinkedHashSet<String>()
        var offset = 0
        while (offset < MAX_ACCOUNT_LIBRARY_ITEMS) {
            val page = engine.libraryPage(kind, offset)
                ?: return if (nothingToKeep) ids.takeIf { it.isNotEmpty() } else null
            val rows = page.optJSONArray("data")?.length() ?: 0
            Mapping.catalogCounterparts(page).forEach { json -> map(json)?.let(ids::add) }
            if (rows == 0 || !page.has("next")) break
            offset += rows
        }
        return ids
    }

    private suspend fun loadStationTracks(appleId: String, existing: Playlist?): List<String>? {
        val candidates = buildList {
            add(appleId)
            if (existing != null) {
                val newer = synchronized(playlistsLock) {
                    personalPlaylists.firstOrNull {
                        it.id.startsWith("st:") &&
                            it.id.removePrefix("st:") != appleId &&
                            it.title.equals(existing.title, ignoreCase = true)
                    }
                }
                if (newer != null) add(newer.id.removePrefix("st:"))
            }
        }
        for (candidate in candidates) {
            val data = engine.stationNextTracks(candidate)?.optJSONArray("data") ?: continue
            val ids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let(::mapPlaylistTrack)?.let(ids::add)
            }
            if (ids.isNotEmpty()) return ids
        }
        return null
    }

    override suspend fun prepareTracks(ids: Collection<String>) {
        // Catalogue song ids are numeric; anything else (library ids) is not looked up.
        val missing = ids.filter { it.isNotEmpty() && it.all(Char::isDigit) && trackMap[it] == null }.distinct()
        if (missing.isEmpty()) return
        var found = 0
        for (chunk in missing.chunked(100)) {
            val data = engine.songsByIds(chunk)?.optJSONArray("data") ?: continue
            for (i in 0 until data.length()) if (data.optJSONObject(i)?.let { songHit(it, 0) } != null) found++
        }
        CadenceLog.i("library", "saved songs missing from memory=${missing.size} resolved=$found")
        if (found > 0) changed()
    }

    /** Songs of a library playlist, through their catalogue counterparts (what can be played). */
    private suspend fun libraryPlaylistSongs(playlistId: String): List<String>? {
        val ids = ArrayList<String>()
        var offset = 0
        while (offset < MAX_LIBRARY_PLAYLIST_SONGS) {
            val page = engine.libraryPlaylistTracks(playlistId.removePrefix("lp:"), offset)
                ?: return if (offset == 0) null else ids
            val data = page.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val catalog = data.optJSONObject(i)?.optJSONObject("relationships")?.optJSONObject("catalog")
                    ?.optJSONArray("data")?.optJSONObject(0) ?: continue // uploaded/unmatched songs have none
                (songHit(catalog, 0) as? SearchHit.SongHit)?.let { ids += it.track.id }
            }
            if (data.length() == 0 || !page.has("next")) break
            offset += data.length()
        }
        return ids
    }

    // ---- artist page ----

    private val artistTop = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val artistSimilar = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val artistFeatured = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    override fun topSongs(artistId: String): List<TrackInfo> =
        artistTop[artistId]?.mapNotNull(::track)?.takeIf { it.isNotEmpty() } ?: trackMap.values.filter { it.artist.id == artistId }

    private val albumVersions = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val albumRelated = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    override fun otherVersions(albumId: String): List<Album> = albumVersions[albumId].orEmpty().mapNotNull(::album)
    override fun relatedAlbums(albumId: String): List<Album> = albumRelated[albumId].orEmpty().mapNotNull(::album)

    override suspend fun prepareAlbumExtras(albumId: String) {
        if (albumRelated.containsKey(albumId) || !albumId.all(Char::isDigit)) return
        val views = engine.albumViews(albumId)?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("views") ?: return
        fun ids(name: String) = hitsOf(views.optJSONObject(name)?.optJSONArray("data")).filterIsInstance<SearchHit.AlbumHit>().map { it.album.id }
        albumVersions[albumId] = ids("other-versions")
        // Registered so the artist's row on the page is complete; the page reads it by artist.
        ids("more-by-artist")
        albumRelated[albumId] = ids("you-might-also-like")
        CadenceLog.i("album", "$albumId versions=${albumVersions[albumId]?.size} related=${albumRelated[albumId]?.size}")
    }

    override fun similarArtists(artistId: String): List<Artist> = artistSimilar[artistId].orEmpty().mapNotNull(::artist)
    override fun artistPlaylists(artistId: String): List<Playlist> = artistFeatured[artistId].orEmpty().mapNotNull(::playlist)

    /** Biography, origin and Apple's curated views for one artist, registered under [artistId]. */
    private suspend fun loadArtistDetail(artistId: String, realId: String) {
        val res = engine.artist(realId)?.optJSONArray("data")?.optJSONObject(0) ?: return
        applyArtistDetail(artistId, res)
    }

    /** What an artist resource (with its views) means for [artistId] (the request is apart, for tests). */
    internal fun applyArtistDetail(artistId: String, res: JSONObject) {
        val attrs = res.optJSONObject("attributes") ?: return
        val mapped = Mapping.artist(res) ?: return
        val genres = attrs.optJSONArray("genreNames")?.let { g -> (0 until g.length()).map(g::optString) }.orEmpty()
        val bio = Mapping.plainText(attrs.optString("artistBio"))
        artistMap[artistId] = mapped.copy(
            id = artistId,
            bio = bio,
            origin = attrs.optString("origin"),
            formed = attrs.optString("bornOrFormed"),
            genres = genres,
        )
        fun view(name: String) = res.optJSONObject("views")?.optJSONObject(name)?.optJSONArray("data")
        artistTop[artistId] = hitsOf(view("top-songs")).filterIsInstance<SearchHit.SongHit>().map { it.track.id }
        // The albums of this artist's own views belong to this artist (see adoptAlbum).
        for (name in listOf("latest-release", "full-albums", "singles")) {
            hitsOf(view(name)).filterIsInstance<SearchHit.AlbumHit>().forEach { adoptAlbum(artistId, it.album) }
        }
        artistSimilar[artistId] = hitsOf(view("similar-artists")).filterIsInstance<SearchHit.ArtistHit>().map { it.artist.id }
        artistFeatured[artistId] = hitsOf(view("featured-playlists")).filterIsInstance<SearchHit.PlaylistHit>().map { it.playlist.id }
        CadenceLog.i("artist", "'${mapped.name}' bio=${bio.length} top=${artistTop[artistId]?.size} " +
            "similar=${artistSimilar[artistId]?.size} playlists=${artistFeatured[artistId]?.size}")
    }

    /**
     * The display id of the artist a resource credits. Albums and songs only carry `artistName`, so the
     * legacy id is the name-based base id. When the resource's `relationships.artists` names exactly one
     * catalogue artist (or one whose name matches), that artist's own id is used: the base id if it owns
     * the name, a qualified id if another artist does. Credits like "A & B" that match nothing stay
     * name-based. (Apple documents these relationships as ids only, so in practice the sole-artist rule
     * is what resolves.)
     */
    private fun resolveArtistId(legacyId: String, resource: JSONObject?): String {
        val name = ArtistIds.nameOf(legacyId)
        val related = resource?.optJSONObject("relationships")?.optJSONObject("artists")?.optJSONArray("data") ?: return legacyId
        val match = (0 until related.length()).mapNotNull(related::optJSONObject)
            .firstOrNull { it.optJSONObject("attributes")?.optString("name").equals(name, ignoreCase = true) }
            ?: related.optJSONObject(0)?.takeIf { related.length() == 1 }
        val catalogId = match?.optString("id")?.takeIf { it.isNotBlank() } ?: return legacyId
        return displayIdFor(catalogId, name)
    }

    /**
     * The display id for a catalogue artist: the base id `ar:<name>` when this artist owns the name
     * (binding it if nobody does yet), otherwise the qualified id that carries the catalogue id.
     */
    internal fun displayIdFor(catalogId: String, name: String): String {
        val base = ArtistIds.base(name)
        return if (bindCatalogId(base, catalogId)) base else ArtistIds.qualified(name, catalogId)
    }

    /** The numeric catalogue id an artist row opens (test hook for the display-id -> catalogue-id map). */
    internal fun realArtistId(displayId: String): String? = ArtistIds.catalogIdOf(displayId) ?: realArtistIds[displayId]

    /**
     * Binds a display artist to a catalogue id, only when none is bound yet. Artists are identified by
     * name here and Apple has different artists with the same name, so the several code paths that learn
     * an id (search artists, the `relationships.artists` of album/song hits, the artist page) must not
     * overwrite each other: the first binding stays. Returns whether the display id is bound to
     * [catalogId] afterwards.
     */
    internal fun bindCatalogId(displayId: String, catalogId: String): Boolean {
        if (catalogId.isBlank()) return false
        val previous = realArtistIds.putIfAbsent(displayId, catalogId)
        if (previous == null && !ArtistIds.isQualified(displayId)) scheduleSave()
        return previous == null || previous == catalogId
    }

    /**
     * Two artists with one name in one response (e.g. the two "Joey Yung"s): which owns the plain name
     * is decided by the rule the artist page always used (the first exact-name match of a name search,
     * [lookup]), not by their order in this response, so a follow stored before ids were qualified keeps
     * pointing at the same artist. One extra request, only for a name that collides and is not bound yet.
     */
    internal suspend fun bindCollidingNames(root: JSONObject, lookup: suspend (String) -> String?) {
        val data = root.optJSONObject("results")?.optJSONObject("artists")?.optJSONArray("data") ?: return
        val idsByName = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
            idsByName.getOrPut(name) { ArrayList() }.add(id)
        }
        for ((name, ids) in idsByName) {
            if (ids.distinct().size < 2 || realArtistIds.containsKey(ArtistIds.base(name))) continue
            val owner = lookup(name)?.takeIf { it in ids } ?: continue
            bindCatalogId(ArtistIds.base(name), owner)
        }
    }

    /** The artist page's long-standing rule: the first exact-name match of an artist search. */
    private suspend fun legacyArtistLookup(name: String): String? =
        engine.get("search?term=${java.net.URLEncoder.encode(name, "UTF-8")}&types=artists&limit=5")
            ?.optJSONObject("results")?.optJSONObject("artists")?.optJSONArray("data")?.let { data ->
                (0 until data.length()).mapNotNull(data::optJSONObject)
                    .firstOrNull { it.optJSONObject("attributes")?.optString("name").equals(name, ignoreCase = true) }
                    ?.optString("id")?.takeIf { it.isNotBlank() }
            }

    private fun artistHit(json: JSONObject, score: Int): SearchHit? {
        val found = Mapping.artist(json) ?: return null
        // The artist's own display id: the plain name if it owns it, else the qualified id.
        val catalogId = json.optString("id").takeIf { it.isNotBlank() }
        val displayId = if (catalogId != null) displayIdFor(catalogId, found.name) else found.id
        val candidate = if (displayId == found.id) found else found.copy(id = displayId)
        // A list entry carries no biography; keep the one a detail load already stored.
        val artist = artistMap[displayId]?.takeIf { it.bio.isNotBlank() }?.copy(color = candidate.color, cover = candidate.cover) ?: candidate
        artistMap[displayId] = artist
        return SearchHit.ArtistHit(artist, score)
    }

    private fun albumHit(json: JSONObject, score: Int): SearchHit? {
        val found = Mapping.album(json) ?: return null
        val album = found.copy(artistId = resolveArtistId(found.artistId, json))
        albumMap[album.id] = album
        placeholderArtist(album.artistId)
        return SearchHit.AlbumHit(album, ArtistIds.nameOf(album.artistId), score)
    }

    private val albumIdInUrl = Regex("/album/[^/]+/(\\d+)")

    private fun songHit(json: JSONObject, score: Int): SearchHit? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val artistId = resolveArtistId(ArtistIds.base(attrs.optString("artistName")), json)
        placeholderArtist(artistId)
        // The song's own album: the included relationship when present, else the id in its URL
        // (`.../album/<slug>/<albumId>?i=<songId>`). Never an id derived from the album's name.
        val related = json.optJSONObject("relationships")?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)
        val albumId = related?.optString("id")?.takeIf { it.isNotBlank() }
            ?: albumIdInUrl.find(attrs.optString("url"))?.groupValues?.get(1)
            ?: return null
        val album = albumMap[albumId]
            ?: related?.let(Mapping::album)
            ?: Mapping.albumFromSong(json, albumId, artistId)
        albumMap[albumId] = album
        placeholderArtist(album.artistId)
        val track = Mapping.track(json, albumId)?.copy(artistId = artistId) ?: return null
        val info = TrackInfo(track, album, artistMap.getValue(artistId))
        trackMap[track.id] = info
        return SearchHit.SongHit(info, score)
    }

    private fun playlistHit(json: JSONObject, score: Int): SearchHit? {
        val found = Mapping.playlist(json) ?: return null
        // Keep the already-loaded copy (it may carry its tracklist) over a fresh empty one.
        val playlist = playlist(found.id) ?: found.also { foundPlaylists[it.id] = it }
        return SearchHit.PlaylistHit(playlist, score)
    }

    /**
     * What makes two Apple resources the same row: Apple's own id. (Two different artists can share a
     * name; they get different display ids in [artistHit], so only a true repeat is dropped here.)
     */
    private fun resourceIdentity(json: JSONObject): String? {
        val type = json.optString("type")
        return when (type) {
            // An artist is the catalogue artist (two artists may share a name); the others by Apple's id.
            "artists", "albums", "songs", "playlists" ->
                json.optString("id").takeIf { it.isNotBlank() }?.let { "$type:$it" }
                    ?: if (type == "artists") Mapping.artist(json)?.id?.let { "$type:$it" } else null
            else -> null
        }
    }

    /**
     * Maps Apple resources to hits in the order given; the score only encodes that order. A resource
     * that repeats an earlier one's identity is skipped before it is mapped: the first (Apple's best
     * ranked) stays the row, and its caches are not overwritten by the repeat.
     */
    internal fun hitsOf(data: JSONArray?): List<SearchHit> {
        if (data == null) return emptyList()
        val out = ArrayList<SearchHit>(data.length())
        val seen = HashSet<String>()
        for (i in 0 until data.length()) {
            val json = data.optJSONObject(i) ?: continue
            val identity = resourceIdentity(json) ?: continue
            if (!seen.add(identity)) continue
            val score = (100 - out.size).coerceAtLeast(1)
            val hit = when (json.optString("type")) {
                "artists" -> artistHit(json, score)
                "albums" -> albumHit(json, score)
                "songs" -> songHit(json, score)
                "playlists" -> playlistHit(json, score)
                else -> null
            }
            if (hit != null) out += hit
        }
        return out
    }

    private val sections = listOf(
        "artists" to HitType.Artist, "albums" to HitType.Album,
        "songs" to HitType.Song, "playlists" to HitType.Playlist,
    )

    /**
     * Catalogue search in Apple's own relevance order: `topResults` first, then the sections
     * in the order `meta.results.order` ranks them for this query.
     */
    suspend fun search(term: String): SearchResults {
        val root = engine.search(term) ?: return SearchResults(emptyList(), emptyMap())
        return searchFrom(root, term, ::legacyArtistLookup)
    }

    /** [search] on Apple's response: settle which colliding artist owns a plain name, then build the lists. */
    internal suspend fun searchFrom(root: JSONObject, term: String, lookup: suspend (String) -> String?): SearchResults {
        bindCollidingNames(root, lookup)
        return resultsFrom(root, term)
    }

    /** The result building of [search] on Apple's response, apart from the request (testable offline). */
    internal fun resultsFrom(root: JSONObject, term: String): SearchResults {
        val results = root.optJSONObject("results") ?: return SearchResults(emptyList(), emptyMap())
        val byKey = HashMap<String, List<SearchHit>>()
        val byType = LinkedHashMap<HitType, List<SearchHit>>()
        for ((key, type) in sections) {
            val hits = hitsOf(results.optJSONObject(key)?.optJSONArray("data"))
            if (hits.isNotEmpty()) { byKey[key] = hits; byType[type] = hits }
        }
        val ranked = hitsOf(results.optJSONObject("topResults")?.optJSONArray("data"))
        val order = root.optJSONObject("meta")?.optJSONObject("results")?.optJSONArray("order")
            ?.let { o -> (0 until o.length()).map(o::optString) }
            ?: sections.map { it.first }
        val top = (ranked + order.flatMap { byKey[it].orEmpty() }).distinctBy { it.key }.take(12)
        CadenceLog.i("search", "term='$term' top=${top.size} ranked=${ranked.size} " +
            "byType=${byType.mapValues { it.value.size }} order=$order")
        return SearchResults(top, byType)
    }

    /** Live suggestions from Apple: completed terms, then direct matches. */
    suspend fun suggest(term: String): app.cadence.backend.api.Suggestions {
        val list = engine.suggestions(term)?.optJSONObject("results")?.optJSONArray("suggestions")
            ?: return app.cadence.backend.api.Suggestions(emptyList(), emptyList())
        val terms = ArrayList<String>()
        val content = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            when (item.optString("kind")) {
                "terms" -> item.optString("displayTerm").ifBlank { item.optString("searchTerm") }
                    .takeIf { it.isNotBlank() }?.let(terms::add)
                "topResults" -> item.optJSONObject("content")?.let(content::put)
            }
        }
        val typed = term.trim()
        return app.cadence.backend.api.Suggestions(
            completions = terms.distinct().filterNot { it.equals(typed, ignoreCase = true) }.take(6),
            matches = hitsOf(content).take(6),
        )
    }

    private companion object {
        const val MAX_LIBRARY_PLAYLIST_SONGS = 500
        /** Per kind; the account this was built against has about 800 songs and 500 albums. */
        const val MAX_ACCOUNT_LIBRARY_ITEMS = 5000
    }

    private val artistLoad = kotlinx.coroutines.sync.Mutex()

    /**
     * Loads the artist's discography before their page shows it. Artists restored from the
     * disk cache have no catalogue id yet, so it is resolved by an exact-name artist search.
     */
    override suspend fun prepareArtist(artistId: String): List<Album> = artistLoad.withLock {
        if (artistId !in loadedArtists) {
            val name = artistMap[artistId]?.name ?: ArtistIds.nameOf(artistId)
            // A qualified id carries its catalogue id; a base id is bound, else found by the name search.
            val realId = ArtistIds.catalogIdOf(artistId) ?: realArtistIds[artistId] ?: legacyArtistLookup(name)
            if (realId != null) {
                bindCatalogId(artistId, realId)
                val bound = ArtistIds.catalogIdOf(artistId) ?: realArtistIds[artistId] ?: realId
                loadArtistDetail(artistId, bound)
                loadArtistAlbums(artistId, bound)
                loadedArtists += artistId
            } else {
                CadenceLog.i("artist", "no catalogue id for '$name'; showing cached albums only")
            }
        }
        albumsOfArtist(artistId)
    }

    suspend fun loadAlbumTracks(albumId: String) {
        val root = engine.albumTracks(albumId) ?: return
        val data = root.optJSONArray("data") ?: return
        // Marked only once the tracklist arrived, so a failed fetch is retried on the next open.
        loadedAlbums += albumId
        for (i in 0 until data.length()) {
            val json = data.optJSONObject(i) ?: continue
            val parsed = Mapping.track(json, albumId) ?: continue
            val t = resolveArtistId(parsed.artistId, json).let { id -> if (id == parsed.artistId) parsed else parsed.copy(artistId = id) }
            val album = albumMap[albumId] ?: Album(
                id = albumId, title = "", artistId = t.artistId, year = 0,
                genre = "Music", colors = listOf("#6750A4", "#21005D"),
            )
            placeholderArtist(t.artistId)
            // Re-insert so the album reads in Apple's order even if a song was cached earlier.
            trackMap.moveToEnd(t.id, TrackInfo(t, album, artistMap.getValue(t.artistId)))
        }
    }

    /** Enumerates an artist's albums using the numeric catalogue id. */
    suspend fun loadArtistAlbums(displayId: String, realId: String) {
        if (realId.isBlank()) return
        val root = engine.artistAlbums(realId) ?: return
        applyArtistAlbums(displayId, root)
    }

    /** What an artist's albums response means for [displayId] (the request is apart, for tests). */
    internal fun applyArtistAlbums(displayId: String, root: JSONObject) {
        val data = root.optJSONArray("data") ?: return
        for (i in 0 until data.length()) {
            val alb = Mapping.album(data.optJSONObject(i) ?: continue) ?: continue
            albumMap[alb.id] = alb
            placeholderArtist(alb.artistId)
            adoptAlbum(displayId, alb)
        }
    }

    /**
     * An album returned by this artist's own calls belongs to this artist: it joins the artist's album
     * list, and an album credited to exactly this artist's name (a base id, i.e. not already resolved to a
     * catalogue artist) is re-credited to this display id, so its artist link opens this artist and not
     * whichever artist owns the plain name.
     */
    private fun adoptAlbum(displayId: String, album: Album) {
        artistAlbums.merge(displayId, listOf(album.id)) { old, new -> (old + new).distinct() }
        if (album.artistId != displayId && !ArtistIds.isQualified(album.artistId) &&
            ArtistIds.nameOf(album.artistId) == ArtistIds.nameOf(displayId)
        ) {
            albumMap[album.id] = album.copy(artistId = displayId)
            placeholderArtist(displayId)
        }
    }
}
