package app.cadence.backend.engine.stream

import java.io.IOException
import java.net.URI

/** One media segment: a byte range of the track's media file, protected by [keyUri]. */
data class HlsSegment(
    val url: String,
    val offset: Long,
    val length: Long,
    val durationSec: Double,
    /** The FairPlay key in force for this segment (`skd://...`). */
    val keyUri: String,
)

/** A parsed HLS media playlist: where the init segment is and every segment after it. */
data class HlsMedia(
    val initUrl: String,
    /** Null when the playlist gives no byte range, i.e. the init is a whole file. */
    val initOffset: Long?,
    val initLength: Long?,
    val segments: List<HlsSegment>,
    /**
     * What Apple's master playlist declares for this rendition's content (0 = not declared). It
     * can be lower than the container's: CD masters arrive as 24-bit ALAC whose low 8 bits are
     * all zero, declared as 16-bit.
     */
    val declaredBitDepth: Int = 0,
    val declaredSampleRate: Int = 0,
) {
    val durationSec: Double get() = segments.sumOf { it.durationSec }
}

/**
 * Parses Apple's ALAC media playlists for streaming playback.
 *
 * Two details of these playlists matter here. Each key change lists the same key for three DRM
 * systems (FairPlay, PlayReady, Widevine) and only the FairPlay `skd://` URI is usable. And the
 * key changes mid-playlist: the first segment is under the shared "prefetch" key, the rest
 * under the track's own key, so the key in force is recorded per segment.
 */
object HlsMediaParser {
    private const val FAIRPLAY = "com.apple.streamingkeydelivery"
    private val uriAttr = Regex("URI=\"([^\"]+)\"")
    private val keyFormatAttr = Regex("KEYFORMAT=\"([^\"]+)\"")
    private val mapRange = Regex("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"")

    /** @param baseUrl the playlist's own URL, against which relative segment URIs resolve. */
    @Throws(IOException::class)
    fun parse(text: String, baseUrl: String): HlsMedia {
        val base = URI(baseUrl)
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.startsWith("#EXTINF") }) throw IOException("Not a media playlist")

        val map = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") } ?: throw IOException("Playlist has no init segment (EXT-X-MAP)")
        val mapUri = uriAttr.find(map)?.groupValues?.get(1) ?: throw IOException("EXT-X-MAP has no URI")
        val range = mapRange.find(map)
        val initLength = range?.groupValues?.get(1)?.toLongOrNull()
        val initOffset = if (initLength == null) null else range.groupValues[2].toLongOrNull() ?: 0L

        val segments = ArrayList<HlsSegment>()
        var key: String? = null
        var duration = 0.0
        var pending: Pair<Long, Long?>? = null
        // A byte range without an offset continues where the previous range of that file ended.
        val nextOffset = HashMap<String, Long>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val format = keyFormatAttr.find(line)?.groupValues?.get(1)
                    val uri = uriAttr.find(line)?.groupValues?.get(1)
                    if (uri != null && uri.startsWith("skd://") && (format == null || format == FAIRPLAY)) key = uri
                }
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: throw IOException("Bad EXTINF: $line")
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':').trim().split("@", limit = 2)
                    val length = spec[0].toLongOrNull() ?: throw IOException("Bad EXT-X-BYTERANGE: $line")
                    pending = length to spec.getOrNull(1)?.toLongOrNull()
                }
                line.startsWith("#") -> {}
                else -> {
                    val url = base.resolve(line).toString()
                    // The stream layout is computed before anything is downloaded, which needs
                    // every segment's size up front; Apple's playlists always carry it.
                    val (length, explicit) = pending ?: throw IOException("Segment without a byte range: streaming needs segment sizes")
                    val offset = explicit ?: nextOffset[url] ?: 0L
                    segments += HlsSegment(url, offset, length, duration, key ?: throw IOException("Segment has no FairPlay key"))
                    nextOffset[url] = offset + length
                    pending = null
                }
            }
        }
        if (segments.isEmpty()) throw IOException("No segments in playlist")
        return HlsMedia(base.resolve(mapUri).toString(), initOffset, initLength, segments)
    }
}
