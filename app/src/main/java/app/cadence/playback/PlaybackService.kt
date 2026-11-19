package app.cadence.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.cadence.backend.engine.CadenceLog
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        val mediaSourceFactory = DefaultMediaSourceFactory(CadenceDataSourceFactory(this, streams))
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
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Streaming keeps downloading with the screen off, so the network is held awake too.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        CadenceLog.i("player", "alac=native float=${useFloatOutput(container.settings.state.value)}")
        player.addAnalyticsListener(PlaybackLog(applicationContext))
        followQueue(player, streams)
        // The waveform is credited to the position that is audible, whether or not a screen shows it.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(50)
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                container.wave.tick(
                    nowUs = System.nanoTime() / 1000,
                    key = player.currentMediaItem?.mediaId,
                    positionUs = player.currentPosition * 1000,
                    lengthUs = duration * 1000,
                    speed = player.playbackParameters.speed,
                    playing = player.isPlaying,
                )
            }
        }

        scope.launch {
            container.settings.state.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it }
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
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(ControllerPolicy(packageName))
            .setBitmapLoader(coverLoader!!)
            .build()
    }

    /**
     * Keeps the stream cache pointed at the tracks around the playback position: the playing
     * one is completed, and the opening of the next and the previous is prepared as the user's
     * settings ask, so a transition or a skip never waits on the network or on a key.
     */
    private fun followQueue(player: ExoPlayer, streams: StreamCache) {
        fun update() {
            fun streamed(index: Int): String? = index
                .takeIf { it != C.INDEX_UNSET && it < player.mediaItemCount }
                ?.let(player::getMediaItemAt)
                ?.takeIf { it.localConfiguration?.uri?.scheme == StreamPlayback.SCHEME }
                ?.mediaId
            val s = container.settings.state.value
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            val config = streamConfigFor(s, metered)
            // The queue either way from the playing song, nearest first, in the order it will play.
            fun queue(step: (Int) -> Int): List<String> {
                val ids = ArrayList<String>()
                var index = step(player.currentMediaItemIndex)
                while (index != C.INDEX_UNSET && ids.size < WINDOW_TRACKS) {
                    streamed(index)?.let(ids::add)
                    index = step(index)
                }
                return ids
            }
            val timeline = player.currentTimeline
            val shuffled = player.shuffleModeEnabled
            // A queue that was only restored at launch downloads nothing until it is played.
            val idle = player.playbackState == Player.STATE_IDLE && !player.playWhenReady
            if (idle) streams.setWindow(null, null, null, config)
            else streams.setWindow(
                current = streamed(player.currentMediaItemIndex),
                next = streamed(player.nextMediaItemIndex),
                previous = streamed(player.previousMediaItemIndex),
                config = config,
                ahead = if (config.aheadBytes > 0 && !timeline.isEmpty) queue { timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled) } else emptyList(),
                behind = if (config.behindBytes > 0 && !timeline.isEmpty) queue { timeline.getPreviousWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled) } else emptyList(),
            )
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) update()
            }
        })
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
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return
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
        session?.run { player.release(); release() }
        session = null
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
