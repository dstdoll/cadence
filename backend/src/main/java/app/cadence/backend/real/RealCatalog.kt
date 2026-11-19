package app.cadence.backend.real

import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.Ttml
import app.cadence.backend.lyrics.LrcParser
import app.cadence.backend.engine.Mapping
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
) : app.cadence.backend.api.MusicRepository {

    // The UI reads these from the main thread while the seed, searches and detail loads write
    // from background threads, so every shared structure here is safe for concurrent use.
    private val artistMap = OrderedCache<String, Artist>()
    private val albumMap = OrderedCache<String, Album>()
    private val trackMap = OrderedCache<String, TrackInfo>()
    private val loadedAlbums: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val loadedArtists: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** display artist id (`ar:<name>`) -> numeric catalogue id, taken from relationships. */
    private val realArtistIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val artists: List<Artist> get() = artistMap.values
    override val albums: List<Album> get() = albumMap.values
    override val tracks: List<TrackInfo> get() = trackMap.values
    /** Recommendations rendered as "Made for you" cards (album art + its tracklist). */
    /** An immutable list swapped whole, so Home can iterate it while a refresh publishes the next one. */
    @Volatile private var personalPlaylists: List<Playlist> = emptyList()
    private val playlistsLock = Any()
    override val playlists: List<Playlist> get() = personalPlaylists

    /** Registers a name-only artist unless a richer record is already cached. */
    private fun placeholderArtist(artistId: String) =
        artistMap.putIfAbsent(artistId, Artist(artistId, artistId.removePrefix("ar:"), "", "#888888"))

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
    override suspend fun preparePlaylist(playlistId: String): List<TrackInfo> = playlistLoad.withLock {
        playlist(playlistId)?.takeIf { it.trackIds.isNotEmpty() }
            ?.let { return@withLock it.trackIds.mapNotNull(::track) }
        if (playlistId.startsWith("st:")) return@withLock emptyList()
        if (playlistId.startsWith("lp:")) {
            val ids = libraryPlaylistSongs(playlistId)
            accountPlaylists = accountPlaylists.map { if (it.id == playlistId) it.copy(trackIds = ids) else it }
            CadenceLog.i("playlist", "open $playlistId (library) tracks=${ids.size}")
            return@withLock ids.mapNotNull(::track)
        }
        val appleId = playlistId.removePrefix("pl:")
        val root = engine.playlistTracks(appleId) ?: return@withLock emptyList()
        val songs = root.optJSONArray("data")?.optJSONObject(0)
            ?.optJSONObject("relationships")?.optJSONObject("tracks")?.optJSONArray("data")
            ?: return@withLock emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val attrs = song.optJSONObject("attributes") ?: continue
            val artist = Mapping.artistFromName(attrs.optString("artistName"))
            artistMap.putIfAbsent(artist.id, artist)
            val albumId = song.optJSONObject("relationships")?.optJSONObject("albums")
                ?.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: "plalb:${attrs.optString("albumName")}:${artist.id}"
            val album = albumMap[albumId] ?: Mapping.albumFromSong(song, albumId, artist.id).also { albumMap[albumId] = it }
            val tr = Mapping.track(song, albumId) ?: continue
            trackMap[tr.id] = TrackInfo(tr, album, artist)
            ids += tr.id
        }
        synchronized(playlistsLock) {
            val idx = personalPlaylists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) {
                personalPlaylists = personalPlaylists.toMutableList().also { it[idx] = it[idx].copy(trackIds = ids) }
            }
        }
        foundPlaylists[playlistId]?.let { foundPlaylists[playlistId] = it.copy(trackIds = ids) }
        CadenceLog.i("playlist", "open $playlistId tracks=${ids.size}")
        ids.mapNotNull(::track)
    }

    override fun albumsOfArtist(artistId: String): List<Album> =
        albumMap.values.filter { it.artistId == artistId }

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
        val file = cacheFile ?: return false
        if (!file.exists()) return false
        return runCatching {
            val doc = json.decodeFromString<Catalog>(file.readText())
            if (doc.albums.isEmpty()) return false
            doc.artists.forEach { artistMap[it.id] = it }
            doc.albums.forEach { albumMap[it.id] = it }
            synchronized(playlistsLock) { personalPlaylists = personalPlaylists + doc.playlists }
            account = doc.account
            doc.tracks.forEach { t ->
                val album = albumMap[t.albumId] ?: return@forEach
                val artist = artistMap[t.artistId] ?: Artist(t.artistId, t.artistId.removePrefix("ar:"), "", "#888888")
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
        runCatching {
            val doc = Catalog(
                artists = artistMap.values.toList(),
                albums = albumMap.values.toList(),
                tracks = trackMap.values.map { it.track },
                playlists = personalPlaylists.toList(),
                account = account,
            )
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Catalog.serializer(), doc))
            CadenceLog.i("cache", "saved albums=" + doc.albums.size + " bytes=" + file.length())
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
    override val libraryPlaylists: List<Playlist> get() = accountPlaylists
    override val favoriteTrackIds: Set<String> get() = favoriteIds

    /**
     * Pushes a favourite to the real account via Apple's ratings endpoint (love = 1, or clears it),
     * then updates [favoriteTrackIds] locally so the UI reflects it without waiting for the next
     * profile sync. Only mutates local state when the service accepted the change.
     */
    override suspend fun setFavorite(trackId: String, favorite: Boolean): Boolean {
        val ok = engine.rateSong(trackId, if (favorite) 1 else null)
        if (ok) {
            favoriteIds = if (favorite) favoriteIds + trackId else favoriteIds - trackId
            changed()
        }
        return ok
    }

    /**
     * Reads the account's playlists. Apple has no favourites endpoint for this token; favourites
     * are the system playlist the user cannot edit ("Favourite Songs", localised), so its songs
     * are loaded here and exposed as [favoriteTrackIds].
     */
    suspend fun loadLibrary() {
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
        favorites?.let { id -> favoriteIds = preparePlaylist(id).map { it.id }.toSet() }
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
    private suspend fun libraryPlaylistSongs(playlistId: String): List<String> {
        val ids = ArrayList<String>()
        var offset = 0
        while (offset < MAX_LIBRARY_PLAYLIST_SONGS) {
            val page = engine.libraryPlaylistTracks(playlistId.removePrefix("lp:"), offset) ?: break
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
        for (name in listOf("latest-release", "full-albums", "singles")) hitsOf(view(name))
        artistSimilar[artistId] = hitsOf(view("similar-artists")).filterIsInstance<SearchHit.ArtistHit>().map { it.artist.id }
        artistFeatured[artistId] = hitsOf(view("featured-playlists")).filterIsInstance<SearchHit.PlaylistHit>().map { it.playlist.id }
        CadenceLog.i("artist", "'${mapped.name}' bio=${bio.length} top=${artistTop[artistId]?.size} " +
            "similar=${artistSimilar[artistId]?.size} playlists=${artistFeatured[artistId]?.size}")
    }

    /** Registers the display artist and, when the resource names it, its catalogue id. */
    private fun rememberArtist(artistId: String, resource: JSONObject?) {
        val name = artistId.removePrefix("ar:")
        placeholderArtist(artistId)
        val related = resource?.optJSONObject("relationships")?.optJSONObject("artists")?.optJSONArray("data") ?: return
        // "A & B" credits list several artists; only an exact name match (or a sole artist)
        // identifies which catalogue id belongs to this display name.
        val match = (0 until related.length()).mapNotNull(related::optJSONObject)
            .firstOrNull { it.optJSONObject("attributes")?.optString("name").equals(name, ignoreCase = true) }
            ?: related.optJSONObject(0)?.takeIf { related.length() == 1 }
        match?.optString("id")?.takeIf { it.isNotBlank() }?.let { realArtistIds[artistId] = it }
    }

    private fun artistHit(json: JSONObject, score: Int): SearchHit? {
        val found = Mapping.artist(json) ?: return null
        // A list entry carries no biography; keep the one a detail load already stored.
        val artist = artistMap[found.id]?.takeIf { it.bio.isNotBlank() }?.copy(color = found.color, cover = found.cover) ?: found
        artistMap[artist.id] = artist
        json.optString("id").takeIf { it.isNotBlank() }?.let { realArtistIds[artist.id] = it }
        return SearchHit.ArtistHit(artist, score)
    }

    private fun albumHit(json: JSONObject, score: Int): SearchHit? {
        val album = Mapping.album(json) ?: return null
        albumMap[album.id] = album
        rememberArtist(album.artistId, json)
        return SearchHit.AlbumHit(album, album.artistId.removePrefix("ar:"), score)
    }

    private val albumIdInUrl = Regex("/album/[^/]+/(\\d+)")

    private fun songHit(json: JSONObject, score: Int): SearchHit? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val artistId = "ar:" + attrs.optString("artistName")
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
        rememberArtist(album.artistId, related)
        val track = Mapping.track(json, albumId) ?: return null
        rememberArtist(track.artistId, json)
        val info = TrackInfo(track, album, artistMap.getValue(track.artistId))
        trackMap[track.id] = info
        return SearchHit.SongHit(info, score)
    }

    private fun playlistHit(json: JSONObject, score: Int): SearchHit? {
        val found = Mapping.playlist(json) ?: return null
        // Keep the already-loaded copy (it may carry its tracklist) over a fresh empty one.
        val playlist = playlist(found.id) ?: found.also { foundPlaylists[it.id] = it }
        return SearchHit.PlaylistHit(playlist, score)
    }

    /** Maps Apple resources to hits in the order given; the score only encodes that order. */
    private fun hitsOf(data: JSONArray?): List<SearchHit> {
        if (data == null) return emptyList()
        val out = ArrayList<SearchHit>(data.length())
        for (i in 0 until data.length()) {
            val json = data.optJSONObject(i) ?: continue
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
            val name = artistMap[artistId]?.name ?: artistId.removePrefix("ar:")
            val realId = realArtistIds[artistId] ?: engine.get(
                "search?term=${java.net.URLEncoder.encode(name, "UTF-8")}&types=artists&limit=5",
            )?.optJSONObject("results")?.optJSONObject("artists")?.optJSONArray("data")?.let { data ->
                (0 until data.length()).mapNotNull(data::optJSONObject)
                    .firstOrNull { it.optJSONObject("attributes")?.optString("name").equals(name, ignoreCase = true) }
                    ?.optString("id")?.takeIf { it.isNotBlank() }
            }
            if (realId != null) {
                realArtistIds[artistId] = realId
                loadArtistDetail(artistId, realId)
                loadArtistAlbums(realId)
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
            val t = Mapping.track(json, albumId) ?: continue
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
    suspend fun loadArtistAlbums(realId: String) {
        if (realId.isBlank()) return
        loadedArtists += "ar:$realId"
        val root = engine.artistAlbums(realId) ?: return
        val data = root.optJSONArray("data") ?: return
        for (i in 0 until data.length()) {
            val alb = Mapping.album(data.optJSONObject(i) ?: continue) ?: continue
            albumMap[alb.id] = alb
            placeholderArtist(alb.artistId)
            realArtistIds[alb.artistId] = realId
        }
    }
}
