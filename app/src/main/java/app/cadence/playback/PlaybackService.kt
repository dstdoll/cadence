package app.cadence.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.media.AutoMixPlanner
import app.cadence.backend.engine.media.TransitionKind
import app.cadence.backend.engine.media.TransitionPlan
import app.cadence.state.settings.TransitionMode
import app.cadence.toDownloadPreferences
import app.cadence.backend.engine.stream.StreamCache
import app.cadence.backend.engine.stream.StreamConfig
import app.cadence.backend.engine.stream.StreamPlayback
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.cadence.MainActivity
import app.cadence.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Hosts the ExoPlayer + MediaSession; Media3 provides the notification, lock screen and headset controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var coverLoader: CoverBitmapLoader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val streams = StreamPlayback.cache(this)
        StreamPlayback.warmUp(this)
        // Android ships no ALAC decoder, so ALAC is decoded by our native Rust AlacAudioRenderer.
        // Float output hands 24-bit masters to the mixer intact instead of converting them to 16-bit.
        // The waveform tap wraps the sink the renderers write to, so it sees the PCM in 16-bit
        // and in float output alike (audio processors are bypassed for float).
        val waveTap = WaveTap(container.wave)
        val renderers = object : DefaultRenderersFactory(this@PlaybackService) {
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                out.add(Eac3AudioRenderer(eventHandler, eventListener, audioSink))
            }

            override fun buildAudioSink(
                context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                // The stock sink, with an output provider that can switch a USB DAC to bit-perfect.
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioOutputProvider(
                        BitPerfectOutputProvider(
                            androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build(),
                            context,
                        ) { container.settings.state.value.bitPerfectUsb },
                    )
                    .build()
                    .let { WaveAudioSink(it, waveTap) }
                    // Outermost: the custom EQ edits the PCM before the wave tap and the output see it.
                    // A disabled/flat config makes this a pure pass-through.
                    .let { sink -> DspAudioSink(sink, loudness = FileLoudnessStore(java.io.File(filesDir, "dsp/loudness.json"))) { container.settings.state.value.let { s -> DspSettings(s.dspReplayGain, s.dspTone, s.dspEq, s.dspChannel, s.dspDither, bypassed = dspBypassed(s.bitPerfectUsb, usbOutput)) } } }
        }
            .setEnableAudioFloatOutput(useFloatOutput(container.settings.state.value))
        val baseMediaSourceFactory = DefaultMediaSourceFactory(CadenceDataSourceFactory(this, streams))
            .setDrmSessionManagerProvider { item ->
                if (item.localConfiguration?.drmConfiguration == null) {
                    androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED
                } else {
                    androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            C.WIDEVINE_UUID,
                            androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER,
                        )
                        .build(AppleWidevineDrmCallback(applicationContext, item.mediaId))
                }
            }
        val aacCache = app.cadence.backend.engine.stream.WebPlayback.aacCache(applicationContext)
        val mediaSourceFactory = object : androidx.media3.exoplayer.source.MediaSource.Factory {
            override fun setDrmSessionManagerProvider(drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) =
                apply { baseMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider) }

            override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) =
                apply { baseMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

            override fun getSupportedTypes(): IntArray = baseMediaSourceFactory.supportedTypes

            override fun createMediaSource(mediaItem: MediaItem): androidx.media3.exoplayer.source.MediaSource {
                val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
                val webId = app.cadence.backend.engine.stream.WebPlayback.trackIdOf(uri)
                val cached = webId?.let { aacCache.cachedFile(it) }
                val effective = if (cached != null) {
                    MediaItem.Builder()
                        .setMediaId(mediaItem.mediaId)
                        .setUri(android.net.Uri.fromFile(cached))
                        .setMediaMetadata(mediaItem.mediaMetadata)
                        .build()
                } else {
                    mediaItem
                }
                return baseMediaSourceFactory.createMediaSource(effective)
            }
        }
        fun buildDeckPlayer(handleAudioFocus: Boolean): ExoPlayer =
            ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    handleAudioFocus,
                )
                .setHandleAudioBecomingNoisy(handleAudioFocus)
                // Streaming keeps downloading with the screen off, so the network is held awake too.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

        val initialPlayer = buildDeckPlayer(handleAudioFocus = true)
        activePlayer = initialPlayer
        secondaryPlayer = buildDeckPlayer(handleAudioFocus = false)
        CadenceLog.i("player", "alac=native float=${useFloatOutput(container.settings.state.value)}")
        initialPlayer.addAnalyticsListener(PlaybackLog(applicationContext))
        secondaryPlayer?.addAnalyticsListener(PlaybackLog(applicationContext))
        val refreshQueueWindow = attachQueueFollower(streams)
        attachDeckQueueListeners(initialPlayer, refreshQueueWindow)
        secondaryPlayer?.let { attachDeckQueueListeners(it, refreshQueueWindow) }
        // The waveform is credited to the position that is audible, whether or not a screen shows it.
        scope.launch {
            while (true) {
                delay(50)
                val p = activePlayer ?: continue
                val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                container.wave.tick(
                    nowUs = System.nanoTime() / 1000,
                    key = p.currentMediaItem?.mediaId,
                    positionUs = p.currentPosition * 1000,
                    lengthUs = duration * 1000,
                    speed = p.playbackParameters.speed,
                    playing = p.isPlaying,
                )
            }
        }
        startAutoMixLoop(refreshQueueWindow)

        scope.launch {
            container.settings.state.map { it.skipSilence }.distinctUntilChanged().collect { enabled ->
                activePlayer?.skipSilenceEnabled = enabled
                secondaryPlayer?.skipSilenceEnabled = enabled
            }
        }
        watchUsbOutput()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        coverLoader = CoverBitmapLoader(this)
        // The status bar and notification show Cadence's own mark, not Media3's generic note.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build()
                .apply { setSmallIcon(app.cadence.R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, initialPlayer)
            .setSessionActivity(open)
            .setCallback(ControllerPolicy(packageName))
            .setBitmapLoader(coverLoader!!)
            .build()
    }

    @Volatile private var activePlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null

    private data class PlanCacheKey(
        val outId: String,
        val inId: String,
        val mode: TransitionMode,
        val crossfadeSec: Int,
    )

    /**
     * Coordinates dual-deck Crossfade and AutoMix transitions on [activePlayer] and [secondaryPlayer].
     * Bypassed when bit-perfect USB DAC output is active or TransitionMode is Gapless.
     */
    private fun startAutoMixLoop(onDeckSwapped: () -> Unit) {
        var cachedKey: PlanCacheKey? = null
        var cachedPlan: TransitionPlan? = null
        var fetchingKey: PlanCacheKey? = null
        var preCuedForKey: PlanCacheKey? = null
        var overlapping = false
        var overlapOutIndex = C.INDEX_UNSET
        var overlapInIndex = C.INDEX_UNSET
        var overlapStartWallMs = 0L
        var overlapPausedAtMs = 0L
        var overlapPlan: TransitionPlan? = null

        fun resetSecondary(primary: ExoPlayer?) {
            val sec = secondaryPlayer
            if (sec != null) {
                sec.pause()
                sec.clearMediaItems()
                sec.volume = 1f
                sec.playbackParameters = PlaybackParameters.DEFAULT
            }
            primary?.volume = 1f
            overlapping = false
            overlapOutIndex = C.INDEX_UNSET
            overlapInIndex = C.INDEX_UNSET
            overlapPausedAtMs = 0L
            overlapPlan = null
            preCuedForKey = null
        }

        scope.launch {
            while (true) {
                delay(20)
                val primary = activePlayer ?: continue
                val secondary = secondaryPlayer ?: continue
                val settings = container.settings.state.value
                val bypassed = settings.transitionMode == TransitionMode.Gapless ||
                    dspBypassed(settings.bitPerfectUsb, usbOutput) ||
                    primary.repeatMode == Player.REPEAT_MODE_ONE ||
                    primary.mediaItemCount <= 1

                if (bypassed) {
                    if (overlapping || preCuedForKey != null) resetSecondary(primary)
                    continue
                }

                if (overlapping) {
                    val plan = overlapPlan ?: continue
                    val currentIdx = primary.currentMediaItemIndex
                    if (currentIdx != overlapOutIndex || primary.currentPosition < plan.fadeOutStartMs - 1_500L) {
                        CadenceLog.i("AutoMix", "abort overlap (seek/skip/queue change)")
                        resetSecondary(primary)
                        continue
                    }
                    if (!primary.playWhenReady) {
                        if (secondary.playWhenReady) secondary.playWhenReady = false
                        if (overlapPausedAtMs == 0L) overlapPausedAtMs = android.os.SystemClock.elapsedRealtime()
                        continue
                    } else if (overlapPausedAtMs != 0L) {
                        overlapStartWallMs += (android.os.SystemClock.elapsedRealtime() - overlapPausedAtMs)
                        overlapPausedAtMs = 0L
                        secondary.playWhenReady = true
                    }

                    val elapsed = (android.os.SystemClock.elapsedRealtime() - overlapStartWallMs).coerceAtLeast(0L)
                    val progress = (elapsed.toFloat() / plan.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    val angle = progress * (PI.toFloat() / 2f)
                    primary.volume = cos(angle)
                    secondary.volume = sin(angle)
                    if (plan.speedRatio != 1f) {
                        val speed = plan.speedRatio + (1f - plan.speedRatio) * progress
                        secondary.playbackParameters = PlaybackParameters(speed)
                    }

                    if (progress >= 1f || primary.playbackState == Player.STATE_ENDED) {
                        swapDecks(primary, secondary, overlapInIndex, onDeckSwapped)
                        overlapping = false
                        overlapPlan = null
                        overlapPausedAtMs = 0L
                        preCuedForKey = null
                        cachedKey = null
                        cachedPlan = null
                    }
                    continue
                }

                if (!primary.isPlaying) continue
                val outIndex = primary.currentMediaItemIndex
                val inIndex = primary.nextMediaItemIndex
                if (outIndex == C.INDEX_UNSET || inIndex == C.INDEX_UNSET || inIndex >= primary.mediaItemCount) continue
                val outItem = primary.getMediaItemAt(outIndex)
                val inItem = primary.getMediaItemAt(inIndex)
                val duration = primary.duration.takeIf { it != C.TIME_UNSET && it > 3_000L } ?: continue
                val remaining = duration - primary.currentPosition
                if (remaining > 22_000L) continue

                val key = PlanCacheKey(outItem.mediaId, inItem.mediaId, settings.transitionMode, settings.crossfadeSeconds)
                if (cachedKey != key && fetchingKey != key) {
                    fetchingKey = key
                    scope.launch {
                        val computed = withContext(Dispatchers.IO) {
                            computeTransitionPlan(outItem, inItem, duration, settings.transitionMode, settings.crossfadeSeconds)
                        }
                        if (fetchingKey == key) {
                            cachedKey = key
                            cachedPlan = computed
                            fetchingKey = null
                            CadenceLog.i(
                                "AutoMix",
                                "plan ${outItem.mediaId}->${inItem.mediaId} kind=${computed.kind} dur=${computed.durationMs}ms fadeOutAt=${computed.fadeOutStartMs}ms speed=${computed.speedRatio} reason=${computed.reason}",
                            )
                        }
                    }
                }

                val plan = if (cachedKey == key) cachedPlan else null
                if (plan == null || plan.kind == TransitionKind.GAPLESS || plan.durationMs <= 0L) continue

                if (preCuedForKey != key && primary.currentPosition >= (plan.fadeOutStartMs - 4_000L).coerceAtLeast(0L)) {
                    preCuedForKey = key
                    secondary.setMediaItem(inItem, plan.fadeInOffsetMs)
                    secondary.playWhenReady = false
                    secondary.volume = 0f
                    secondary.playbackParameters = PlaybackParameters(plan.speedRatio)
                    secondary.prepare()
                }

                if (preCuedForKey == key && primary.currentPosition >= plan.fadeOutStartMs) {
                    overlapping = true
                    overlapOutIndex = outIndex
                    overlapInIndex = inIndex
                    overlapPlan = plan
                    overlapStartWallMs = android.os.SystemClock.elapsedRealtime()
                    overlapPausedAtMs = 0L
                    secondary.volume = 0f
                    secondary.playWhenReady = true
                    CadenceLog.i("AutoMix", "start ${plan.kind} ${outItem.mediaId}->${inItem.mediaId} dur=${plan.durationMs}ms")
                }
            }
        }
    }

    private suspend fun computeTransitionPlan(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        mode: TransitionMode,
        crossfadeSeconds: Int,
    ): TransitionPlan {
        val fallbackMs = crossfadeSeconds.coerceIn(1, 12) * 1_000L
        if (mode == TransitionMode.Crossfade) {
            return AutoMixPlanner.plan(
                outAnalysis = null,
                inAnalysis = null,
                outDurationMs = outDurationMs,
                inDurationMs = outDurationMs,
                sameAlbumConsecutive = false,
                fallbackCrossfadeMs = fallbackMs,
            )
        }
        val catalog = container.backend.catalog
        val outTrack = catalog.tracks.firstOrNull { it.track.id == outItem.mediaId }
        val inTrack = catalog.tracks.firstOrNull { it.track.id == inItem.mediaId }
        val sameAlbum = outTrack != null && inTrack != null &&
            outTrack.album.id.isNotBlank() && outTrack.album.id == inTrack.album.id
        if (sameAlbum) {
            return AutoMixPlanner.plan(null, null, outDurationMs, outDurationMs, sameAlbumConsecutive = true, fallbackCrossfadeMs = fallbackMs)
        }
        val outAnalysis = runCatching { catalog.audioAnalysis(outItem.mediaId) }.getOrNull()
        val inAnalysis = runCatching { catalog.audioAnalysis(inItem.mediaId) }.getOrNull()
        val inDurationMs = inTrack?.track?.durationMs?.takeIf { it > 0L } ?: outDurationMs
        return AutoMixPlanner.plan(
            outAnalysis = outAnalysis,
            inAnalysis = inAnalysis,
            outDurationMs = outDurationMs,
            inDurationMs = inDurationMs,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = fallbackMs,
        )
    }

    private fun swapDecks(
        oldPrimary: ExoPlayer,
        newPrimary: ExoPlayer,
        targetInIndex: Int,
        onDeckSwapped: () -> Unit,
    ) {
        val count = oldPrimary.mediaItemCount
        val before = (0 until targetInIndex.coerceAtMost(count)).map(oldPrimary::getMediaItemAt)
        val after = ((targetInIndex + 1).coerceAtMost(count) until count).map(oldPrimary::getMediaItemAt)
        val repeatMode = oldPrimary.repeatMode
        val shuffleEnabled = oldPrimary.shuffleModeEnabled

        oldPrimary.pause()
        oldPrimary.clearMediaItems()
        oldPrimary.volume = 1f
        oldPrimary.playbackParameters = PlaybackParameters.DEFAULT

        newPrimary.volume = 1f
        newPrimary.playbackParameters = PlaybackParameters.DEFAULT
        newPrimary.repeatMode = repeatMode
        newPrimary.shuffleModeEnabled = shuffleEnabled
        if (before.isNotEmpty()) newPrimary.addMediaItems(0, before)
        if (after.isNotEmpty()) newPrimary.addMediaItems(after)

        activePlayer = newPrimary
        secondaryPlayer = oldPrimary
        session?.setPlayer(newPrimary)
        onDeckSwapped()
        CadenceLog.i("AutoMix", "deck swapped -> index=${newPrimary.currentMediaItemIndex} total=${newPrimary.mediaItemCount}")
    }

    private fun attachDeckQueueListeners(player: ExoPlayer, update: () -> Unit) {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (p !== activePlayer) return
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) update()
            }
        })
    }

    /**
     * Keeps the stream cache pointed at the tracks around the playback position: the playing
     * one is completed, and the opening of the next and the previous is prepared as the user's
     * settings ask, so a transition or a skip never waits on the network or on a key.
     */
    private fun attachQueueFollower(streams: StreamCache): () -> Unit {
        fun update() {
            val player = activePlayer ?: return
            fun itemWithScheme(index: Int, scheme: String): String? = index
                .takeIf { it != C.INDEX_UNSET && it < player.mediaItemCount }
                ?.let(player::getMediaItemAt)
                ?.takeIf { it.localConfiguration?.uri?.scheme == scheme }
                ?.mediaId
            fun streamed(index: Int): String? = itemWithScheme(index, StreamPlayback.SCHEME)
            fun webStreamed(index: Int): String? = itemWithScheme(index, app.cadence.backend.engine.stream.WebPlayback.SCHEME)
            val s = container.settings.state.value
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            val config = streamConfigFor(s, metered)
            // The queue either way from the playing song, nearest first, in the order it will play.
            fun queue(step: (Int) -> Int, picker: (Int) -> String?): List<String> {
                val ids = ArrayList<String>()
                var index = step(player.currentMediaItemIndex)
                while (index != C.INDEX_UNSET && ids.size < WINDOW_TRACKS) {
                    picker(index)?.let(ids::add)
                    index = step(index)
                }
                return ids
            }
            val timeline = player.currentTimeline
            val shuffled = player.shuffleModeEnabled
            // A queue that was only restored at launch downloads nothing until it is played.
            val idle = player.playbackState == Player.STATE_IDLE && !player.playWhenReady
            val aacCache = app.cadence.backend.engine.stream.WebPlayback.aacCache(applicationContext)
            if (idle) {
                streams.setWindow(null, null, null, config)
                aacCache.setWindow(emptyList(), config.cacheBytes)
            } else {
                streams.setWindow(
                    current = streamed(player.currentMediaItemIndex),
                    next = streamed(player.nextMediaItemIndex),
                    previous = streamed(player.previousMediaItemIndex),
                    config = config,
                    ahead = if (config.aheadBytes > 0 && !timeline.isEmpty) queue({ timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled) }, ::streamed) else emptyList(),
                    behind = if (config.behindBytes > 0 && !timeline.isEmpty) queue({ timeline.getPreviousWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled) }, ::streamed) else emptyList(),
                )
                val webTargets = buildList {
                    webStreamed(player.currentMediaItemIndex)?.let(::add)
                    if (config.nextSeconds != 0) webStreamed(player.nextMediaItemIndex)?.let(::add)
                    if (config.previousSeconds != 0) webStreamed(player.previousMediaItemIndex)?.let(::add)
                    if (config.aheadBytes > 0 && !timeline.isEmpty) {
                        addAll(queue({ timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled) }, ::webStreamed))
                    }
                }
                aacCache.setWindow(webTargets, config.cacheBytes)
            }
        }
        scope.launch {
            container.settings.state
                .map { it to streamWindowKey(it) }
                .distinctUntilChanged { a, b -> a.second == b.second }
                .collect { (s, _) ->
                    // The resolver reads the quality from DownloadEngine, which another collector
                    // updates; apply it first so the window is not recomputed with the old one.
                    app.cadence.backend.engine.DownloadEngine.configure(s.toDownloadPreferences())
                    update()
                }
        }
        // Leaving Wi-Fi for mobile data (or back) changes how much the window may fill. No player
        // event fires for that, so the network is watched directly; only a change of metered-ness counts.
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity == null) return ::update
        var lastMetered: Boolean? = null
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            fun check() {
                val metered = connectivity.isActiveNetworkMetered
                if (metered != lastMetered) {
                    val first = lastMetered == null
                    lastMetered = metered
                    if (!first) {
                        CadenceLog.i("stream", "network metered=$metered; window recomputed")
                        update()
                    }
                }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = check()
            override fun onLost(network: android.net.Network) = check()
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
            networkCallback = callback
        }.onFailure { CadenceLog.e("stream", "cannot watch the network", it) }
        return ::update
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** Whether a USB audio output is connected; read by the DSP sink on every buffer, so kept in a field. */
    @Volatile private var usbOutput = false
    private var deviceCallback: android.media.AudioDeviceCallback? = null

    /**
     * Tracks USB outputs. With bit-perfect on and a USB DAC connected, the DSP stages step aside so
     * the DAC receives the decoder's samples untouched (see [dspBypassed]).
     */
    private fun watchUsbOutput() {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        fun refresh() {
            usbOutput = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { isUsbOutput(it.type) }
        }
        refresh()
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = refresh()
        }
        audio.registerAudioDeviceCallback(callback, null)
        deviceCallback = callback
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        networkCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        scope.cancel()
        deviceCallback?.let { cb -> runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(cb) } }
        deviceCallback = null
        session?.run { release() }
        session = null
        activePlayer?.release()
        activePlayer = null
        secondaryPlayer?.release()
        secondaryPlayer = null
        coverLoader?.release()
        coverLoader = null
        super.onDestroy()
    }
}

