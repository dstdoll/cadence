package app.cadence.backend.engine

import app.cadence.backend.engine.media.QualityPolicy
import android.content.Context
import app.cadence.backend.engine.media.EngineDownloader
import app.cadence.backend.engine.media.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** How a download folder/template is chosen. Values are persisted as strings. */
object DownloadKind {
    const val SINGLE = "single"
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
}

/**
 * amdl-style download configuration, supplied by the app whenever Settings change.
 * Paths are SAF tree uris; null means "Music/Cadence" (MediaStore).
 */
data class DownloadPreferences(
    val defaultTreeUri: String? = null,
    val albumTreeUri: String? = null,
    val playlistTreeUri: String? = null,
    val singleTreeUri: String? = null,
    val albumFolderTemplate: String = "{artist}/{album}",
    val trackFileTemplate: String = "{track:02d} {title}",
    val playlistFolderTemplate: String = "{playlist}",
    val playlistTrackFileTemplate: String = "{track:02d} {artist} - {title}",
    /** "full" | "year" | "none" for the `©day` atom. */
    val albumDate: String = "full",
    val writeMetadata: Boolean = true,
    val embedCover: Boolean = true,
    val coverSize: String = "1400x1400",
    val coverFormat: String = "jpg",
    val embedLyrics: Boolean = true,
    val saveLyricsFile: Boolean = false,
    val lyricsFormat: String = "lrc",
    /** Preferred ALAC bit depth when the master offers several. */
    val bitDepth: Int = 24,
    /** Ceiling in Hz; 0 = Auto (best available). */
    val maxSampleRate: Int = 0,
    /** Format of downloaded files: "alac" (lossless) or "aac" (256 kbps). */
    val codec: String = "alac",
    /** Format streamed for playback: "alac" or "aac". */
    val streamCodec: String = "alac",
    /**
     * Dolby Atmos for playback: "off", "spatial" (Apple's binaural render of the Atmos mix, AAC)
     * or "atmos" (the E-AC-3 JOC mix). Only tracks mixed in Atmos are affected.
     */
    val streamAtmos: String = "off",
    /**
     * Dolby Atmos for downloads, the same choices as [streamAtmos]: a song mixed in Atmos is saved as
     * that mix (E-AC-3 JOC in an .m4a) or as its binaural AAC render; other songs keep [codec].
     */
    val atmos: String = "off",
    /**
     * Download segment by segment (fetch + decrypt + mux each segment as it arrives), the same way
     * playback does, instead of downloading the whole encrypted package and then decrypting it in
     * one pass. Off falls back to the two-phase path.
     */
    val streamingDownload: Boolean = true,
    /**
     * When [streamingDownload] is on and the download quality matches the streaming quality, reuse
     * playback's decrypted-segment cache so a track you just played downloads almost instantly (and
     * a download warms the cache for later plays). Off keeps a dedicated download cache, isolated
     * from playback but without that reuse.
     */
    val shareStreamCache: Boolean = true,
    /**
     * The quality to download (highest wanted + Dolby Atmos choice). Null means "what [codec], [bitDepth],
     * [maxSampleRate] and [atmos] say", which is how every setting from before the quality picker reads.
     */
    val quality: QualityPolicy? = null,
) {
    /** The quality this job downloads; see [quality]. */
    fun policy(): QualityPolicy = quality ?: QualityPolicy.fromLegacy(codec, bitDepth, maxSampleRate, atmos)

    /** These preferences for one download that asked for [chosen] (null keeps the default). */
    fun forQuality(chosen: QualityPolicy?): DownloadPreferences = if (chosen == null) this else copy(quality = chosen)

    /** The streaming cache variant this quality maps to; matches `AmpStreamResolver.variant`. */
    fun downloadVariant(): String = policy().cacheKey()
    fun streamVariant(): String {
        val base = if (streamCodec == "aac") "aac/256" else "alac/$bitDepth/$maxSampleRate"
        // Atmos tracks are cached apart from the stereo renditions; other tracks share the key.
        return if (streamAtmos == "off") base else "$streamAtmos+$base"
    }

    fun treeFor(kind: String): String? = when (kind) {
        DownloadKind.ALBUM -> albumTreeUri ?: defaultTreeUri
        DownloadKind.PLAYLIST -> playlistTreeUri ?: defaultTreeUri
        else -> singleTreeUri ?: defaultTreeUri
    }

    fun folderTemplateFor(kind: String): String =
        if (kind == DownloadKind.PLAYLIST) playlistFolderTemplate else albumFolderTemplate

    fun fileTemplateFor(kind: String): String =
        if (kind == DownloadKind.PLAYLIST) playlistTrackFileTemplate else trackFileTemplate
}

