package app.cadence.backend.engine.media

import app.cadence.backend.engine.stream.HlsMediaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches Apple motion-cover artwork (HLS fMP4 or progressive MP4) into [dest]. */
object MotionArtFetcher {
    suspend fun fetchToFile(motionUrl: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (motionUrl.contains(".m3u8", ignoreCase = true)) {
            val master = fetchBytes(motionUrl).toString(Charsets.UTF_8)
            val bestUrl = HlsVariants.parse(master, motionUrl).maxByOrNull { it.bandwidth }?.url ?: motionUrl
            val mediaText = fetchBytes(bestUrl).toString(Charsets.UTF_8)
            val media = HlsMediaParser.parse(mediaText, bestUrl)
            dest.outputStream().use { out ->
                out.write(fetchBytes(media.initUrl, media.initOffset, media.initLength))
                for (seg in media.segments) {
                    out.write(fetchBytes(seg.url, seg.offset, seg.length))
                }
            }
            true
        } else {
            dest.writeBytes(fetchBytes(motionUrl))
            false
        }
    }

    private fun fetchBytes(url: String, offset: Long? = null, length: Long? = null): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
            if (offset != null && length != null && length > 0) {
                setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
