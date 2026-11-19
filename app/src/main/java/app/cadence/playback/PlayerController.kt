@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.cadence.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import app.cadence.backend.model.AudioFormatInfo
import app.cadence.backend.model.AudioQuality
import app.cadence.backend.api.RecommendationService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.cadence.core.design.ArtworkRenderer
import app.cadence.core.design.coverAtSize
import app.cadence.core.design.prefetchCover
import app.cadence.state.JsonStore
import app.cadence.backend.api.MusicRepository
import app.cadence.state.library.UserLibrary
import app.cadence.backend.model.TrackInfo
import app.cadence.state.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class Repeat { Off, All, One }

private const val STATION_BATCH = 9
private const val AUTOPLAY_BATCH = 4

data class PlayerState(
    val connected: Boolean = false,
    /** Queue in natural (insertion) order. */
    val queue: List<TrackInfo> = emptyList(),
    val index: Int = -1,
    /** Indices into [queue] that will play after the current one, honoring shuffle. */
    val upNext: List<Int> = emptyList(),
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    val contextTitle: String? = null,
    val sleepAtMs: Long? = null,
    /** First queue index that autoplay appended (shown under an "Autoplay" divider), or -1. */
    val autoplayFrom: Int = -1,
    /** Encoding of the stream actually selected by the player, merged over library info. */
    val format: AudioFormatInfo? = null,
) {
    val current: TrackInfo? get() = queue.getOrNull(index)
}