/**
 * Public entry point for downloads. Resolves the track's HLS asset from the
 * catalogue, then hands off to the in-process engine (fetch -> FairPlay decrypt
 * -> tag -> publish).
 *
 * Jobs run one at a time and are exposed as a [StateFlow] so the downloads window
 * can draw a bar per phase: `downloading` fills the download bar, `decrypting`
 * fills the decrypt bar, and decryption starts on its own the moment the
 * encrypted package is complete.
 */
object DownloadEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * FIFO consumed by one worker; the Apple KD context is process-wide, so tracks are decrypted
     * one at a time. The network half of the next track runs while the current one decrypts.
     */
    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val lock = Any()
    private var nextOrder = 0L

    private data class Pending(val context: Context, val item: DownloadItem)

    init {
        scope.launch {
            var ahead: Pair<Pending, Deferred<EngineDownloader.Staged?>>? = null
            while (true) {
                val (pending, staged) = ahead ?: queue.receive().let { it to stageAsync(it) }
                val finishing = async { staged.await()?.let { finishJob(pending, it) } }
                // One track ahead at most (a second encrypted package on disk, never a pile of
                // them), taken as soon as it is queued, even if that is in the middle of this one.
                ahead = select {
                    finishing.onAwait { null }
                    queue.onReceive { it to stageAsync(it) }
                }
                finishing.await()
            }
        }
    }

    /** Written by the app whenever Settings change; read at job start. */
    @Volatile
    var preferences: DownloadPreferences = DownloadPreferences()
        private set

    fun configure(preferences: DownloadPreferences) {
        this.preferences = preferences
    }

    data class Job(
        val trackId: String,
        val title: String,
        val subtitle: String,
        /** queued | downloading | decrypting | done | error */
        val phase: String = "queued",
        val fraction: Float = 0f,
        val message: String = "",
        val outputUri: String? = null,
        /** Enqueue order; the window shows active jobs in this order. */
        val order: Long = 0L,
    ) {
        val active: Boolean get() = phase == "queued" || phase == "downloading" || phase == "decrypting"
    }

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    /**
     * Bumped per successful enqueue. The UI opens the Downloads window from this
     * rather than from list size: re-downloading a finished track replaces its job
     * (same size) but must still surface progress.
     */
    private val _enqueueEvents = MutableStateFlow(0L)
    val enqueueEvents: StateFlow<Long> = _enqueueEvents

    /** Requests a single track by id; [hlsUrl] comes from `Track.audio`. */
    fun downloadTrack(
        context: Context,
        trackId: String,
        title: String,
        artist: String,
        albumTitle: String,
        hlsUrl: String,
        artworkTemplate: String? = null,
        kind: String = DownloadKind.SINGLE,
        collectionTitle: String = "",
        releaseDate: String? = null,
        genre: String? = null,
        trackNumber: Int = 1,
        trackTotal: Int = 0,
        discNumber: Int = 1,
        copyright: String? = null,
        hasLyrics: Boolean = true,
    ) = enqueue(
        context,
        listOf(
            DownloadItem(
                trackId, title, artist, albumTitle, hlsUrl, artworkTemplate, kind, collectionTitle,
                releaseDate, genre, trackNumber, trackTotal, discNumber, copyright, hasLyrics,
            )
        ),
    )

    /** Appends tracks to the FIFO queue and shows them as queued immediately. */
    fun downloadAll(context: Context, items: List<DownloadItem>) = enqueue(context, items)

    /**
     * The queue is a plain FIFO drained by one worker. Every item becomes a
     * visible `queued` job before it starts, so an album download shows all of
     * its songs at once and then fills in as they are processed.
     */
    fun enqueue(context: Context, items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        var added = 0
        synchronized(lock) {
            items.forEach { item ->
                val existing = _jobs.value.firstOrNull { it.trackId == item.trackId }
                if (existing?.active == true) return@forEach
                val base = Job(
                    trackId = item.trackId,
                    title = item.title,
                    subtitle = listOf(item.artist, item.albumTitle).filter { it.isNotBlank() }.joinToString(" · "),
                    phase = "queued",
                    message = "Queued",
                    order = nextOrder++,
                )
                _jobs.update { list -> trimJobs(list.filterNot { it.trackId == item.trackId } + base) }
                // Queue even when the cached catalogue row has no URL: the engine
                // re-resolves enhancedHls for the track before downloading, which
                // is exactly how expired/blank cached asset URLs recover.
                queue.trySend(Pending(app, item))
                added++
            }
        }
        if (added > 0) _enqueueEvents.update { it + 1 }
        if (added > 1) CadenceLog.i("download", "queued $added tracks kind=${items.first().kind}")
        if (added > 0) CadenceLog.i("QUALITY", "enqueue kind=${items.first().kind} n=$added policy=" +
            (items.firstOrNull { it.quality != null }?.quality?.cacheKey()?.let { "picked $it" } ?: "default ${preferences.downloadVariant()}"))
    }

    /** Drops finished/failed rows from the window. */
    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
    }

    private fun subtitle(item: DownloadItem) = listOf(item.artist, item.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")
    private fun orderOf(item: DownloadItem) = _jobs.value.firstOrNull { it.trackId == item.trackId }?.order ?: 0L

    private fun report(item: DownloadItem, order: Long): (DownloadState) -> Unit = { st ->
        upsert(
            Job(
                trackId = item.trackId,
                title = item.title,
                subtitle = subtitle(item),
                phase = when {
                    st.error != null -> "error"
                    st.phase == "decrypting" -> "decrypting"
                    st.phase == "done" || st.done -> "done"
                    else -> "downloading"
                },
                fraction = st.progress.coerceIn(0f, 1f),
                message = st.error ?: st.message,
                outputUri = if (st.done) st.message else null,
                order = order,
            )
        )
    }

    private fun failed(item: DownloadItem, order: Long, error: Throwable) {
        CadenceLog.e("download", "failed for ${item.trackId}", error)
        upsert(Job(item.trackId, item.title, subtitle(item), "error", message = error.message ?: "Download failed", order = order))
    }

    /** Starts the network half of a job. Null when it failed (the job already shows the error). */
    private fun stageAsync(pending: Pending): Deferred<EngineDownloader.Staged?> = scope.async {
        // Do NOT check for an active job here: `enqueue` has already inserted this item's
        // `queued` job, which is itself `active`. Duplicate taps are filtered in `enqueue`.
        val item = pending.item
        val order = orderOf(item)
        upsert(Job(item.trackId, item.title, subtitle(item), "downloading", message = "Starting", order = order))
        CadenceLog.i("download", "start ${item.trackId} ${item.artist} - ${item.title} kind=${item.kind}")
        runCatching { EngineDownloader.stage(pending.context, item, preferences, report(item, order)) }
            .onFailure { failed(item, order, it) }
            .getOrNull()
    }

    private suspend fun finishJob(pending: Pending, staged: EngineDownloader.Staged) {
        val order = orderOf(pending.item)
        runCatching { EngineDownloader.finish(pending.context, staged, report(pending.item, order)) }
            .onFailure { staged.discard(); failed(pending.item, order, it) }
    }

    /** Updating a track keeps its queue position; new tracks append. */
    private fun upsert(job: Job) {
        _jobs.update { list ->
            if (list.any { it.trackId == job.trackId }) {
                list.map { if (it.trackId == job.trackId) job else it }
            } else {
                trimJobs(list + job)
            }
        }
    }

    /** Active/queued jobs in FIFO order first, then finished jobs newest-first. */
    fun ordered(jobs: List<Job>): List<Job> =
        jobs.sortedWith(
            compareBy<Job> { if (it.active) 0 else 1 }
                .thenBy { if (it.active) it.order else -it.order },
        )

    data class DownloadItem(
        val trackId: String,
        val title: String,
        val artist: String,
        val albumTitle: String,
        val hlsUrl: String,
        val artworkTemplate: String? = null,
        val kind: String = DownloadKind.SINGLE,
        /** Playlist name for kind=playlist (used by `{playlist}`). */
        val collectionTitle: String = "",
        val releaseDate: String? = null,
        val genre: String? = null,
        val trackNumber: Int = 1,
        val trackTotal: Int = 0,
        val discNumber: Int = 1,
        val copyright: String? = null,
        val hasLyrics: Boolean = true,
        /** The quality chosen for this download in the picker; null = the default from settings. */
        val quality: QualityPolicy? = null,
        /** The song's length, for the size estimate in the quality picker; 0 if unknown. */
        val durationMs: Long = 0L,
    )
}

/**
 * Bounds the job list without ever dropping a job that is still queued or running: only finished
 * rows (done or failed) are trimmed, oldest first. Dropping a queued row hid it from the window and
 * let the same track be queued, and downloaded, a second time.
 */
internal fun trimJobs(list: List<DownloadEngine.Job>, cap: Int = 200): List<DownloadEngine.Job> {
    if (list.size <= cap) return list
    val room = (cap - list.count { it.active }).coerceAtLeast(0)
    val keep = list.filter { !it.active }.takeLast(room).map { it.trackId }.toHashSet()
    return list.filter { it.active || it.trackId in keep }
}
