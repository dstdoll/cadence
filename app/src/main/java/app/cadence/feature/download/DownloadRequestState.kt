package app.cadence.feature.download

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import app.cadence.backend.engine.media.DownloadRecord
import app.cadence.backend.engine.media.QualityOption
import app.cadence.backend.engine.media.QualityProbeResult
import app.cadence.state.settings.UserSettings

/** What the download sheet shows. Pure data: the flow in [DownloadRequests] moves between these and the sheet only renders them. */
sealed interface DownloadRequest {
    val items: List<DownloadItem>

    /** Reading the songs' master playlists. */
    data class Loading(override val items: List<DownloadItem>) : DownloadRequest

    /**
     * The picker. [selected] indexes [probe]'s options; with no options (nothing could be read) the sheet says so and
     * downloads at the default. [existing] holds the already-downloaded files of these songs, and [makeDefault] is the
     * "use this as my default" box.
     */
    data class Choose(
        override val items: List<DownloadItem>,
        val probe: QualityProbeResult,
        val selected: Int,
        val existing: Map<String, List<DownloadRecord>>,
        val makeDefault: Boolean = false,
    ) : DownloadRequest {
        val option: QualityOption? get() = probe.options.getOrNull(selected)
        fun select(index: Int) = if (index in probe.options.indices) copy(selected = index) else this
    }

    /** No picker (the person chose "use the default"), but some of these songs are already downloaded: ask before adding a second file. */
    data class Confirm(override val items: List<DownloadItem>, val existing: Map<String, List<DownloadRecord>>) : DownloadRequest
}

/** How many of the requested songs already have a downloaded file, and the files' qualities (specs; "" = not recorded). */
data class ExistingSummary(val songs: Int, val total: Int, val specs: List<String>, val newest: DownloadRecord?)

fun summarizeExisting(items: List<DownloadItem>, existing: Map<String, List<DownloadRecord>>): ExistingSummary {
    val mine = items.mapNotNull { existing[it.trackId]?.takeIf { l -> l.isNotEmpty() } }
    val all = mine.flatten()
    return ExistingSummary(mine.size, items.size, all.map { it.spec }.distinct(), all.maxByOrNull { it.atMs })
}

/**
 * The settings after "use this as my default": a stereo row saves its quality (and turns the Dolby Atmos preference off
 * when the songs had an Atmos row, because picking stereo over it was the choice); an Atmos row saves the Atmos choice.
 */
fun UserSettings.withDefaultFrom(option: QualityOption, hadAtmosRow: Boolean, topLossless: Boolean = false): UserSettings = when (option.kind) {
    "atmos" -> copy(downloadAtmos = "atmos")
    "spatial" -> copy(downloadAtmos = "spatial")
    else -> copy(
        // The best lossless row of what was offered means "the best there is": saving its exact rate would cap future,
        // higher-rate songs (a 176.4 kHz row saved as the default would give 192 kHz songs 48 kHz).
        downloadQuality = (if (topLossless) app.cadence.backend.engine.media.RenditionSpec.BEST else option.spec ?: app.cadence.backend.engine.media.RenditionSpec.BEST).format(),
        downloadAtmos = if (hadAtmosRow) "off" else downloadAtmos,
    )
}

/** What a download request does first. */
enum class RequestStep {
    /** Download at the default now: the person chose "use the default" and nothing is downloaded yet. */
    Enqueue,
    /** No picker, but some songs are already downloaded: ask before adding a second file. */
    Confirm,
    /** Show the quality picker (it also tells about files that already exist). */
    Pick,
}

/** The first step for a request: [asks] is "ask which quality" (the default), [alreadyDownloaded] whether any song has a file. */
fun requestStep(asks: Boolean, alreadyDownloaded: Boolean): RequestStep = when {
    asks -> RequestStep.Pick
    alreadyDownloaded -> RequestStep.Confirm
    else -> RequestStep.Enqueue
}

/** Whether [option] is the highest-rate lossless row of this probe (the one "use as my default" should save as "highest available"). */
fun QualityProbeResult.isTopLossless(option: QualityOption): Boolean {
    if (option.kind != "lossless" || option.highestAvailable) return false
    val top = options.filter { it.kind == "lossless" && !it.highestAvailable }.maxByOrNull { it.spec?.sampleRate ?: 0 }
    return top != null && top.spec == option.spec
}
