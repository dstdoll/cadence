package app.cadence.backend.engine.media

import java.io.IOException

/** Kinds of rendition a master offers, best first: lossless > AAC-LC > HE-AAC. Dolby Atmos is a separate axis. */
enum class QualityFamily { LOSSLESS, AAC_LC, HE_AAC, ATMOS }

/**
 * A quality a person can ask for, as a short stable string: `alac:24:96000` (lossless, bit depth at most 24,
 * sample rate at most 96 kHz; 0 = no rate limit), `aac:256`, `aac:128`, `aac:64` (HE-AAC).
 * "Highest available" is [BEST] (`alac:24:0`): Apple publishes nothing above 24-bit.
 */
data class RenditionSpec(val family: QualityFamily, val bitDepth: Int = 0, val sampleRate: Int = 0, val kbps: Int = 0) {
    /** The string form used in settings and logs. */
    fun format(): String = when (family) {
        QualityFamily.LOSSLESS -> "alac:$bitDepth:$sampleRate"
        else -> "aac:$kbps"
    }

    /** The segment-cache key (the form playback already uses: `alac/24/0`, `aac/256`). */
    fun cacheKey(): String = when (family) {
        QualityFamily.LOSSLESS -> "alac/$bitDepth/$sampleRate"
        else -> "aac/$kbps"
    }

    companion object {
        val BEST = RenditionSpec(QualityFamily.LOSSLESS, 24, 0)

        /** Every rung the settings can offer, best first (the ladder; a track simply lacks some of them). */
        val LADDER: List<RenditionSpec> = listOf(
            BEST,
            RenditionSpec(QualityFamily.LOSSLESS, 24, 192_000),
            RenditionSpec(QualityFamily.LOSSLESS, 24, 96_000),
            RenditionSpec(QualityFamily.LOSSLESS, 24, 48_000),
            RenditionSpec(QualityFamily.LOSSLESS, 24, 44_100),
            RenditionSpec(QualityFamily.LOSSLESS, 16, 44_100),
            RenditionSpec(QualityFamily.AAC_LC, kbps = 256),
            RenditionSpec(QualityFamily.AAC_LC, kbps = 128),
            RenditionSpec(QualityFamily.HE_AAC, kbps = 64),
        )

        /** Parses [format]; null for anything else (a corrupt or future setting must not crash the app). */
        fun parse(text: String?): RenditionSpec? {
            val p = text?.trim()?.lowercase()?.split(':') ?: return null
            return try {
                when {
                    p.size == 3 && p[0] == "alac" -> RenditionSpec(QualityFamily.LOSSLESS, p[1].toInt(), p[2].toInt()).takeIf { it.bitDepth in 8..32 && it.sampleRate >= 0 }
                    p.size == 2 && p[0] == "aac" -> when (val k = p[1].toInt()) {
                        in 1..64 -> RenditionSpec(QualityFamily.HE_AAC, kbps = 64)
                        in 65..320 -> RenditionSpec(QualityFamily.AAC_LC, kbps = if (k > 192) 256 else 128)
                        else -> null
                    }
                    else -> null
                }
            } catch (_: NumberFormatException) { null }
        }
    }
}

/** What to download: the highest quality wanted ([ceiling]) and the Dolby Atmos preference (off | spatial | atmos). */
data class QualityPolicy(val ceiling: RenditionSpec = RenditionSpec.BEST, val atmos: String = HlsVariants.ATMOS_OFF) {
    /** The cache key; Atmos choices get their own prefix, exactly as before. */
    fun cacheKey(): String = if (atmos == HlsVariants.ATMOS_OFF) ceiling.cacheKey() else "$atmos+${ceiling.cacheKey()}"

    companion object {
        /** What the older settings (format "alac"/"aac", bit depth, sample-rate ceiling in Hz, Atmos) mean as a policy. */
        fun fromLegacy(codec: String, bitDepth: Int, maxSampleRate: Int, atmos: String): QualityPolicy = QualityPolicy(
            if (codec == "aac") RenditionSpec(QualityFamily.AAC_LC, kbps = 256) else RenditionSpec(QualityFamily.LOSSLESS, bitDepth, maxSampleRate),
            atmos,
        )
    }
}

