package app.cadence.feature.download

import android.content.Context
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.DownloadEngine.DownloadItem
import app.cadence.backend.engine.media.DownloadRegistry
import app.cadence.backend.engine.media.QualityProbe
import app.cadence.backend.engine.media.QualityProbeResult
import app.cadence.state.JsonStore
import app.cadence.state.settings.UserSettings
import app.cadence.state.settings.asksDownloadQuality
import app.cadence.state.settings.downloadPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every download starts here (a song, an album, a playlist). It decides whether to just download, to ask which quality,
 * or to ask before adding a second copy of a song that is already downloaded, and hands the result to [DownloadEngine].
 * The sheet ([DownloadRequestHost]) only renders [state] and calls back into [select], [setMakeDefault], [confirm], [cancel].
 */
object DownloadRequests {
    private var context: Context? = null
    private var settings: JsonStore<UserSettings>? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private val _state = MutableStateFlow<DownloadRequest?>(null)
    val state: StateFlow<DownloadRequest?> = _state

    fun attach(context: Context, settings: JsonStore<UserSettings>, scope: CoroutineScope) {
        this.context = context.applicationContext
        this.settings = settings
        this.scope = scope
    }

    fun request(items: List<DownloadItem>) {
        val ctx = context ?: return
        val store = settings ?: return
        val sc = scope ?: return
        if (items.isEmpty()) return
        job?.cancel()
        job = sc.launch {
            val s = store.state.value
            val existing = withContext(Dispatchers.IO) { runCatching { DownloadRegistry.existing(ctx, items.map { it.trackId }) }.getOrDefault(emptyMap()) }
            if (existing.isNotEmpty()) {
                val e = summarizeExisting(items, existing)
                CadenceLog.i("QUALITY", "request n=${items.size} already downloaded: ${e.songs} song(s) as ${e.specs.joinToString { it.ifBlank { "unknown" } }}")
            }
            when (requestStep(s.asksDownloadQuality, existing.isNotEmpty())) {
                RequestStep.Enqueue -> DownloadEngine.enqueue(ctx, items)
                RequestStep.Confirm -> _state.value = DownloadRequest.Confirm(items, existing)
                RequestStep.Pick -> {
                    _state.value = DownloadRequest.Loading(items)
                    val probe: QualityProbeResult = QualityProbe.probe(items, s.downloadPolicy())
                    _state.value = DownloadRequest.Choose(items, probe, probe.preselected, existing)
                }
            }
        }
    }

    fun select(index: Int) = _state.update { (it as? DownloadRequest.Choose)?.select(index) ?: it }
    fun setMakeDefault(on: Boolean) = _state.update { (it as? DownloadRequest.Choose)?.copy(makeDefault = on) ?: it }

    /** Starts the download the sheet is showing (at the picked quality, or the default when there is no picker). */
    fun confirm() {
        val ctx = context ?: return
        val request = _state.value ?: return
        _state.value = null
        job?.cancel()
        when (request) {
            is DownloadRequest.Loading -> Unit
            is DownloadRequest.Confirm -> DownloadEngine.enqueue(ctx, request.items)
            is DownloadRequest.Choose -> {
                val option = request.option
                if (option == null) {
                    DownloadEngine.enqueue(ctx, request.items)
                } else {
                    val policy = option.policy()
                    CadenceLog.i("QUALITY", "picked ${policy.cacheKey()} for ${request.items.size} song(s)${if (request.makeDefault) " and made it the default" else ""}")
                    if (request.makeDefault) {
                        val hadAtmos = request.probe.options.any { it.kind == "atmos" }
                        settings?.update { it.withDefaultFrom(option, hadAtmos, request.probe.isTopLossless(option)) }
                    }
                    DownloadEngine.enqueue(ctx, request.items.map { it.copy(quality = policy) })
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = null
    }

    private inline fun <T> MutableStateFlow<T>.update(f: (T) -> T) { value = f(value) }
}
