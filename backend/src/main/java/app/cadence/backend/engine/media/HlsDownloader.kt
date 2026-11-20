package app.cadence.backend.engine.media

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

data class DownloadState(
    val running: Boolean = false,
    val done: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    val keyUri: String? = null,
    /** "downloading", "decrypting", "done" or "error". The UI draws one bar per phase. */
    val phase: String = "downloading",
)

private data class Segment(
    val url: String,
    val offset: Long?,
    val length: Long?,
)

class HlsDownloader(private val context: Context) {
    suspend fun download(
        track: Track,
        album: Album,
        settings: AppSettings,
        sourceUrl: String,
        onState: (DownloadState) -> Unit,
        /** The text of [sourceUrl] when the caller already fetched it, which saves a round trip. */
        knownPlaylist: String = "",
    ): String = withContext(Dispatchers.IO) {
        val hls = sourceUrl
        AppLog.log("HLS", "start track=${track.id} source=$hls (cache staging)")
        // The encrypted package never touches the user's music folder: it is
        // staged under the app cache, decrypted, and then deleted.
        val stagingDir = File(context.cacheDir, "hls").apply { mkdirs() }
        cleanupOldPackages(stagingDir)
        val staging = File(stagingDir, "pkg_${track.id}_${System.nanoTime()}.enc")

        onState(DownloadState(running = true, phase = "downloading", message = "Reading playlist"))
        val masterUrl = URL(hls)
        val master = knownPlaylist.ifBlank { readText(masterUrl) }
        // Apple's enhancedHls master lists the lossy variants first and the ALAC
        // one second, so blindly taking the first #EXT-X-STREAM-INF downloads AAC.
        // Prefer CODECS="alac" exactly as the ported engine does.
        val mediaUrl = if (master.contains("#EXT-X-STREAM-INF")) {
            val lines = master.lineSequence().toList()
            val alac = lines.indexOfFirst {
                it.startsWith("#EXT-X-STREAM-INF") && it.contains("CODECS=\"alac\"", ignoreCase = true)
            }
            val variant = lines.drop((if (alac >= 0) alac else lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }) + 1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("No variant in master playlist")
            URL(masterUrl, variant.trim())
        } else {
            masterUrl
        }
        val media = if (mediaUrl == masterUrl) master else readText(mediaUrl)
        val lines = media.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.startsWith("#EXTINF") }) throw IOException("Not a media playlist")

        // The init segment is usually a byte range of the same file
        // (#EXT-X-MAP:URI, BYTERANGE="1023@0"). Ignoring BYTERANGE made the
        // downloader fetch the whole media file as the init, which wrote the
        // package twice and doubled the decoded duration.
        val mapLine = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
        val mapUri = mapLine?.let(::attributeUri)
        val mapRange = mapLine?.let(::mapByteRange)
        val mapOffset = mapRange?.first
        val mapLength = mapRange?.second
        val segments = mutableListOf<Segment>()
        val segmentKeys = mutableListOf<KeyPlan.SegmentKey>()
        var currentKey: KeyPlan.SegmentKey? = null
        var pendingRange: Pair<Long, Long>? = null
        for (line in lines) {
            // EXT-X-KEY applies to every segment that follows it, and Apple's ALAC
            // playlists switch from the prefetch key to the content key after the
            // first segment. Remember which key each segment was written with.
            if (line.startsWith("#EXT-X-KEY:")) {
                val format = Regex("KEYFORMAT=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                val uri = attributeUri(line)
                if (uri != null && uri.startsWith("skd://") &&
                    (format == null || format == "com.apple.streamingkeydelivery")
                ) {
                    currentKey = KeyPlan.SegmentKey(uri, KeyPlan.adamIdFor(track.id, uri))
                    AppLog.log("HLS", "key=$uri adamId=${currentKey.adamId}")
                }
                continue
            }
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                val spec = line.substringAfter(':').trim()
                val parts = spec.split("@", limit = 2)
                val length = parts[0].toLongOrNull() ?: continue
                val offset = parts.getOrNull(1)?.toLongOrNull()
                pendingRange = length to (offset ?: -1L)
                continue
            }
            if (line.startsWith("#")) continue
            val url = URL(mediaUrl, line).toString()
            val range = pendingRange
            val key = currentKey ?: throw IOException("Segment $url has no FairPlay key")
            segments.add(
                Segment(
                    url = url,
                    offset = range?.second?.takeIf { it >= 0 },
                    length = range?.first,
                )
            )
            segmentKeys.add(key)
            pendingRange = null
        }
        if (segments.isEmpty()) throw IOException("No segments in playlist")
        AppLog.log("HLS", "playlist media=$mediaUrl segments=${segments.size} map=$mapUri")
        try {
            staging.outputStream().buffered().use { stream ->
                if (mapUri != null) {
                    stream.write(readBytes(URL(mediaUrl, mapUri), mapOffset, mapLength))
                }
                // A range without an offset continues where the previous one in that file ended.
                val nextOffsetByUrl = mutableMapOf<String, Long>()
                val starts = segments.map { segment ->
                    val start = segment.offset ?: nextOffsetByUrl[segment.url]
                        ?: if (segment.length != null) 0L else null
                    if (segment.length != null) nextOffsetByUrl[segment.url] = (start ?: 0L) + segment.length
                    start
                }
                // Several segments travel at once and are written in playlist order. Fetching them
                // one by one left most of the connection idle (measured: 11 segments, 6.6 s).
                //
                // A sliding window, not fixed groups. With `chunked` + `awaitAll` the whole group
                // had to finish before the next one started, so one slow segment left the other
                // connections idle waiting for it -- the tail of every group was dead time. Here a
                // new segment starts the moment one is written, so PARALLEL_SEGMENTS of them are
                // in flight continuously.
                //
                // The window also bounds memory: at most PARALLEL_SEGMENTS segments are held at
                // once, because nothing is launched more than that far ahead of the write cursor.
                var done = 0
                var bytesTotal = 0L
                coroutineScope {
                    val inFlight = ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
                    var next = 0
                    while (next < segments.size || inFlight.isNotEmpty()) {
                        while (next < segments.size && inFlight.size < PARALLEL_SEGMENTS) {
                            val i = next++
                            inFlight.addLast(
                                async(Dispatchers.IO) { readBytes(URL(segments[i].url), starts[i], segments[i].length) }
                            )
                        }
                        val bytes = inFlight.removeFirst().await()
                        stream.write(bytes)
                        done++
                        bytesTotal += bytes.size
                        onState(
                            DownloadState(
                                running = true,
                                phase = "downloading",
                                progress = done.toFloat() / segments.size,
                                message = "Segment $done/${segments.size}",
                            )
                        )
                    }
                }
                AppLog.log("HLS", "segments $done/${segments.size} bytes=$bytesTotal parallel=$PARALLEL_SEGMENTS")
            }
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }
        if (staging.length() == 0L) {
            staging.delete()
            throw IOException("Empty HLS package")
        }
        KeyPlan.save(context, track.id, KeyPlan(track.id, segmentKeys))
        // This only marks a complete encrypted package. The job turns "done" only
        // after DrmService publishes the decrypted master.
        onState(DownloadState(done = false, phase = "downloading", progress = 1f, message = "Encrypted package ready"))
        AppLog.log("HLS", "done cache=${staging.absolutePath} size=${staging.length()}")
        Uri.fromFile(staging).toString()
    }

    private companion object {
        /** Segments in flight together. Four fills the link without holding much in memory. */
        /**
         * Segments in flight at once.
         *
         * Six rather than four: with the sliding window above the connections stay busy, so a
         * couple more of them fill the pipe on a fast link without asking the CDN for so many at
         * once that it starts throttling. Memory stays bounded by this number of segments.
         */
        const val PARALLEL_SEGMENTS = 6
    }

    /** Drops stale packages from a previous crash (the normal path deletes them). */
    private fun cleanupOldPackages(dir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        runCatching { dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() } }
    }

    private fun readText(url: URL): String = readBytes(url, null, null).toString(Charsets.UTF_8)

    private fun readBytes(url: URL, offset: Long?, length: Long?): ByteArray {
        var last: IOException? = null
        for (attempt in 1..2) {
            try {
                return Http.bytes(url.toString(), offset, length)
            } catch (e: IOException) {
                last = e
                AppLog.error("HTTP", "${url.host} attempt=$attempt range=$offset failed", e)
                if (attempt < 2) Thread.sleep(300)
            }
        }
        throw IOException("HTTP ${url.host} failed: ${last?.message}", last)
    }

    private fun attributeUri(line: String): String? {
        val match = Regex("URI=\"([^\"]+)\"").find(line) ?: return null
        return match.groupValues[1]
    }
}

/**
 * `BYTERANGE="<length>[@<offset>]"` of an `#EXT-X-MAP` line as (offset, length). A missing offset
 * means the start of the resource; without that default the whole package was fetched as the init.
 */
internal fun mapByteRange(line: String): Pair<Long, Long>? {
    val m = Regex("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"").find(line) ?: return null
    return (m.groupValues[2].toLongOrNull() ?: 0L) to m.groupValues[1].toLong()
}