/** One rendition of a track's master, as the quality picker sees it. */
internal data class Rendition(
    val variant: HlsVariant,
    val family: QualityFamily,
    val bitDepth: Int,
    val sampleRate: Int,
    /** Nominal bit rate for lossy renditions (256, 128, 64; Atmos 768, 448); 0 for lossless. */
    val kbps: Int,
) {
    val spec: RenditionSpec
        get() = if (family == QualityFamily.LOSSLESS) RenditionSpec(family, bitDepth, sampleRate) else RenditionSpec(family, kbps = kbps)

    /** Estimated size in bytes of a track lasting [durationMs] (the playlist's mean bit rate x duration); 0 if unknown. */
    fun estimatedBytes(durationMs: Long): Long {
        val bps = variant.averageBandwidth.takeIf { it > 0 } ?: variant.bandwidth
        return if (durationMs <= 0 || bps <= 0) 0 else bps * durationMs / 8000
    }
}

/** A picked rendition and, in words, why: `wanted X; this track offers Y -> Z`. */
internal data class QualityChoice(val variant: HlsVariant, val codec: String, val kind: String, val rendition: Rendition?, val reason: String)

internal object QualityPlanner {
    /** The plain stereo renditions of a master, best first. Binaural and downmix renders are a different mix and are not listed. */
    fun renditions(variants: List<HlsVariant>): List<Rendition> =
        variants.filter { it.stereoMix }.mapNotNull(::rendition).sortedWith(RANK).reversed()

    /** The Dolby Atmos renditions of a master (E-AC-3 JOC), best first. */
    fun atmosRenditions(variants: List<HlsVariant>): List<Rendition> =
        variants.filter { it.atmos }.map { Rendition(it, QualityFamily.ATMOS, 0, 0, nominalKbps(it)) }.sortedByDescending { it.kbps }

    /** Apple's binaural AAC render of an Atmos mix ("spatial"), best first. */
    fun spatialRenditions(variants: List<HlsVariant>): List<HlsVariant> = variants.filter { it.aacLc && it.binaural }.sortedByDescending { it.bandwidth }

    private fun rendition(v: HlsVariant): Rendition? = when {
        v.alac -> Rendition(v, QualityFamily.LOSSLESS, v.group["BIT-DEPTH"]?.toIntOrNull() ?: 0, v.group["SAMPLE-RATE"]?.toIntOrNull() ?: 0, 0)
        v.aacLc -> Rendition(v, QualityFamily.AAC_LC, 0, 0, nominalKbps(v))
        v.heAac -> Rendition(v, QualityFamily.HE_AAC, 0, 0, 64)
        else -> null
    }

    /** Lowest to highest quality. */
    private val RANK: Comparator<Rendition> = compareBy<Rendition>({ order(it.family) }, { it.sampleRate }, { it.bitDepth }, { it.kbps }, { it.variant.averageBandwidth })
    private fun order(f: QualityFamily) = when (f) { QualityFamily.HE_AAC -> 0; QualityFamily.AAC_LC -> 1; QualityFamily.LOSSLESS -> 2; QualityFamily.ATMOS -> 3 }

