package app.cadence.backend.engine

import android.content.Context
import app.cadence.backend.model.*
import app.cadence.backend.engine.media.Http
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MusicKit credentials. Nothing here ships with the app: the developer token comes from [DevToken]
 * (read from Apple's web player and renewed on its own), and the account's token, storefront and
 * language come from signing in, or from an imported session file (Settings, Session) kept in
 * files/engine/amp.json.
 */
data class AmpConfig(
    val devToken: String,
    val mediaUserToken: String,
    val storefront: String,
    val language: String,
) {
    companion object {
        /** The stored config and token; no network. Null until a developer token has been obtained once. */
        fun load(context: Context): AmpConfig? = runCatching {
            val imported = SessionBundle.importedConfig(context.filesDir)
            val saved = if (imported.isFile) SecretFiles.read(imported)?.let { JSONObject(it) } else null
            // A token read from the web player is newer than the one an imported file carried.
            val dev = DevToken.cached(context) ?: saved?.optString("devToken").orEmpty()
            if (dev.isBlank()) null
            else AmpConfig(
                dev,
                saved?.optString("mediaUserToken").orEmpty(),
                saved?.optString("storefront", "us") ?: "us",
                saved?.optString("language", "en-US") ?: "en-US",
            )
        }.getOrNull()

        /**
         * [load], reading the developer token from the web player first when there is none yet (a first
         * start). Waits a few seconds at most; without a network the config carries no token and the
         * catalogue stays empty until the next start, while signing in still works.
         */
        fun loadOrFetch(context: Context): AmpConfig {
            load(context)?.let { return it }
            DevToken.ensureWithin(context, timeoutMs = 8_000)
            return load(context) ?: AmpConfig("", "", "us", "en-US")
        }
    }
}

/**
 * Catalogue access against amp-api. Token split:
 * `Authorization: Bearer <dev token>` for catalogue, `media-user-token` for subscriber
 * scoped data such as lyrics and `extendedAssetUrls`.
 */
class AmpEngine(private val cfg: AmpConfig) {
    companion object {
        private val engines = java.util.concurrent.ConcurrentHashMap<AmpConfig, AmpEngine>()

        /**
         * One engine per account config for the whole process, so playback, downloads and the
         * catalogue share its pacing gate (a 429 pause holds all of them), its response cache and
         * its storefront lookup, instead of each start of a song building a fresh engine.
         */
        fun shared(cfg: AmpConfig): AmpEngine = engines.getOrPut(cfg) { AmpEngine(cfg) }

        private const val ME_BASE = "https://amp-api.music.apple.com/v1/me/"
        /** Apple's own `Cache-Control: max-age=60` on catalogue responses. */
        private const val CATALOG_TTL_MS = 60_000L

        /** Subscriber-scoped ratings path for a song (relative to [ME_BASE]). */
        internal fun ratingsPath(trackId: String) = "ratings/songs/$trackId"
        /** Authoritative storefront most recently resolved from `/v1/me/storefront`. */
        @Volatile internal var latestStorefront: String? = null

        /** The PUT body that sets a rating: 1 = favourite (love), -1 = dislike. */
        internal fun ratingBody(value: Int) = """{"type":"rating","attributes":{"value":$value}}"""
    }

    /** Replaced after a real sign-in; the dev token and endpoints stay. */
    @Volatile private var mediaToken: String = cfg.mediaUserToken

    /**
     * Applies a freshly signed-in account so catalogue/lyrics requests use its
     * media token immediately, without restarting the app.
     */
    /** The storefront known right now (the account's once resolved, else the configured one); never blocks. */
    fun storefrontNow(): String = resolvedStorefront ?: cfg.storefront

    fun updateAccount(mediaUserToken: String, storefront: String?) {
        if (mediaUserToken.isNotBlank()) mediaToken = mediaUserToken
        // A blank storefront clears the cached one so storefront() re-resolves it from /v1/me/storefront
        // with the new token (the account's real country), instead of keeping the old default.
        resolvedStorefront = storefront?.takeIf { it.isNotBlank() }
        latestStorefront = resolvedStorefront
        // Cached catalogue rows carry asset URLs minted for the previous token.
        cache.clear()
        CadenceLog.i("amp", "account updated token=${if (mediaUserToken.isBlank()) "none" else "present"} storefront=${storefront ?: "-"}")
    }

    /**
     * Apple serves catalogue resources per storefront, and ids differ between them.
     * The account's own storefront (e.g. "gb") is authoritative - using a hardcoded
     * "us" makes lookups 404 even for tracks that exist.
     */
    @Volatile private var resolvedStorefront: String? = null

    suspend fun storefront(): String {
        resolvedStorefront?.let { return it }
        val fromAccount = runCatching {
            me("storefront")?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val value = fromAccount ?: cfg.storefront
        resolvedStorefront = value
        latestStorefront = value
        CadenceLog.i("amp", "storefront=" + value + (if (fromAccount != null) " (from account)" else " (config)"))
        return value
    }

    private val gate = AmpGate()
    private val cache = AmpResponseCache()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Requests that actually reached Apple, and ones answered from the short cache. */
    val networkCalls: Long get() = gate.granted.get()
    val cacheHits: Long get() = cache.hits.get()
    val throttledCount: Long get() = gate.throttled.get()

    private fun headers(accept: String): Map<String, String> {
        val headers = linkedMapOf(
            "Authorization" to "Bearer ${cfg.devToken}",
            "Origin" to "https://music.apple.com",
            "Accept" to accept,
        )
        if (mediaToken.isNotBlank()) {
            headers["media-user-token"] = mediaToken
            headers["Music-User-Token"] = mediaToken
        }
        return headers
    }

    /**
     * The single network path for amp-api. Every call is paced by [gate]; identical requests
     * in flight share one response; catalogue answers (and their 404s) are reused for the
     * 60 s Apple declares in `Cache-Control`. Only a network failure or a 5xx is retried.
     * A 4xx is an answer, and a 429 waits for `Retry-After` before a single further attempt.
     * Returns null when no response could be obtained.
     */
    private suspend fun fetch(url: String, accept: String, cacheable: Boolean): Http.Reply? = withContext(Dispatchers.IO) {
        val key = "$accept $url"
        if (cacheable) cache.get(key)?.let { return@withContext Http.Reply(it.code, it.body) }
        val flight = inFlight.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }
        flight.lock()
        try {
            if (cacheable) cache.get(key)?.let { return@withContext Http.Reply(it.code, it.body) }
            send(url, accept)?.also { reply ->
                if (cacheable && (reply.code in 200..299 || reply.code == 404)) cache.put(key, reply.code, reply.body, CATALOG_TTL_MS)
            }
        } finally {
            flight.unlock()
            inFlight.remove(key, flight)
        }
    }

    private suspend fun send(url: String, accept: String): Http.Reply? {
        val label = url.substringAfter("/v1/").substringBefore('?')
        var last: Http.Reply? = null
        for (attempt in 1..3) {
            val sentIn = gate.acquire()
            val reply = runCatching { Http.get(url, headers(accept)) }
                .onFailure { CadenceLog.i("amp", "FAILED $label: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            last = reply
            when {
                reply != null && reply.code >= 500 && reply.retryAfterSeconds != null -> {
                    // The server named its own wait (typically 503 + Retry-After): hold every
                    // caller for it, as for a 429, instead of retrying in 400 ms.
                    val wait = gate.throttled(reply.retryAfterSeconds, sentIn)
                    CadenceLog.e("amp", "${reply.code} on $label; pausing all requests ${wait}ms (Retry-After=${reply.retryAfterSeconds})")
                    if (attempt >= 2) return reply
                }
                reply == null || reply.code >= 500 -> {
                    if (attempt < 3) delay(400L * attempt)
                }
                reply.code == 429 -> {
                    val wait = gate.throttled(reply.retryAfterSeconds, sentIn)
                    CadenceLog.e("amp", "429 on $label; pausing all requests ${wait}ms (Retry-After=${reply.retryAfterSeconds})")
                    if (attempt >= 2) return reply
                }
                else -> {
                    gate.succeeded(sentIn)
                    CadenceLog.i("amp", "GET $label -> ${reply.code} bytes=${reply.body.length}")
                    return reply
                }
            }
        }
        return last
    }

    private suspend fun catalogUrl(path: String) =
        "https://amp-api.music.apple.com/v1/catalog/" + storefront() + "/$path"

    /**
     * Catalogue search. `with=topResults` adds Apple's own cross-type ranking
     * (`results.topResults`, plus `meta.results.order` for the sections), and the
     * relationship includes give every song its real album and artist ids, so
     * nothing about a hit has to be guessed from its display names.
     */
    suspend fun search(term: String): JSONObject? =
        get(
            "search?term=${URLEncoder.encode(term, "UTF-8")}" +
                "&types=albums,artists,songs,playlists&limit=20&with=topResults" +
                "&include%5Bsongs%5D=albums,artists&include%5Balbums%5D=artists" +
                "&extend=extendedAssetUrls",
        )

    /** As-you-type suggestions: completed search terms and a few direct matches. */
    suspend fun suggestions(term: String): JSONObject? =
        get(
            "search/suggestions?term=${URLEncoder.encode(term, "UTF-8")}" +
                "&kinds=terms,topResults&types=albums,artists,songs,playlists" +
                "&limit%5Bresults%3Aterms%5D=6&limit%5Bresults%3AtopResults%5D=6" +
                "&include%5Bsongs%5D=albums,artists&extend=extendedAssetUrls",
        )

    suspend fun albumTracks(albumId: String): JSONObject? =
        get("albums/$albumId/tracks?limit=100&extend=extendedAssetUrls")

    /**
     * Fresh song resource with `extendedAssetUrls`. Used before a download because
     * HLS asset URLs are tokenised and expire, and a cached catalogue row can
     * outlive its URL (or belong to the previous storefront).
     */
    suspend fun song(trackId: String): JSONObject? =
        get("songs/$trackId?extend=extendedAssetUrls")

    /** A catalogue resource as JSON, or null when Apple has none (4xx) or the network failed. */
    suspend fun get(path: String): JSONObject? {
        val reply = fetch(catalogUrl(path), "application/json", cacheable = true) ?: return null
        if (reply.code !in 200..299) {
            if (reply.code != 404) CadenceLog.e("amp", "${path.substringBefore('?')} -> ${reply.code} ${reply.body.take(160)}")
            return null
        }
        return runCatching { JSONObject(reply.body) }.getOrNull()
    }

    /**
     * Subscriber-scoped endpoints live under /v1/me and need the media user token. Apple marks
     * them `no-store`, so they are paced and de-duplicated in flight but never cached.
     */
    suspend fun me(path: String): JSONObject? {
        val reply = fetch(ME_BASE + path, "application/json", cacheable = false) ?: return null
        return if (reply.code in 200..299) runCatching { JSONObject(reply.body) }.getOrNull() else null
    }

    /**
     * Marks a song as favourite (love, rating value 1) or clears its rating (`value == null`), via
     * Apple's subscriber-scoped ratings endpoint. The "Favourite Songs" system playlist the app reads
     * for [favoriteTrackIds][app.cadence.backend.api.MusicRepository.favoriteTrackIds] is populated
     * from these ratings, so a favourite set here shows up there. Needs the media user token (sent by
     * [headers]); returns whether Apple accepted it (2xx, e.g. 200 for PUT or 204 for DELETE).
     */
    suspend fun rateSong(trackId: String, value: Int?): Boolean = withContext(Dispatchers.IO) {
        if (mediaToken.isBlank()) return@withContext false
        val url = ME_BASE + ratingsPath(trackId)
        val sentIn = gate.acquire()
        val reply = runCatching {
            if (value == null) Http.send(url, "DELETE", null, headers("application/json"))
            else Http.send(url, "PUT", ratingBody(value), headers("application/json"))
        }.onFailure { CadenceLog.i("amp", "FAILED rate $trackId: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        gate.succeeded(sentIn)
        val ok = reply != null && reply.code in 200..299
        CadenceLog.i("amp", "${if (value == null) "DELETE" else "PUT"} ratings/songs/$trackId -> ${reply?.code}")
        ok
    }

    /** Raw text GET, for TTML lyrics (the JSON path cannot decode them). */
    suspend fun getText(path: String): String? =
        fetch(catalogUrl(path), "application/ttml+xml", cacheable = true)?.takeIf { it.code in 200..299 }?.body

    /**
     * Lyrics are served as JSON carrying the TTML payload, not as a TTML media type.
     * Tries word timing first, then line timing; a 404 means the track has none.
     */
    suspend fun lyrics(songId: String): String? {
        val candidates = listOf(
            "songs/$songId/syllable-lyrics?l[lyrics]=${cfg.language}&extend=ttmlLocalizations&l[script]=en-Latn",
            "songs/$songId/lyrics?l[lyrics]=${cfg.language}&extend=ttmlLocalizations&l[script]=en-Latn",
            "songs/$songId/lyrics?l[lyrics]=${cfg.language}",
        )
        for (path in candidates) {
            val root = get(path) ?: continue
            val attrs = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
            val ttml = attrs?.optString("ttmlLocalizations")?.takeIf { it.isNotBlank() }
                ?: attrs?.optString("ttml")?.takeIf { it.isNotBlank() }
            if (ttml != null) {
                CadenceLog.i("lyrics", "song=$songId ttml=" + ttml.length + "b via " + path.substringBefore('?'))
                return ttml
            }
        }
        return null
    }

    /** Fetches Apple Music `audioAnalysis` (tempo, beats, bars, loudness, vocals) for AutoMix. */
    suspend fun audioAnalysis(songId: String): JSONObject? {
        val root = get("songs/$songId?extend=audioAnalysis") ?: return null
        val attrs = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: return null
        return attrs.optJSONObject("audioAnalysis")
    }

    /** A catalog playlist with its songs (recommendation/editorial playlists). */
    suspend fun playlistTracks(playlistId: String): JSONObject? =
        get("playlists/$playlistId?include=tracks&extend=extendedAssetUrls")

    /** Paginates a catalog playlist's tracks beyond the initial inline relationship page. */
    suspend fun playlistTracksPage(playlistId: String, offset: Int): JSONObject? =
        get("playlists/$playlistId/tracks?limit=100&offset=$offset&extend=extendedAssetUrls")

    /** Fetches the next batch of tracks for an Apple Music radio station (`POST /v1/me/stations/next-tracks/{id}`). */
    suspend fun stationNextTracks(stationId: String): JSONObject? = withContext(Dispatchers.IO) {
        if (mediaToken.isBlank()) return@withContext null
        val url = ME_BASE + "stations/next-tracks/$stationId?extend=extendedAssetUrls"
        val sentIn = gate.acquire()
        val reply = runCatching {
            Http.send(url, "POST", "{}", headers("application/json"))
        }.onFailure { CadenceLog.i("amp", "FAILED POST stations/next-tracks/$stationId: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (reply == null || reply.code !in 200..299) {
            if (reply != null && reply.code != 404) {
                CadenceLog.e("amp", "POST stations/next-tracks/$stationId -> ${reply.code} ${reply.body.take(160)}")
            }
            return@withContext null
        }
        gate.succeeded(sentIn)
        CadenceLog.i("amp", "POST stations/next-tracks/$stationId -> ${reply.code} bytes=${reply.body.length}")
        runCatching { JSONObject(reply.body) }.getOrNull()
    }

    /**
     * Everything the artist page shows, in one request: biography, origin, and the views Apple
     * curates (top songs, latest release, albums, singles, similar artists, featured playlists).
     */
    suspend fun artist(artistId: String): JSONObject? =
        get(
            "artists/$artistId?views=top-songs,latest-release,full-albums,singles,similar-artists,featured-playlists" +
                "&extend=artistBio,bornOrFormed,origin,isGroup,extendedAssetUrls" +
                "&limit%5Bartists%3Atop-songs%5D=10&include%5Bsongs%5D=albums",
        )

    /** The account's own playlists; Apple's "Favourite Songs" is one of them (`canEdit` false). */
    /** Apple's rows for an album page: other versions, more by the artist, related albums. */
    suspend fun albumViews(albumId: String): JSONObject? =
        get("albums/$albumId?views=other-versions,more-by-artist,you-might-also-like")

    suspend fun libraryPlaylists(): JSONObject? = me("library/playlists?limit=100")

    /** One page of the account library ("songs", "albums" or "artists") with catalogue counterparts. */
    suspend fun libraryPage(kind: String, offset: Int): JSONObject? =
        me("library/$kind?limit=100&offset=$offset&include=catalog" + if (kind == "songs") "&extend=extendedAssetUrls" else "")

    /** Catalogue songs by id, with their albums and artists (at most 100 ids per call). */
    suspend fun songsByIds(ids: List<String>): JSONObject? =
        get("songs?ids=${ids.joinToString(",")}&include=albums,artists&extend=extendedAssetUrls")

    /** A library playlist's songs with their catalogue counterparts, which is what can be played. */
    suspend fun libraryPlaylistTracks(libraryPlaylistId: String, offset: Int = 0): JSONObject? =
        me("library/playlists/$libraryPlaylistId/tracks?limit=100&offset=$offset&include=catalog&extend=extendedAssetUrls")

    /** Browse seed for Home: Apple's album charts. */
    suspend fun charts(): JSONObject? =
        get("charts?types=albums&limit=12&extend=extendedAssetUrls")

    suspend fun artistAlbums(artistId: String): JSONObject? =
        get("artists/$artistId/albums?limit=25&extend=extendedAssetUrls")
}

/** Apple artwork is served from a `{w}x{h}` template; keep it and substitute per use. */
object Artwork {
    fun template(attrs: JSONObject): String? =
        attrs.optJSONObject("artwork")?.optString("url")?.takeIf { it.isNotBlank() }

    fun at(template: String?, size: String = "1400x1400"): String? =
        template?.replace("{w}x{h}", size)
}

object Mapping {

    private fun isAvailable(attrs: JSONObject): Boolean = attrs.optJSONObject("playParams") != null

    fun artistFromName(name: String): Artist =
        Artist(id = "ar:$name", name = name.ifBlank { "Unknown Artist" }, bio = "", color = "#888888")

    fun artist(json: JSONObject): Artist? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val name = attrs.optString("name").ifBlank { return null }
        // The avatar tint comes from the artist's own artwork when Apple provides one.
        val tint = attrs.optJSONObject("artwork")?.optString("bgColor")?.takeIf { it.length == 6 }
        return artistFromName(name).copy(
            color = if (tint != null) "#$tint" else "#888888",
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            // Lets two artists with one name be told apart in a list ("Artist · Hip-Hop/Rap").
            genres = attrs.optJSONArray("genreNames")?.let { g -> (0 until g.length()).map(g::optString).filter { it.isNotBlank() } }.orEmpty(),
        )
    }

    fun album(json: JSONObject): Album? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val id = json.optString("id").ifBlank { return null }
        val released = attrs.optString("releaseDate").takeIf { it.isNotBlank() }
        return Album(
            id = id,
            title = attrs.optString("name"),
            artistId = "ar:" + attrs.optString("artistName"),
            year = released?.take(4)?.toIntOrNull() ?: 0,
            genre = attrs.optJSONArray("genreNames")?.optString(0)?.takeIf { it.isNotBlank() } ?: "Music",
            colors = artworkColors(attrs),
            // 300x300 keeps the first paint fast; the template is kept for larger sizes.
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            motion = null,
            released = released,
            copyright = attrs.optString("copyright").takeIf { it.isNotBlank() },
            recordLabel = attrs.optString("recordLabel").takeIf { it.isNotBlank() },
            trackCount = attrs.optInt("trackCount", 0),
            isSingle = attrs.optBoolean("isSingle", false),
            available = isAvailable(attrs),
        )
    }

    /** A standalone album synthesised from a playlist song's own attributes (each song
     * may be from a different album, and the playlist response doesn't repeat full albums). */
    fun albumFromSong(song: JSONObject, albumId: String, artistId: String): Album {
        val attrs = song.optJSONObject("attributes") ?: JSONObject()
        val released = attrs.optString("releaseDate").takeIf { it.isNotBlank() }
        return Album(
            id = albumId,
            title = attrs.optString("albumName").ifBlank { "Album" },
            artistId = artistId,
            year = released?.take(4)?.toIntOrNull() ?: 0,
            genre = attrs.optJSONArray("genreNames")?.optString(0)?.takeIf { it.isNotBlank() } ?: "Music",
            colors = artworkColors(attrs),
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            released = released,
            available = isAvailable(attrs),
        )
    }

    /**
     * Accent colours from Apple's per-cover palette. Apple extracts bgColor + textColor1..4 from
     * every cover, so [ArtworkPalette] reorders them most-vibrant-first for a lively, cover-driven
     * theme seed - no image decoding needed. Falls back to the app default when a cover has none.
     */
    private fun artworkColors(attrs: JSONObject): List<String> {
        val art = attrs.optJSONObject("artwork")
        return ArtworkPalette.themeColors(
            listOf(
                art?.optString("bgColor"),
                art?.optString("textColor1"),
                art?.optString("textColor2"),
                art?.optString("textColor3"),
                art?.optString("textColor4"),
            )
        )
    }

    /** A real curated/editorial playlist (recommendation shelves, suggested rails). */
    fun playlist(json: JSONObject): Playlist? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val id = json.optString("id").ifBlank { return null }
        val curator = attrs.optString("curatorName")
        val desc = attrs.optJSONObject("description")?.optString("short")?.takeIf { it.isNotBlank() }
            ?: curator.takeIf { it.isNotBlank() } ?: "Apple Music"
        return Playlist(
            id = "pl:$id",
            title = attrs.optString("name"),
            description = desc,
            curator = curator.ifBlank { "Apple Music" },
            // The tracklist is a separate fetch, loaded when the playlist is opened.
            trackIds = emptyList(),
            colors = artworkColors(attrs),
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            userCreated = false,
            available = isAvailable(attrs),
        )
    }

    /** One of the account's own playlists (`library-playlists`); its songs load when it is opened. */
    /**
     * The catalogue counterpart of each row of an account-library page, in order. Rows without
     * one (uploads, unmatched songs) are left out.
     */
    fun catalogCounterparts(page: JSONObject): List<JSONObject> {
        val data = page.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optJSONObject("relationships")?.optJSONObject("catalog")
                ?.optJSONArray("data")?.optJSONObject(0)?.takeIf { it.optJSONObject("attributes") != null }
        }
    }

    /**
     * The id of the account's "Favourite Songs" playlist in a library-playlists response, or null.
     * It is the system playlist the user can neither edit nor delete. A catalogue playlist added
     * to the library is also not editable but can be removed, so `canEdit` alone is not enough.
     * When the marker matches more than one playlist nothing is guessed.
     */
    fun favouritesPlaylistId(data: org.json.JSONArray): String? {
        val candidates = (0 until data.length()).mapNotNull(data::optJSONObject).filter { json ->
            val attrs = json.optJSONObject("attributes") ?: return@filter false
            attrs.has("canEdit") && attrs.has("canDelete") && !attrs.optBoolean("canEdit", true) && !attrs.optBoolean("canDelete", true)
        }
        return candidates.singleOrNull()?.let(::libraryPlaylist)?.id
    }

    fun libraryPlaylist(json: JSONObject): Playlist? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val id = json.optString("id").ifBlank { return null }
        return Playlist(
            id = "lp:$id",
            title = attrs.optString("name").ifBlank { "Playlist" },
            description = attrs.optJSONObject("description")?.optString("standard").orEmpty(),
            curator = "Your library",
            trackIds = emptyList(),
            colors = artworkColors(attrs),
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            userCreated = false,
            available = isAvailable(attrs),
        )
    }

    /** Apple's text fields may carry light markup (`<i>`, `<b>`, line breaks); the UI shows plain text. */
    fun plainText(text: String): String =
        text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").trim()

    /** A real Apple Music radio station, surfaced as a card (dynamic, no fixed tracklist). */
    fun station(json: JSONObject): Playlist? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val id = json.optString("id").ifBlank { return null }
        val note = attrs.optJSONObject("editorialNotes")?.optString("short")?.takeIf { it.isNotBlank() }
        return Playlist(
            id = "st:$id",
            title = attrs.optString("name"),
            description = note ?: if (attrs.optBoolean("isLive")) "Live radio" else "Apple Music Radio",
            curator = "Apple Music",
            trackIds = emptyList(),
            colors = artworkColors(attrs),
            cover = Artwork.at(Artwork.template(attrs), "300x300"),
            userCreated = false,
            available = isAvailable(attrs),
            stationKind = attrs.optString("kind").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Track metadata. `enhancedHls` present means the lossless (ALAC) variant is
     * available; without it the catalogue offers only the AAC stream. The catalogue
     * does not say which bit depth and sample rate the lossless rendition has (16/44.1,
     * 24/48 and 24/192 all exist), so those stay unknown here; playback reports the
     * real ones from the stream.
     */
    fun track(json: JSONObject, albumId: String): Track? {
        val attrs = json.optJSONObject("attributes") ?: return null
        val id = json.optString("id").ifBlank { return null }
        val hls = attrs.optJSONObject("extendedAssetUrls")?.optString("enhancedHls")?.takeIf { it.isNotBlank() }
        val format = if (hls != null)
            AudioFormatInfo(codec = "ALAC", channels = 2)
        else
            AudioFormatInfo(codec = "AAC", bitrate = 256_000, channels = 2)
        return Track(
            id = id,
            title = attrs.optString("name"),
            artistId = "ar:" + attrs.optString("artistName"),
            albumId = albumId.ifBlank { attrs.optString("collectionId") },
            number = attrs.optInt("trackNumber", 0),
            durationMs = attrs.optLong("durationInMillis", 0L),
            audio = hls.orEmpty(),
            lyrics = null,
            format = format,
            credits = null,
            available = isAvailable(attrs),
        )
    }
}
