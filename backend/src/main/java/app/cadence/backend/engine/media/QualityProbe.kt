package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import app.cadence.backend.engine.CadenceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One row of the quality picker. [kind] is `lossless`, `aac`, `atmos` or `spatial`; [spec] is the stereo quality
 * (null for Atmos/spatial). For several songs [tracksWith] counts the songs that have exactly this quality and
 * [fallbackTracks] those that would get the next best one instead. [bytes] is the estimated total size of what this
 * choice would download (the playlists' mean bit rate x duration; 0 when no duration is known).
 */
data class QualityOption(
    val kind: String,
    val spec: RenditionSpec?,
    val atmosKbps: Int,
    val tracksWith: Int,
    val tracks: Int,
    val fallbackTracks: Int,
    val bytes: Long,
    /** True for the "highest available" row of a several-song request. */
    val highestAvailable: Boolean = false,
    /** For the "highest available" row: the best lossless quality any song reaches and how many songs reach exactly it. */
    val topSpec: RenditionSpec? = null,
    val topTracks: Int = 0,
) {
    /** What to download when this row is picked: a stereo row turns Atmos OFF for this download (it is an explicit choice). */
    fun policy(): QualityPolicy = when (kind) {
        "atmos" -> QualityPolicy(RenditionSpec.BEST, HlsVariants.ATMOS_DOLBY)
        "spatial" -> QualityPolicy(RenditionSpec.BEST, HlsVariants.ATMOS_SPATIAL)
        else -> QualityPolicy(spec ?: RenditionSpec.BEST, HlsVariants.ATMOS_OFF)
    }
}

/** What the picker needs: the rows best-first, which one to preselect, and how many songs could not be checked. */
data class QualityProbeResult(
    val options: List<QualityOption>,
    val preselected: Int,
    val tracks: Int,
    /** Songs whose master playlist could not be read (offline, expired link); they use the default quality. */
    val unchecked: Int,
)

/**
 * Reads the master playlists of the songs about to be downloaded and turns them into the rows of the quality picker.
 * The masters are not kept: the download fetches a fresh one itself (links expire), so this only informs the choice.
 */
object QualityProbe {
    private const val CONCURRENCY = 4
    private const val MASTER_TIMEOUT_MS = 8_000L

    private class Probed(val item: DownloadItem, val variants: List<HlsVariant>?)

    suspend fun probe(items: List<DownloadItem>, defaults: QualityPolicy, fetch: suspend (String) -> String? = ::fetchMaster): QualityProbeResult {
        val gate = Semaphore(CONCURRENCY)
        val probed = coroutineScope {
            items.map { item ->
                async {
                    gate.withPermit {
                        val text = item.hlsUrl.takeIf { it.isNotBlank() }?.let { fetch(it) }
                        Probed(item, text?.let { runCatching { HlsVariants.parse(it, item.hlsUrl) }.getOrNull() }?.takeIf { v -> QualityPlanner.renditions(v).isNotEmpty() })
                    }
                }
            }.awaitAll()
        }
        probed.forEach { p -> p.variants?.let { CadenceLog.i("QUALITY", describeMaster(p.item.trackId, p.item.hlsUrl, it)); QualityIndex.record(p.item.trackId, it) } }
        val result = build(probed, defaults)
        CadenceLog.i("QUALITY", "probe n=${items.size} unchecked=${result.unchecked} options=[" + result.options.joinToString(", ") { describe(it) } + "] preselected=${result.preselected}")
        return result
    }

    /**
     * What Apple's master offers for one song, as one log line: every rendition by kind, including the ones the picker
     * does not list (binaural / downmix mixes), so "why is there no 96 kHz?" can be answered from a report.
     */
    internal fun describeMaster(trackId: String, url: String, variants: List<HlsVariant>): String {
        val file = url.substringBefore('?').substringAfterLast('/')
        val stereo = QualityPlanner.renditions(variants).joinToString(", ") { QualityPlanner.describe(it).removePrefix("lossless ") .let { d -> if (it.family == QualityFamily.LOSSLESS) "alac $d" else d } }
        val atmos = QualityPlanner.atmosRenditions(variants).joinToString("/") { "${it.kbps}" }.ifEmpty { "none" }
        val other = variants.count { !it.stereoMix && !it.atmos }
        return "master track=$trackId file=$file renditions=${variants.size} stereo=[$stereo] atmos=$atmos binaural/downmix=$other"
    }

    private fun describe(o: QualityOption) = (o.spec?.format() ?: o.kind) + "(${o.tracksWith}/${o.tracks})"

    private fun build(probed: List<Probed>, defaults: QualityPolicy): QualityProbeResult {
        val known = probed.filter { it.variants != null }
        val unchecked = probed.size - known.size
        if (known.isEmpty()) return QualityProbeResult(emptyList(), 0, probed.size, unchecked)

        fun bytesOf(p: Probed, policy: QualityPolicy): Pair<QualityChoice?, Long> {
            val c = runCatching { QualityPlanner.choose(p.variants!!, policy) }.getOrNull() ?: return null to 0L
            return c to (c.rendition?.estimatedBytes(p.item.durationMs) ?: c.variant.let { v -> (v.averageBandwidth.takeIf { it > 0 } ?: v.bandwidth) * p.item.durationMs / 8000 })
        }

        // Every stereo quality any of the songs has, best first.
        val specs = known.flatMap { QualityPlanner.renditions(it.variants!!) }.map { it.spec }.distinct()
            .sortedWith(compareByDescending<RenditionSpec> { rank(it) }.thenByDescending { it.sampleRate }.thenByDescending { it.bitDepth }.thenByDescending { it.kbps })
        val options = ArrayList<QualityOption>()
        val several = known.size > 1
        val losslessSpecs = specs.count { it.family == QualityFamily.LOSSLESS }
        fun stereoOption(spec: RenditionSpec, highest: Boolean): QualityOption {
            var bytes = 0L; var exact = 0
            for (p in known) {
                val (c, b) = bytesOf(p, QualityPolicy(spec))
                bytes += b
                if (c?.rendition?.spec == spec) exact++
            }
            val has = known.count { p -> QualityPlanner.renditions(p.variants!!).any { it.spec == spec } }
            // What "highest available" really gives: the best lossless any song has, and on how many songs.
            val bests = if (highest) known.mapNotNull { p -> QualityPlanner.renditions(p.variants!!).firstOrNull { it.family == QualityFamily.LOSSLESS }?.spec } else emptyList()
            val top = bests.maxWithOrNull(compareBy<RenditionSpec>({ it.sampleRate }, { it.bitDepth }))
            return QualityOption(
                spec.family.let { if (it == QualityFamily.LOSSLESS) "lossless" else "aac" }, spec, 0, if (highest) known.size else has, known.size, known.size - exact, bytes, highest,
                topSpec = top, topTracks = if (top == null) 0 else bests.count { it == top },
            )
        }
        if (several && losslessSpecs >= 2) options += stereoOption(RenditionSpec.BEST, true)
        specs.forEach { options += stereoOption(it, false) }

        val withAtmos = known.filter { QualityPlanner.atmosRenditions(it.variants!!).isNotEmpty() }
        if (withAtmos.isNotEmpty()) {
            var bytes = 0L
            for (p in known) bytes += bytesOf(p, QualityPolicy(RenditionSpec.BEST, HlsVariants.ATMOS_DOLBY)).second
            options += QualityOption("atmos", null, QualityPlanner.atmosRenditions(withAtmos.first().variants!!).first().kbps, withAtmos.size, known.size, known.size - withAtmos.size, bytes)
        }
        val withSpatial = known.filter { QualityPlanner.spatialRenditions(it.variants!!).isNotEmpty() }
        if (withSpatial.isNotEmpty()) {
            var bytes = 0L
            for (p in known) bytes += bytesOf(p, QualityPolicy(RenditionSpec.BEST, HlsVariants.ATMOS_SPATIAL)).second
            options += QualityOption("spatial", null, 0, withSpatial.size, known.size, known.size - withSpatial.size, bytes)
        }

        return QualityProbeResult(options, preselect(options, known.mapNotNull { it.variants }, defaults), probed.size, unchecked)
    }

    /**
     * The row "Download" does what the default does for: the Atmos/spatial row when the default asks for it; the
     * "highest available" row for the default "highest available" (several songs); the row of the exact quality the
     * default names; otherwise the row most of the songs would get from the default (a single song, or a default no row names).
     */
    internal fun preselect(options: List<QualityOption>, variants: List<List<HlsVariant>>, defaults: QualityPolicy): Int {
        fun at(pred: (QualityOption) -> Boolean) = options.indexOfFirst(pred).takeIf { it >= 0 }
        if (defaults.atmos == HlsVariants.ATMOS_DOLBY) at { it.kind == "atmos" }?.let { return it }
        if (defaults.atmos == HlsVariants.ATMOS_SPATIAL) at { it.kind == "spatial" }?.let { return it }
        if (defaults.ceiling == RenditionSpec.BEST) at { it.highestAvailable }?.let { return it }
        at { !it.highestAvailable && it.spec == defaults.ceiling }?.let { return it }
        val picks = variants.mapNotNull { v -> runCatching { QualityPlanner.choose(v, QualityPolicy(defaults.ceiling)) }.getOrNull() }
        val wanted = picks.groupingBy { it.rendition?.spec }.eachCount().maxByOrNull { it.value }?.key
        return at { !it.highestAvailable && it.spec == wanted } ?: 0
    }

    private fun rank(s: RenditionSpec) = when (s.family) { QualityFamily.LOSSLESS -> 3; QualityFamily.AAC_LC -> 2; QualityFamily.HE_AAC -> 1; QualityFamily.ATMOS -> 0 }

    private suspend fun fetchMaster(url: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(MASTER_TIMEOUT_MS) { runCatching { Http.text(url) }.getOrNull() }
    }
}
