package app.cadence.backend.engine.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

data class FairPlayPlan(
    val mediaUrl: String,
    val keyUri: String,
    /** The variant's media playlist as fetched while resolving, or blank if that fetch failed. */
    val mediaPlaylist: String = "",
    /** The chosen rendition's real codec ("alac", "aac" or "ec-3"), PCM bit depth and sample rate (Hz); 0 if unknown. */
    val codec: String = "",
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    /** What was chosen: "alac", "aac", "atmos" (the E-AC-3 mix) or "spatial" (its binaural AAC render). */
    val kind: String = codec,
    /** The chosen rendition as a short string (`alac:24:96000`, `aac:128`, `atmos:768`, `spatial`); "" when unknown. */
    val spec: String = "",
    /** Why this rendition (what was wanted, what the track offers); "" for the older selection. */
    val reason: String = "",
)

object WrapperKeyClient {
    /**
     * Picks the variant to fetch. `codec` "alac" takes the lossless one, honouring the preferred
     * bit depth and sample rate ceiling (Apple usually publishes one; when it offers several,
     * e.g. 44.1/16 and 48/24, this chooses between them). `codec` "aac" takes the 256 kbps
     * stereo AAC-LC variant, about a fifth of the size; a track without one falls back to
     * lossless. [atmos] ("off", "spatial" or "atmos") plays a track's Dolby Atmos mix when it
     * has one; see [HlsVariants.choose].
     */
    suspend fun resolve(
        track: Track,
        preferredBitDepth: Int = 24,
        maxSampleRate: Int = 0,
        codec: String = "alac",
        atmos: String = HlsVariants.ATMOS_OFF,
    ): FairPlayPlan = withContext(Dispatchers.IO) {
        val masterUrl = track.hlsUrl
            ?: throw IOException("Track has no enhancedHls URL")
        AppLog.log("API", "master=$masterUrl")
        parseMaster(masterUrl, preferredBitDepth, maxSampleRate, codec, atmos) { variants ->
            val c = HlsVariants.choose(variants, codec, atmos, preferredBitDepth, maxSampleRate)
            Picked(c.variant, c.codec, c.kind, "", "")
        }
    }

    /**
     * Picks the rendition for a download from a [QualityPolicy] (the quality the person asked for or their
     * default), falling back as described in [QualityPlanner.choose], and logs what was wanted and what the
     * track offers (`[QUALITY]` lines), so a report says why a file is the quality it is.
     */
    suspend fun resolve(track: Track, policy: QualityPolicy): FairPlayPlan = withContext(Dispatchers.IO) {
        val masterUrl = track.hlsUrl
            ?: throw IOException("Track has no enhancedHls URL")
        AppLog.log("API", "master=$masterUrl")
        parseMaster(masterUrl, 24, 0, policy.ceiling.format(), policy.atmos) { variants ->
            val c = QualityPlanner.choose(variants, policy)
            val spec = when (c.kind) {
                "atmos" -> "atmos:${c.rendition?.kbps ?: 0}"
                "spatial" -> "spatial"
                else -> c.rendition?.spec?.format().orEmpty()
            }
            AppLog.log("QUALITY", "track=${track.id} policy=${policy.cacheKey()} -> ${spec.ifBlank { c.kind }}: ${c.reason}")
            Picked(c.variant, c.codec, c.kind, spec, c.reason)
        }
    }

    private class Picked(val variant: HlsVariant, val codec: String, val kind: String, val spec: String, val reason: String)

    private fun parseMaster(
        masterUrl: String,
        preferredBitDepth: Int,
        maxSampleRate: Int,
        codec: String,
        atmos: String,
        select: (List<HlsVariant>) -> Picked,
    ): FairPlayPlan {
        val master = readText(URL(masterUrl))
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = HlsVariants.parse(master, masterUrl)
        val choice = select(variants)
        val best = choice.variant
        val group = best.group
        AppLog.log(
            "Native",
            "${choice.kind}=${best.url} group=${best.groupId} " +
                "variant=${group["SAMPLE-RATE"] ?: "?"}Hz/${group["BIT-DEPTH"] ?: "?"}bit " +
                "pref=${preferredBitDepth}bit/max${if (maxSampleRate == 0) "auto" else "${maxSampleRate}Hz"} " +
                "atmos=$atmos variants=${variants.size}",
        )

        // Key URIs are chosen for the plan/log; the actual per-segment keys come
        // from the media playlist's EXT-X-KEY lines (see HlsDownloader/KeyPlan).
        val media = runCatching { readText(URL(best.url)) }.getOrDefault("")
        val keyUris = (media.lineSequence().toList() + lines)
            .filter { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") }
            .mapNotNull { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            .filter { it.startsWith("skd://") }
        val keyUri = keyUris.lastOrNull { it.endsWith("/c6") }
            ?: keyUris.lastOrNull { !it.contains("P000000000") && !it.contains("/s1/e1") }
            ?: keyUris.lastOrNull()
            ?: throw IOException("No FairPlay key URI")
        AppLog.log("Native", "key=$keyUri keys=${keyUris.size}")
        return FairPlayPlan(
            mediaUrl = best.url,
            keyUri = keyUri,
            mediaPlaylist = media,
            codec = choice.codec,
            bitDepth = group["BIT-DEPTH"]?.toIntOrNull() ?: 0,
            sampleRate = group["SAMPLE-RATE"]?.toIntOrNull() ?: 0,
            kind = choice.kind,
            spec = choice.spec,
            reason = choice.reason,
        )
    }

    private fun readText(url: URL): String {
        var last: IOException? = null
        for (attempt in 1..2) {
            val started = System.currentTimeMillis()
            AppLog.log("HTTP", "okhttp GET ${url.host} attempt=$attempt")
            try {
                val body = Http.text(url.toString())
                AppLog.log("HTTP", "${url.host} -> ${body.length}B in ${System.currentTimeMillis() - started}ms")
                return body
            } catch (e: IOException) {
                last = e
                AppLog.error("HTTP", "${url.host} attempt=$attempt failed after ${System.currentTimeMillis() - started}ms", e)
                if (attempt < 2) Thread.sleep(300)
            }
        }
        throw IOException("HTTP ${url.host} failed: ${last?.message}", last)
    }
}