/** UI-side handle on the playback service. All calls are no-ops until the controller connects. */
class PlayerController(
    private val context: Context,
    private val repo: MusicRepository,
    private val recommendations: RecommendationService,
    private val library: JsonStore<UserLibrary>,
    private val settings: JsonStore<UserSettings>,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var sleepJob: Job? = null
    private var restored = false

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (settings.state.value.listeningHistory) mediaItem?.mediaId?.let { id -> library.update { it.played(id) } }
                    topUpAutoplay()
                    persist()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) persist() }
            })
            restoreIfIdle(c)
            sync()
        }
    }

    /** Current position, read on demand (the lyrics view polls this every frame). */
    fun position(): Long = controller?.currentPosition ?: 0L

    fun play(tracks: List<TrackInfo>, startIndex: Int = 0, shuffle: Boolean = false, contextTitle: String? = null) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        stationMode = false // a normal play() leaves station mode; station starters re-arm it after.
        _state.value = _state.value.copy(contextTitle = contextTitle, autoplayFrom = -1)
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex
        c.setMediaItems(tracks.map(::item), start, 0)
        c.prepare()
        c.play()
        topUpAutoplay()
    }

    fun togglePlay() = controller?.run {
        if (isPlaying) pause() else {
            if (playbackState == Player.STATE_ENDED) seekTo(0)
            // A queue restored at launch is left unprepared so it costs no data until played.
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }

    /** Player gain, 0..1; independent of the device volume. */
    fun setVolume(volume: Float) { controller?.volume = volume.coerceIn(0f, 1f) }
    fun pause() = controller?.pause()
    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skipTo(queueIndex: Int) = controller?.run {
        seekTo(queueIndex, 0)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(track: TrackInfo) = controller?.run {
        if (mediaItemCount == 0) play(listOf(track)) else addMediaItem(currentMediaItemIndex + 1, item(track))
    }

    fun addToQueue(track: TrackInfo) = controller?.run {
        if (mediaItemCount == 0) play(listOf(track)) else addMediaItem(item(track))
    }

    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)
    /** An endless queue seeded from one song: it plays first, then similar music keeps coming. */
    fun startStation(seed: TrackInfo) {
        scope.launch {
            val c = controller ?: return@launch
            val picks = recommendations.similar(seed, library.state.value.suggestLess, STATION_BATCH)
            play(listOf(seed) + picks, contextTitle = "${seed.track.title} Station")
            armStation()
            _state.value = _state.value.copy(autoplayFrom = 1)
            c.shuffleModeEnabled = false
        }
    }

    /** The Discovery station: an exploration mix, regenerated fresh each time it is started. */
    fun startDiscoveryStation() {
        scope.launch {
            controller ?: return@launch
            val picks = recommendations.discovery(library.state.value.suggestLess, STATION_BATCH + 1)
            if (picks.isEmpty()) return@launch
            play(picks, contextTitle = "Discovery Station")
            armStation()
        }
    }

    /** The user's personal station: heavy rotation from their favourites, saved songs and recents. */
    fun startMyStation() {
        scope.launch {
            controller ?: return@launch
            val lib = library.state.value
            val seedIds = lib.favorites + lib.savedTracks + lib.recentTracks
            val picks = recommendations.heavyRotation(seedIds, lib.suggestLess, STATION_BATCH + 1)
            if (picks.isEmpty()) return@launch
            play(picks, contextTitle = "My Station")
            armStation()
        }
    }

    // A station keeps itself full even when the global autoplay toggle is off; [play] clears it.
    @Volatile private var stationMode = false
    private fun armStation() { stationMode = true; topUpAutoplay() }

    private var toppingUp = false

    /**
     * Keeps music going: when autoplay is on and fewer than two songs are left, append songs similar to
     * the current one (never ones already queued or marked "suggest less").
     */
    private fun topUpAutoplay() {
        val c = controller ?: return
        if (toppingUp || !(settings.state.value.autoplay || stationMode) || c.repeatMode != Player.REPEAT_MODE_OFF || c.mediaItemCount == 0) return
        val remaining = c.mediaItemCount - 1 - c.currentMediaItemIndex
        if (remaining >= 2) return
        val current = repo.track(c.currentMediaItem?.mediaId ?: return) ?: return
        val queued = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        toppingUp = true
        scope.launch {
            try {
                val picks = recommendations.similar(current, queued + library.state.value.suggestLess, AUTOPLAY_BATCH)
                if (picks.isEmpty()) return@launch
                if (_state.value.autoplayFrom < 0) _state.value = _state.value.copy(autoplayFrom = c.mediaItemCount)
                c.addMediaItems(picks.map(::item))
            } finally {
                toppingUp = false
            }
        }
    }

    fun setAutoplay(on: Boolean) {
        settings.update { it.copy(autoplay = on) }
        // Either way, drop the songs the previous autoplay added but that haven't played yet. When
        // turning on, this then regenerates a fresh continuation from the current track rather than
        // keeping whatever stale picks were already appended.
        controller?.run {
            val from = _state.value.autoplayFrom
            if (from in 0 until mediaItemCount) {
                val start = maxOf(from, currentMediaItemIndex + 1)
                if (start < mediaItemCount) removeMediaItems(start, mediaItemCount)
            }
            _state.value = _state.value.copy(autoplayFrom = -1)
        }
        if (on) scope.launch { topUpAutoplay() }
    }

    fun clearUpNext() = controller?.run {
        val cur = currentMediaItemIndex
        if (cur + 1 < mediaItemCount) removeMediaItems(cur + 1, mediaItemCount)
    }

    /** Pause after [minutes]; null cancels. 0 means "end of current song". */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _state.value = _state.value.copy(sleepAtMs = null); return }
        val c = controller ?: return
        val wait = if (minutes == 0) (c.duration - c.currentPosition).coerceAtLeast(0) else minutes * 60_000L
        _state.value = _state.value.copy(sleepAtMs = System.currentTimeMillis() + wait)
        sleepJob = scope.launch {
            delay(wait)
            controller?.pause()
            _state.value = _state.value.copy(sleepAtMs = null)
        }
    }

    private fun item(t: TrackInfo): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(t.track.title)
            .setArtist(t.artist.name)
            .setAlbumTitle(t.album.title)
            // Lets the session's artwork loader draw the procedural art if the cover cannot load.
            .setExtras(CoverBitmapLoader.extras(t.album.id, t.album.colors))
        val cover = t.album.cover
        if (cover != null) {
            // The real album cover for the notification and lock screen. CoverBitmapLoader serves
            // it from the app's cover cache (offline once seen) and falls back to procedural art.
            meta.setArtworkUri(Uri.parse(coverAtSize(cover, "512x512") ?: cover))
        } else {
            // No catalogue cover: fall back to procedural art so the notification is never blank.
            val art = ByteArrayOutputStream().also {
                ArtworkRenderer.bitmap(t.album.id, t.album.colors).compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
            }.toByteArray()
            meta.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.parse(repo.audioUri(t)))
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun sync() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { repo.track(c.getMediaItemAt(it).mediaId) }
        val upNext = buildList {
            val tl = c.currentTimeline
            if (!tl.isEmpty) {
                var i = tl.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
                while (i != C.INDEX_UNSET && size < queue.size) { add(i); i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled) }
            }
        }
        val current = queue.getOrNull(c.currentMediaItemIndex)
        _state.value = _state.value.copy(
            format = withDeclaredDepth(mergeFormat(liveFormat(c.currentTracks), current?.track?.format), current?.id),
            connected = true,
            queue = queue,
            index = if (queue.isEmpty()) -1 else c.currentMediaItemIndex,
            upNext = upNext,
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: (queue.getOrNull(c.currentMediaItemIndex)?.track?.durationMs ?: 0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) { Player.REPEAT_MODE_ONE -> Repeat.One; Player.REPEAT_MODE_ALL -> Repeat.All; else -> Repeat.Off },
        )
        prefetchUpcomingCovers(queue, upNext)
    }

    /**
     * Warm the next couple of upcoming tracks' covers (source download + display bucket + neon
     * palette) so skipping to them shows art immediately and the skip animation isn't competing with
     * a decode. Guarded by the upcoming set so the per-frame syncs don't relaunch it.
     */
    private var prefetchedCovers: Set<String> = emptySet()
    private fun prefetchUpcomingCovers(queue: List<TrackInfo>, upNext: List<Int>) {
        val urls = upNext.take(2).mapNotNull { queue.getOrNull(it)?.album?.cover }.toSet()
        if (urls.isEmpty() || urls == prefetchedCovers) return
        prefetchedCovers = urls
        scope.launch { urls.forEach { runCatching { prefetchCover(context, it) } } }
    }

    /** Reads the selected audio track's Format. Lossless decoders report bit depth via pcmEncoding. */
    private fun liveFormat(tracks: Tracks): AudioFormatInfo? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        val f = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat) ?: return null
        val bits = when (f.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> alacBitDepth(f)
        }
        return AudioFormatInfo(
            codec = AudioQuality.codecForMime(f.sampleMimeType, f.containerMimeType),
            bitDepth = bits,
            sampleRate = f.sampleRate.takeIf { it != Format.NO_VALUE },
            channels = f.channelCount.takeIf { it != Format.NO_VALUE },
            bitrate = listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it != Format.NO_VALUE },
        )
    }

    /**
     * Lossless streams report the bit depth Apple declares for the content. A CD master arrives as
     * 24-bit ALAC whose low 8 bits are all zero: that is 16-bit audio, and saying 24 would claim a
     * resolution that is not there.
     */
    private fun withDeclaredDepth(f: AudioFormatInfo?, trackId: String?): AudioFormatInfo? {
        if (f == null || trackId == null || !f.codec.equals("ALAC", true)) return f
        val bits = app.cadence.backend.engine.stream.StreamPlayback.declaredQuality(trackId)?.first ?: return f
        return if (bits > 0) f.copy(bitDepth = bits) else f
    }

    /** Stream values win; library info fills whatever the stream didn't report. */
    private fun mergeFormat(live: AudioFormatInfo?, hint: AudioFormatInfo?): AudioFormatInfo? = when {
        live == null -> hint
        hint == null || !live.codec.equals(hint.codec, true) -> live
        else -> live.copy(
            bitDepth = live.bitDepth ?: hint.bitDepth,
            sampleRate = live.sampleRate ?: hint.sampleRate,
            channels = live.channels ?: hint.channels,
            bitrate = live.bitrate ?: hint.bitrate,
        )
    }

    private fun persist() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val idx = c.currentMediaItemIndex
        val pos = c.currentPosition
        library.update { it.copy(lastQueue = ids, lastIndex = idx, lastPositionMs = pos) }
    }

    private fun restoreIfIdle(c: MediaController) {
        if (restored || c.mediaItemCount > 0 || !settings.state.value.resumeOnLaunch) return
        restored = true
        val lib = library.state.value
        val tracks = lib.lastQueue.mapNotNull(repo::track)
        if (tracks.isEmpty()) return
        // Not prepared here: preparing a streamed track starts downloading it, and reopening the
        // app should not spend mobile data. Playback prepares it (see [togglePlay]).
        c.setMediaItems(tracks.map(::item), lib.lastIndex.coerceIn(0, tracks.lastIndex), lib.lastPositionMs)
    }
}

/**
 * ALAC's real bit depth. Media3 leaves pcmEncoding unset for it, but passes the stream's ALAC
 * "magic cookie" (ALACSpecificConfig) as initializationData[0]: byte 5 is the bit depth.
 */
internal fun alacBitDepth(f: Format): Int? =
    f.takeIf { it.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_ALAC }
        ?.initializationData?.firstOrNull()
        ?.takeIf { it.size >= 24 }
        ?.let { it[5].toInt() and 0xFF }
        ?.takeIf { it in setOf(16, 20, 24, 32) }
