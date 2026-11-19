package app.cadence.backend.engine.media

import java.io.IOException
import java.net.URL

/** One rendition an enhancedHls master playlist offers, with its AUDIO group's attributes. */
internal data class HlsVariant(
    val url: String,
    val codecs: String,
    val bandwidth: Long,
    val groupId: String?,
    val group: Map<String, String>,
) {
    private val channels: String get() = group["CHANNELS"].orEmpty()

    /** AAC-LC; the HE-AAC renditions (mp4a.40.5) are 64 kbps and never chosen. */
    val aacLc: Boolean get() = codecs.equals("mp4a.40.2", ignoreCase = true)

    /** Apple's headphone render of a Dolby Atmos mix (`CHANNELS="2/-/BINAURAL"`), plain AAC. */
    val binaural: Boolean get() = channels.contains("BINAURAL", ignoreCase = true)

    /** The Dolby Atmos mix folded down to stereo (`CHANNELS="2/-/DOWNMIX"`), plain AAC. */
    val downmix: Boolean get() = channels.contains("DOWNMIX", ignoreCase = true)

    /** Dolby Atmos itself: E-AC-3 with joint object coding (`CODECS="ec-3"`, `CHANNELS="16/JOC"`). */
    val atmos: Boolean get() = codecs.equals("ec-3", ignoreCase = true)

    val alac: Boolean get() = codecs.equals("alac", ignoreCase = true)
}

/** The rendition [HlsVariants.choose] picked: [codec] is `alac`, `aac` or `ec-3`; [kind] adds `atmos` and `spatial`. */
internal data class VariantChoice(val variant: HlsVariant, val codec: String, val kind: String)

/**
 * Reads the renditions of Apple's enhancedHls master playlists and picks one.
 *
 * A track mixed in Dolby Atmos carries, next to the stereo renditions, the Atmos mix itself
 * (E-AC-3 JOC at about 768 and 448 kbps), Apple's binaural render of it for headphones and a stereo
 * downmix of it (both AAC). The binaural and downmix renditions are a different mix from the stereo
 * master, so they are only used when asked for, never as "the AAC version" of a song.
 */
internal object HlsVariants {
    /** Values of the Dolby Atmos preference. */
    const val ATMOS_OFF = "off"
    const val ATMOS_SPATIAL = "spatial"
    const val ATMOS_DOLBY = "atmos"

    fun parse(master: String, masterUrl: String): List<HlsVariant> {
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // AUDIO group declarations carry SAMPLE-RATE / BIT-DEPTH / CHANNELS.
        val groups = HashMap<String, Map<String, String>>()
        for (line in lines) {
            if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=AUDIO", ignoreCase = true)) continue
            val attrs = attributes(line)
            groups[attrs["GROUP-ID"] ?: continue] = attrs
        }
        val out = ArrayList<HlsVariant>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val attrs = attributes(line)
            val uri = lines.subList(index + 1, lines.size).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
            val groupId = attrs["AUDIO"]
            out += HlsVariant(
                url = URL(URL(masterUrl), uri).toString(),
                codecs = attrs["CODECS"].orEmpty(),
                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                groupId = groupId,
                group = groupId?.let(groups::get).orEmpty(),
            )
        }
        return out
    }

    /**
     * Picks the rendition for [codec] (`alac` or `aac`) and the Dolby Atmos preference [atmos].
     * With [ATMOS_DOLBY] or [ATMOS_SPATIAL] a track that has the Atmos mix plays it (as E-AC-3, or
     * as Apple's binaural AAC); any other track keeps [codec]. `aac` takes the plain stereo AAC-LC
     * rendition, and a track without one falls back to lossless. `alac` honours the preferred bit
     * depth and sample-rate ceiling when the master offers several.
     */
    fun choose(
        variants: List<HlsVariant>,
        codec: String,
        atmos: String,
        preferredBitDepth: Int,
        maxSampleRate: Int,
    ): VariantChoice {
        fun best(filter: (HlsVariant) -> Boolean) = variants.filter(filter).maxByOrNull { it.bandwidth }
        when (atmos) {
            ATMOS_DOLBY -> best { it.atmos }?.let { return VariantChoice(it, "ec-3", "atmos") }
            ATMOS_SPATIAL -> best { it.aacLc && it.binaural }?.let { return VariantChoice(it, "aac", "spatial") }
        }
        if (codec == "aac") {
            (best { it.aacLc && !it.binaural && !it.downmix } ?: best { it.aacLc && !it.binaural })
                ?.let { return VariantChoice(it, "aac", "aac") }
        }
        val alac = variants.filter { it.alac }
        if (alac.isEmpty()) throw IOException("No ALAC variant in playlist")
        return VariantChoice(alac.maxByOrNull { score(it, preferredBitDepth, maxSampleRate) } ?: alac.first(), "alac", "alac")
    }

    private fun score(v: HlsVariant, preferredBitDepth: Int, maxSampleRate: Int): Int {
        val depth = v.group["BIT-DEPTH"]?.toIntOrNull() ?: 0
        val rate = v.group["SAMPLE-RATE"]?.toIntOrNull() ?: 0
        var s = 0
        // A matching bit depth wins; otherwise the deeper variant is better.
        s += if (depth == preferredBitDepth) 10_000 else depth * 10
        s += if (maxSampleRate > 0) {
            if (rate in 1..maxSampleRate) 1_000 + rate / 1000 else -(rate / 1000)
        } else {
            rate / 1000
        }
        return s
    }

    /** Parses `KEY="value",KEY=value,...` attribute lists on HLS tags. */
    fun attributes(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"([^\"]*)\"|[^,]*)").findAll(line).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: m.groupValues[2]
            out[key] = value
        }
        return out
    }
}
