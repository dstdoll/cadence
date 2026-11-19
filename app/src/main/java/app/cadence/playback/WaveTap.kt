@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.cadence.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.cadence.backend.wave.BandAnalyzer
import app.cadence.backend.wave.LiveWave
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** What the analyser saw in the 23 ms block being heard now: eight bands, level and bass hit, 0..1. */
class WaveLevels(val bands: FloatArray, val level: Float, val hit: Float) {
    companion object { val Silent = WaveLevels(FloatArray(BandAnalyzer.BANDS), 0f, 0f) }
}

/**
 * Where the player's audio analysis meets the UI. The playback service writes, Now Playing reads;
 * both live in the same process. Only numbers pass through here: the PCM never leaves the tap.
 *
 * The waveform is owned here, not by a screen, so it keeps filling in while the player is closed.
 */
class WaveHub {
    private val _levels = MutableStateFlow(WaveLevels.Silent)
    val levels: StateFlow<WaveLevels> = _levels

    private val _waveVersion = MutableStateFlow(0)
    /** Increments when [waveLevels] changes (new slices measured, or another track). */
    val waveVersion: StateFlow<Int> = _waveVersion

    @Volatile private var wave = LiveWave()
    private var waveKey: Any? = null
    /** Heights 0..255 for the track that is playing; 0 = not measured yet. */
    val waveLevels: IntArray get() = wave.levels

    private val lock = Any()
    private var power = 0.0
    private var blocks = 0.0

    /** A block has just become audible. */
    internal fun heard(bands: FloatArray, level: Float, hit: Float, energyDb: Float) {
        _levels.value = WaveLevels(bands, level, hit)
        synchronized(lock) {
            power += 10.0.pow(energyDb / 10.0)
            blocks += 1.0
        }
    }

    internal fun silence() { _levels.value = WaveLevels.Silent }

    /**
     * Driven by the playback service a few times per second: credits the power heard since the
     * last call to the part of [key]'s timeline that was playing.
     */
    fun tick(nowUs: Long, key: Any?, positionUs: Long, lengthUs: Long, speed: Float, playing: Boolean) {
        if (key != waveKey) {
            waveKey = key
            wave = LiveWave()
            synchronized(lock) { power = 0.0; blocks = 0.0 }
            _waveVersion.value++
        }
        if (!playing || key == null) { wave.cut(); return }
        synchronized(lock) {
            if (wave.sample(nowUs, positionUs, lengthUs, speed.toDouble(), power, blocks)) { power = 0.0; blocks = 0.0 }
        }
        if (wave.paint(nowUs, lengthUs)) _waveVersion.value++
    }
}

/**
 * Feeds PCM to [BandAnalyzer] in 1024-sample blocks and holds each result until the audio output
 * has actually reached it. The sink is handed audio well before it is heard (its buffer), so
 * publishing at analysis time would make every visual lead the sound.
 */
class WaveTap(private val hub: WaveHub) {
    private class Pending(val atUs: Long, val bands: FloatArray, val level: Float, val hit: Float, val energyDb: Float)

    private var analyzer: BandAnalyzer? = null
    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val block = FloatArray(BandAnalyzer.HOP)
    private var filled = 0
    private val frame = BandAnalyzer.Frame()
    private val pending = ArrayDeque<Pending>()

    fun configure(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz.coerceAtLeast(1)
        analyzer = BandAnalyzer(sampleRate)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        reset()
    }

    fun reset() {
        filled = 0
        pending.clear()
        hub.silence()
    }

    /** Analyses [buffer] through a read-only view; [presentationUs] is the time of its first frame. */
    fun handle(buffer: ByteBuffer, presentationUs: Long) {
        val analyzer = analyzer ?: return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return // other encodings are not analysed; the bar stays a flat line
        }
        val pcm = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = bytesPerSample * channels
        var frames = 0L
        while (pcm.remaining() >= frameBytes) {
            var mono = 0f
            for (c in 0 until channels) mono += if (bytesPerSample == 2) pcm.short / 32768f else pcm.float
            block[filled++] = mono / channels
            frames++
            if (filled == block.size) {
                analyzer.process(block, frame)
                filled = 0
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(
                    Pending(presentationUs + frames * 1_000_000L / sampleRate, frame.bands.copyOf(), frame.level, frame.hit, frame.energyDb),
                )
            }
        }
    }

    /** The output has played up to [playedUs]: everything analysed before that is now audible. */
    fun played(playedUs: Long) {
        while (true) {
            val next = pending.firstOrNull() ?: return
            if (next.atUs > playedUs) return
            pending.removeFirst()
            hub.heard(next.bands, next.level, next.hit, next.energyDb)
        }
    }

    private companion object { const val MAX_PENDING = 512 } // about 12 s of blocks
}

/**
 * Passes everything through to [sink] and shows each PCM buffer to [tap] once. Sitting at the
 * sink (rather than in the audio-processor chain) means it also sees float output, which
 * bypasses processors, and it can ask the sink how far the output has really played.
 */
class WaveAudioSink(sink: AudioSink, private val tap: WaveTap) : ForwardingAudioSink(sink) {
    private var lastPresentationUs = Long.MIN_VALUE

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format: Format = config.format
        tap.configure(format.sampleRate, format.channelCount, format.pcmEncoding)
        lastPresentationUs = Long.MIN_VALUE
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The same buffer is offered again until the sink has room for it; analyse it only once.
        if (presentationTimeUs != lastPresentationUs) {
            lastPresentationUs = presentationTimeUs
            tap.handle(buffer, presentationTimeUs)
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val playedUs = super.getCurrentPositionUs(false)
        if (playedUs != AudioSink.CURRENT_POSITION_NOT_SET) tap.played(playedUs)
        return handled
    }

    override fun flush() {
        lastPresentationUs = Long.MIN_VALUE
        tap.reset()
        super.flush()
    }
}
