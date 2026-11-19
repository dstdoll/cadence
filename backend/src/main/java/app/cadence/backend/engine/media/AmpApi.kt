package app.cadence.backend.engine.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /** Raw `{w}x{h}` template used for embedding at a chosen size. */
    val artworkTemplate: String? = null,
    val trackCount: Int = 0,
    val releaseDate: String? = null,
    val genre: String? = null,
    val copyright: String? = null,
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    val hlsUrl: String?,
    val artworkTemplate: String? = null,
    val genre: String? = null,
    val isrc: String? = null,
    val isCompilation: Boolean = false,
    /** Apple's own flags; false means the lyrics endpoint will 404. */
    val hasLyrics: Boolean = false,
    val hasTimeSyncedLyrics: Boolean = false,
)

object AmpApi {
    suspend fun searchAlbums(session: Session, query: String): List<Album> {
        if (session.devToken.isBlank()) throw IllegalStateException("Missing dev token")
        val term = URLEncoder.encode(query, "UTF-8")
        val root = request(
            session,
            "search?term=$term&types=albums&limit=12&extend=extendedAssetUrls",
        )
        val data = root.getJSONObject("results").getJSONObject("albums").getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                add(
                    Album(
                        id = item.optString("id"),
                        title = attrs.optString("name"),
                        artist = attrs.optString("artistName"),
                        artworkUrl = artwork(attrs),
                        artworkTemplate = attrs.optJSONObject("artwork")?.optString("url"),
                        trackCount = attrs.optInt("trackCount", 0),
                        releaseDate = attrs.optString("releaseDate").takeIf { it.isNotBlank() },
                        genre = attrs.optJSONArray("genreNames")?.optString(0)?.takeIf { it.isNotBlank() },
                        copyright = attrs.optString("copyright").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }

    suspend fun albumTracks(session: Session, albumId: String): List<Track> {
        if (session.devToken.isBlank()) throw IllegalStateException("Missing dev token")
        val root = request(
            session,
            "albums/$albumId/tracks?limit=100&extend=extendedAssetUrls",
        )
        val data = root.getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val extended = attrs.optJSONObject("extendedAssetUrls")
                add(
                    Track(
                        id = item.optString("id"),
                        title = attrs.optString("name"),
                        artist = attrs.optString("artistName"),
                        trackNumber = attrs.optInt("trackNumber", i + 1),
                        discNumber = attrs.optInt("discNumber", 1),
                        durationMs = attrs.optLong("durationInMillis", 0L),
                        hlsUrl = extended?.optString("enhancedHls")?.takeIf { it.isNotBlank() },
                        genre = attrs.optJSONArray("genreNames")?.optString(0)?.takeIf { it.isNotBlank() },
                        isrc = attrs.optString("isrc").takeIf { it.isNotBlank() },
                        isCompilation = attrs.optBoolean("isCompilation", false),
                        // Permissive default: only skip when Apple explicitly
                        // says there are no lyrics. A missing field must not
                        // silently disable embedding.
                        hasLyrics = if (attrs.has("hasLyrics")) attrs.optBoolean("hasLyrics") else true,
                        hasTimeSyncedLyrics = attrs.optBoolean("hasTimeSyncedLyrics", false),
                    )
                )
            }
        }
    }

    internal suspend fun request(session: Session, path: String): JSONObject = withContext(Dispatchers.IO) {
        requestSync(session, path)
    }

    /** Blocking variant for callers already on a background thread. */
    internal fun requestBlocking(session: Session, path: String): JSONObject? =
        runCatching { requestSync(session, path) }.getOrNull()

    /**
     * Raw GET returning (status, body) so callers can log why a request failed.
     *
     * Lyrics (and other media-API calls that are subscriber scoped) need the
     * media user token under the header name `media-user-token` - the wrapper
     * sends exactly that, not `Music-User-Token`. Both are sent for safety.
     */
    internal fun getRaw(session: Session, path: String): Pair<Int, String> {
        val url = URL("https://amp-api.music.apple.com/v1/catalog/${session.storefrontCode}/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${session.devToken}")
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
            if (session.mediaUserToken.isNotBlank()) {
                setRequestProperty("media-user-token", session.mediaUserToken)
                setRequestProperty("Music-User-Token", session.mediaUserToken)
            }
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun requestSync(session: Session, path: String): JSONObject {
        val url = URL("https://amp-api.music.apple.com/v1/catalog/${session.storefrontCode}/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${session.devToken}")
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
            // Token split: the dev token in
            // `Authorization` authenticates the catalogue anonymously, while
            // subscriber-scoped data needs the media user token, which Apple
            // reads from `media-user-token`. `Music-User-Token` is sent too for
            // endpoints that accept only that spelling.
            if (session.mediaUserToken.isNotBlank()) {
                setRequestProperty("media-user-token", session.mediaUserToken)
                setRequestProperty("Music-User-Token", session.mediaUserToken)
            }
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${body.take(180)}")
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun artwork(attrs: JSONObject): String? =
        attrs.optJSONObject("artwork")
            ?.optString("url")
            ?.replace("{w}x{h}", "300x300")
}