    /** 256 / 128 / 64 from the group id (`audio-stereo-256`) or, failing that, from the mean bit rate; Atmos is rounded to 64 kbps steps. */
    internal fun nominalKbps(v: HlsVariant): Int {
        val bps = v.averageBandwidth.takeIf { it > 0 } ?: v.bandwidth
        if (v.atmos) return ((bps / 1000.0 / 64).let { Math.round(it) } * 64).toInt()
        Regex("""stereo-(\d+)""").find(v.groupId.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when { bps >= 180_000 -> 256; bps >= 100_000 -> 128; else -> 64 }
    }

    /**
     * Picks a rendition for [policy]. The Atmos preference first (as before); then the best rendition at or below
     * the ceiling; if nothing is, the closest one above it. Fallback order: same family downward, same family
     * upward, then the next family (lossless -> AAC 256 -> 128 -> HE-AAC 64; an AAC ceiling on a track with no AAC
     * at all -> the smallest lossless). A lossless ceiling never drops to AAC while any lossless exists.
     * Throws [IOException] only when the master has no usable rendition at all.
     */
    fun choose(variants: List<HlsVariant>, policy: QualityPolicy): QualityChoice {
        when (policy.atmos) {
            HlsVariants.ATMOS_DOLBY -> atmosRenditions(variants).firstOrNull()?.let {
                return QualityChoice(it.variant, "ec-3", "atmos", it, "Dolby Atmos asked for and this track has it (${it.kbps} kbps)")
            }
            HlsVariants.ATMOS_SPATIAL -> spatialRenditions(variants).firstOrNull()?.let {
                return QualityChoice(it, "aac", "spatial", null, "spatial (binaural) asked for and this track has it")
            }
        }
        val all = renditions(variants)
        if (all.isEmpty()) throw IOException("No usable rendition in the playlist")
        val c = policy.ceiling
        val offered = all.joinToString(", ") { describe(it) }
        fun done(r: Rendition, how: String) = QualityChoice(r.variant, if (r.family == QualityFamily.LOSSLESS) "alac" else "aac", if (r.family == QualityFamily.LOSSLESS) "alac" else "aac", r, "wanted ${c.format()}; this track offers [$offered] -> ${describe(r)} ($how)")

        val lossless = all.filter { it.family == QualityFamily.LOSSLESS }
        val aac = all.filter { it.family == QualityFamily.AAC_LC || it.family == QualityFamily.HE_AAC }
        when (c.family) {
            QualityFamily.LOSSLESS, QualityFamily.ATMOS -> {
                val eligible = lossless.filter { it.bitDepth <= c.bitDepth && (c.sampleRate == 0 || it.sampleRate <= c.sampleRate) }
                eligible.maxWithOrNull(RANK)?.let { return done(it, "best within the limit") }
                lossless.minWithOrNull(RANK)?.let { return done(it, "nothing within the limit; closest lossless above it") }
                aac.maxWithOrNull(RANK)?.let { return done(it, "no lossless on this track; best AAC") }
            }
            QualityFamily.AAC_LC, QualityFamily.HE_AAC -> {
                val limit = if (c.family == QualityFamily.HE_AAC) 64 else c.kbps
                aac.filter { it.kbps <= limit }.maxWithOrNull(RANK)?.let { return done(it, "best within the limit") }
                aac.minWithOrNull(RANK)?.let { return done(it, "nothing within the limit; closest AAC above it") }
                lossless.minWithOrNull(RANK)?.let { return done(it, "no AAC on this track; smallest lossless") }
            }
        }
        throw IOException("No usable rendition in the playlist")
    }

    /** `lossless 24-bit/96 kHz`, `AAC 256 kbps`, `HE-AAC 64 kbps`. */
    fun describe(r: Rendition): String = when (r.family) {
        QualityFamily.LOSSLESS -> "lossless ${r.bitDepth}-bit/${r.sampleRate / 1000.0}".replace(".0", "") + " kHz"
        QualityFamily.AAC_LC -> "AAC ${r.kbps} kbps"
        QualityFamily.HE_AAC -> "HE-AAC ${r.kbps} kbps"
        QualityFamily.ATMOS -> "Dolby Atmos ${r.kbps} kbps"
    }

    /** What the older settings mean as a policy (so an upgrade changes nothing); see [QualityPolicy.fromLegacy]. */
    fun legacyPolicy(codec: String, bitDepth: Int, maxSampleRate: Int, atmos: String): QualityPolicy = QualityPolicy.fromLegacy(codec, bitDepth, maxSampleRate, atmos)
}
