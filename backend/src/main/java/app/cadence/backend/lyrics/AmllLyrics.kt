package app.cadence.backend.lyrics

import app.cadence.backend.engine.CadenceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional fallback to the community AMLL TTML DB (`https://api.amll.dev`) when Apple Music
 * returns no synced lyrics for a track. Opt-in via Settings -> Lyrics.
 */
object AmllLyrics {
    private const val ENDPOINT = "https://api.amll.dev/v1/lyrics/get?appleMusicId="

    @Volatile
    var enabled: Boolean = false

    internal fun parseResponse(jsonText: String): String? = runCatching {
        val data = JSONObject(jsonText).optJSONObject("data") ?: return@runCatching null
        data.optString("lyrics").takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun fetchTtml(trackId: String): String? = withContext(Dispatchers.IO) {
        if (!enabled || trackId.isBlank()) return@withContext null
        runCatching {
            val url = ENDPOINT + URLEncoder.encode(trackId, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode == 404) return@withContext null
                if (conn.responseCode !in 200..299) return@withContext null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseResponse(body)?.also {
                    CadenceLog.i("lyrics", "track=$trackId source=amll ttml=${it.length}b")
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            CadenceLog.e("lyrics", "amll fetch failed track=$trackId", it)
        }.getOrNull()
    }
}