/**
 * Writes the playback pipeline's milestones to the app log: which decoder is in use, every state
 * change and transition, and anything that interrupts audio. This is the record that shows
 * whether a transition was seamless (no BUFFERING between two items, no underrun).
 */
@OptIn(UnstableApi::class)
private class PlaybackLog(private val context: android.content.Context) : AnalyticsListener {
    private fun log(message: String) = CadenceLog.i("player", message)

    private fun at(t: AnalyticsListener.EventTime) = "item=${t.currentWindowIndex} pos=${t.currentPlaybackPositionMs}ms buffered=${t.totalBufferedDurationMs}ms"

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        log("decoder=$decoderName init=${initializationDurationMs}ms")

    /**
     * What the app hands to Android: the AudioTrack's rate and encoding. A rate equal to the
     * source's and float (or 24/32-bit) encoding mean the app did not resample or truncate. The
     * system mixer's own rate is logged next to it: when the two differ, Android resamples after
     * the app, which no app can avoid on the built-in output (only a USB DAC in bit-perfect mode).
     */
    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
        val encoding = when (audioTrackConfig.encoding) {
            C.ENCODING_PCM_FLOAT -> "float32"
            C.ENCODING_PCM_16BIT -> "pcm16"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "pcm24"
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "pcm32"
            else -> "encoding=${audioTrackConfig.encoding}"
        }
        val mixer = runCatching {
            context.getSystemService(android.media.AudioManager::class.java)
                ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        }.getOrNull()
        log("audiotrack rate=${audioTrackConfig.sampleRate}Hz $encoding offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling} systemMixer=${mixer ?: "?"}Hz")
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
        log("input ${format.sampleMimeType} ${format.sampleRate}Hz ch=${format.channelCount} reuse=${decoderReuseEvaluation?.result}")

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val name = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
        log("state=$name ${at(eventTime)}")
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) = log("playing=$isPlaying ${at(eventTime)}")

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        val why = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            else -> reason.toString()
        }
        log("transition reason=$why to=${mediaItem?.mediaId} '${mediaItem?.mediaMetadata?.title}' ${at(eventTime)}")
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        log("discontinuity reason=$reason ${oldPosition.mediaItemIndex}@${oldPosition.positionMs}ms -> ${newPosition.mediaItemIndex}@${newPosition.positionMs}ms")

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
        log("UNDERRUN buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms ${at(eventTime)}")

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
        CadenceLog.e("player", "ERROR ${error.errorCodeName} ${at(eventTime)}", error)

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) =
        CadenceLog.e("player", "audio sink error", audioSinkError)

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
        CadenceLog.e("player", "audio codec error", audioCodecError)
}

/** How far along the queue the cache window looks in each direction. */
private const val WINDOW_TRACKS = 60

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class AppleWidevineDrmCallback(
    private val context: android.content.Context,
    private val trackId: String,
) : androidx.media3.exoplayer.drm.MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: java.util.UUID,
        request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest,
    ): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
        val url = "${request.defaultUrl}&signedRequest=${String(request.data, Charsets.UTF_8)}"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        return androidx.media3.exoplayer.drm.MediaDrmCallback.Response.Builder(bytes).build()
    }

    override fun executeKeyRequest(
        uuid: java.util.UUID,
        request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest,
    ): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
        val bytes = app.cadence.backend.engine.stream.WebPlayback.exchangeLicense(context, trackId, request.data)
        return androidx.media3.exoplayer.drm.MediaDrmCallback.Response.Builder(bytes).build()
    }
}
