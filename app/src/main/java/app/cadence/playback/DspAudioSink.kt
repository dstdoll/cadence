package app.cadence.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.cadence.playback.dsp.ChannelEngine
import app.cadence.playback.dsp.DitherEngine
import app.cadence.playback.dsp.EqBand
import app.cadence.playback.dsp.EqEngine
import app.cadence.playback.dsp.EqShape
import app.cadence.playback.dsp.LimiterEngine
import app.cadence.playback.dsp.LoudnessMeter
import app.cadence.playback.dsp.ReplayGainEngine
import app.cadence.playback.dsp.ToneEngine
import app.cadence.state.settings.ChannelConfig
import app.cadence.state.settings.DitherConfig
import app.cadence.state.settings.DitherType
import app.cadence.state.settings.EqConfig
import app.cadence.state.settings.EqFilterType
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.RgMode
import app.cadence.state.settings.ToneConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The DSP stages the sink reads fresh each buffer, applied in order: replay gain, tone, eq, channel,
 * then the limiter and, when quantizing to 16 bits, dither.
 */
data class DspSettings(
    val replayGain: ReplayGainConfig,
    val tone: ToneConfig,
    val eq: EqConfig,
    val channel: ChannelConfig,
    val dither: DitherConfig,
    /** Every stage steps aside, so the output is the decoder's samples untouched (bit-perfect USB). */
    val bypassed: Boolean = false,
)

/**
 * Bit-perfect means untouched: with it on and a USB output connected, no DSP stage runs. Bit-perfect
 * covers stereo and mono only, so the sink still processes multichannel streams (see [DspAudioSink]).
 */
internal fun dspBypassed(bitPerfectUsb: Boolean, usbOutputConnected: Boolean): Boolean = bitPerfectUsb && usbOutputConnected

/** Replay gain levels songs to this loudness (the ReplayGain 2.0 reference, -18 LUFS). */
internal const val TARGET_LUFS = -18.0

/** The song a sink configuration is for: its id, length and album key, from the player's timeline. */
internal data class TrackRef(val id: String, val durationMs: Long?, val album: String?)

/**
 * One fixed set of processing stages: replay gain, tone, equalizer and channel, after a headroom
 * gain that keeps the boosts of the filters from clipping. A null stage is skipped; a chain with no
 * stage and unity headroom changes nothing.
 */
internal class DspChain(
    private val rg: ReplayGainEngine?,
    private val tone: ToneEngine?,
    private val eq: EqEngine?,
    private val channel: ChannelEngine?,
    /** Linear gain before the stages (auto headroom); 1.0 for none. */
    val preGain: Double,
) {
    val active: Boolean = rg != null || tone != null || eq != null || channel != null || preGain != 1.0

    fun processFrame(frame: DoubleArray, channels: Int) {
        for (c in 0 until channels) {
            var y = frame[c] * preGain
            rg?.let { y = it.processSample(c, y) }
            tone?.let { y = it.processSample(c, y) }
            eq?.let { y = it.processSample(c, y) }
            frame[c] = y
        }
        channel?.processFrame(frame)
    }

    /** Continues [old]'s filter memory, so the new settings do not restart the filters (no click). */
    fun takeStateFrom(old: DspChain) {
        tone?.copyStateFrom(old.tone)
        eq?.copyStateFrom(old.eq)
    }

    fun reset() {
        tone?.reset()
        eq?.reset()
        channel?.reset()
    }

    companion object {
        val IDLE = DspChain(null, null, null, null, 1.0)

        /**
         * The chain for [cfg]. [levellingDb] is the replay gain from the song's measured loudness, or
         * null when it has not been measured yet (the "no info" gain applies then).
         */
        fun build(cfg: DspSettings, sampleRate: Int, channels: Int, pcm: Boolean, levellingDb: Double?): DspChain {
            if (!pcm || cfg.bypassed) return IDLE
            val rc = cfg.replayGain
            // The limiter after the chain catches boosted peaks, so the stage itself never hard-clips.
            val rg = if (rc.enabled) {
                ReplayGainEngine(ReplayGainEngine.effectiveDb(levellingDb, rc.noInfoDb.toDouble(), rc.preampDb.toDouble()), preventClip = false)
                    .takeIf { it.active }
            } else null
            val tc = cfg.tone
            val tone = if (tc.enabled) ToneEngine(sampleRate, channels, tc.bassDb.toDouble(), tc.trebleDb.toDouble()).takeIf { it.active } else null
            val ec = cfg.eq
            val eq = if (ec.enabled) {
                EqEngine(sampleRate, channels, eqPreampDb(ec), eqBands(ec)).takeIf { it.active }
            } else null
            val cc = cfg.channel
            val channel = if (cc.enabled) ChannelEngine(channels, cc.balance.toDouble(), cc.width.toDouble()).takeIf { it.active } else null
            val headroomDb = if (ec.autoHeadroom) max(0.0, peakBoostDb(tone, eq, channel?.let { cc.width.toDouble() }, sampleRate)) else 0.0
            return DspChain(rg, tone, eq, channel, 10.0.pow(-headroomDb / 20.0))
        }

        /**
         * The equalizer's filters: the headphone correction first (when one is loaded), then the
         * graphic bands, so the bands adjust the corrected sound.
         */
        fun eqBands(ec: EqConfig): List<EqBand> =
            (ec.headphone?.filters.orEmpty() + ec.bands).map { b ->
                val shape = when (b.type) {
                    EqFilterType.Peaking -> EqShape.Peaking
                    EqFilterType.LowShelf -> EqShape.LowShelf
                    EqFilterType.HighShelf -> EqShape.HighShelf
                }
                EqBand(b.freq.toDouble(), b.gainDb.toDouble(), b.q.toDouble(), shape)
            }

        /**
         * The equalizer's preamp. A headphone profile's own preamp only applies with auto headroom off:
         * with it on, the measured headroom already takes the profile's boosts into account.
         */
        fun eqPreampDb(ec: EqConfig): Double =
            ec.preampDb.toDouble() + if (ec.autoHeadroom) 0.0 else (ec.headphone?.preampDb?.toDouble() ?: 0.0)

        /**
         * The largest gain the filters and the stereo widening can add, in dB, read from the combined
         * frequency response at 128 frequencies from 20 Hz to 20 kHz (or just under Nyquist). Replay
         * gain is a deliberate level change and is left to the limiter.
         */
        fun peakBoostDb(tone: ToneEngine?, eq: EqEngine?, width: Double?, sampleRate: Int): Double {
            val widening = if (width != null && width > 1.0) 20.0 * log10(width) else 0.0
            if (tone == null && eq == null) return widening
            val top = min(20_000.0, sampleRate * 0.45)
            var peak = Double.NEGATIVE_INFINITY
            for (i in 0 until 128) {
                val f = exp(ln(20.0) + (ln(top) - ln(20.0)) * i / 127)
                peak = max(peak, (tone?.magnitudeDb(f, sampleRate) ?: 0.0) + (eq?.magnitudeDb(f, sampleRate) ?: 0.0))
            }
            return peak + widening
        }
    }
}

/**
 * Applies the DSP stages to PCM at the **sink** level, so they also process float output (which
 * bypasses Media3's audio-processor chain). With every stage off the sink passes the samples through
 * untouched, byte for byte.
 *
 * Settings are read fresh every buffer. A change (a slider moving) builds a new [DspChain] that takes
 * over the old one's filter memory, and the output crossfades from the old chain to the new one over
 * [FADE_MS]; changes arriving during a fade wait for it to finish, then the newest one applies. So
 * dragging a control does not click. A new stream starts clean; the next song of a gapless run (the
 * same format again, no flush in between) gets its own level from its first sample while the filter
 * memory carries on, so nothing is cut or inserted between the two.
 *
 * With "prevent clipping" on, a look-ahead limiter follows the chain. Its 2 ms delay must not appear or
 * vanish while sound flows (that would splice in silence or cut audio out), so the limiter is only
 * created or dropped at a clean point (a new stream, a seek) and is otherwise just engaged while
 * processing and on standby when not; on standby it only delays, every sample exact. At the end of the
 * stream what it still holds is played out. With replay gain on, each song's loudness is measured
 * (BS.1770, before any processing) and stored once enough of it has played; from then on it is levelled
 * to [TARGET_LUFS].
 *
 * Multichannel audio (Dolby Atmos decoded to 5.1 or 7.1) is processed on every channel: replay gain, tone
 * and equalizer per channel, loudness with the BS.1770 channel weights, the limiter linked across all of
 * them; balance and width apply to stereo only. It is never bit-perfect, so the bypass does not apply to it.
 *
 * float32 and 16-bit PCM are handled (the two encodings this app's decoder produces); any other
 * encoding passes straight through. Samples are edited in place with absolute gets/puts, so the
 * buffer position the inner sink consumes from is never disturbed. A MediaCodec decoder's output (the
 * Dolby decoder's, for one) is read-only: it is copied, the copy processed and handed on instead.
 */
@OptIn(UnstableApi::class)
class DspAudioSink(
    sink: AudioSink,
    private val loudness: LoudnessStore? = null,
    private val config: () -> DspSettings,
) : ForwardingAudioSink(sink) {
    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var current = DspChain.IDLE
    private var fading: DspChain? = null
    private var fadeLeft = 0
    private var fadeLength = 1
    private var limiter: LimiterEngine? = null
    private var dither: DitherEngine? = null
    private var meter: LoudnessMeter? = null
    private var stored = false
    private var track: TrackRef? = null
    private var applied: DspSettings? = null
    private var lastPresentationUs = Long.MIN_VALUE
    private var lastBuffer: ByteBuffer? = null
    /** What was handed to the inner sink for [lastBuffer]: the buffer itself, or its processed copy. */
    private var lastOut: ByteBuffer? = null
    private var scratch: ByteBuffer? = null
    private var nextPresentationUs = 0L
    /** Whether a stream has been configured since the last reset. */
    private var configured = false
    /** No sound has gone out since the stream started or was flushed, so a delay may be introduced. */
    private var clean = true
    /** The limiter's last frames, being played out at the end of the stream. */
    private var tail: ByteBuffer? = null
    private var frame = DoubleArray(2)
    private var other = DoubleArray(2)

    private val pcm get() = encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_16BIT

    override fun configure(config: AudioSink.AudioSinkConfig) {
        finishMeasurement()
        val format: Format = config.format
        val rate = format.sampleRate.coerceAtLeast(1)
        val count = format.channelCount.coerceAtLeast(1)
        // The same format again without a reset: the next song of a gapless run, its sound continuous.
        val continuous = configured && applied != null && rate == sampleRate && count == channels && format.pcmEncoding == encoding
        sampleRate = rate
        channels = count
        encoding = format.pcmEncoding
        if (frame.size != channels) { frame = DoubleArray(channels); other = DoubleArray(channels) }
        fadeLength = max(1, (sampleRate * FADE_MS / 1000.0).roundToInt())
        track = trackOf(config)
        meter = null
        stored = false
        tail = null
        app.cadence.backend.engine.CadenceLog.i("player", if (continuous) "dsp next song, continuous" else "dsp new stream")
        if (continuous) {
            rebuildIfNeeded(settings(), boundary = true)
        } else {
            // A new stream starts clean: no fade, no limiter delay carried over.
            applied = null
            current = DspChain.IDLE
            fading = null
            limiter = null
            clean = true
            rebuildIfNeeded(settings())
        }
        configured = true
        lastPresentationUs = Long.MIN_VALUE
        lastBuffer = null
        lastOut = null
        super.configure(config)
    }

    private fun levellingDb(cfg: DspSettings): Double? {
        val t = track ?: return null
        val store = loudness ?: return null
        val lufs = if (cfg.replayGain.mode == RgMode.Album) t.album?.let(store::albumLufs) ?: store.get(t.id)?.lufs else store.get(t.id)?.lufs
        return lufs?.let { TARGET_LUFS - it }
    }

    /** Builds the chain for [cfg] when it changed; at a song [boundary] always, for the new song's level. */
    private fun rebuildIfNeeded(cfg: DspSettings, boundary: Boolean = false) {
        if (!boundary) {
            if (cfg == applied) return
            if (fading != null && !cfg.bypassed) return // the newest settings apply when this fade ends
        }
        val next = DspChain.build(cfg, sampleRate, channels, pcm, levellingDb(cfg))
        when {
            applied == null || cfg.bypassed -> {
                current = next
                fading = null
            }
            boundary -> { // a level change between songs applies from the new song's first sample
                next.takeStateFrom(current)
                current = next
                fading = null
            }
            else -> {
                next.takeStateFrom(current)
                fading = current
                current = next
                fadeLeft = fadeLength
            }
        }
        applied = cfg
        if (cfg.bypassed || !pcm) {
            meter = null
        } else if (meter == null && cfg.replayGain.enabled && track != null && loudness != null) {
            meter = LoudnessMeter(sampleRate, channels)
        }
        updateLimiter(cfg)
        val dc = cfg.dither
        dither = if (pcm && !cfg.bypassed && dc.enabled && dc.type != DitherType.None && encoding == C.ENCODING_PCM_16BIT) DitherEngine(dc.type, channels) else null
        log(cfg)
    }

    /** Creates or drops the limiter at clean points only (bit-perfect output drops it at once); engages it while processing. */
    private fun updateLimiter(cfg: DspSettings) {
        val wanted = pcm && !cfg.bypassed && cfg.replayGain.preventClip
        if (wanted && limiter == null && clean) limiter = LimiterEngine(sampleRate, channels)
        if (!wanted && limiter != null && (clean || cfg.bypassed || !pcm)) limiter = null
        limiter?.engaged = wanted && (current.active || fading != null)
    }

    private fun log(cfg: DspSettings) {
        val stages = buildList {
            if (current.preGain != 1.0) add("headroom %.1fdB".format(Locale.ROOT, 20 * log10(current.preGain)))
            if (cfg.replayGain.enabled) add("rg ${cfg.replayGain.mode}${levellingDb(cfg)?.let { " level=%+.1fdB".format(Locale.ROOT, it) } ?: " no-info"}")
            if (cfg.tone.enabled) add("tone")
            if (cfg.eq.enabled) add("eq")
            if (cfg.channel.enabled) add("channel")
            if (limiter?.engaged == true) add("limiter")
            if (dither != null) add("dither ${cfg.dither.type}")
            if (meter != null) add("measuring")
        }
        val fmt = if (encoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"
        app.cadence.backend.engine.CadenceLog.i(
            "player",
            when {
                cfg.bypassed -> "dsp bypassed (bit-perfect USB)"
                stages.isEmpty() -> "dsp off"
                else -> "dsp active [${stages.joinToString(", ")}] $fmt ${sampleRate}Hz${if (fading != null) " (crossfading)" else ""}"
            },
        )
    }

    /** The current settings; bit-perfect does not cover multichannel, so its bypass does not apply there. */
    private fun settings(): DspSettings = config().let { if (it.bypassed && channels > 2) it.copy(bypassed = false) else it }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val cfg = settings()
        rebuildIfNeeded(cfg)
        updateLimiter(cfg)
        val process = current.active || fading != null || limiter != null || dither != null
        // The same buffer is re-offered until the inner sink has room; process it only once. Two
        // different buffers can share a timestamp, so the buffer itself is part of the key.
        if (presentationTimeUs != lastPresentationUs || buffer !== lastBuffer) {
            lastPresentationUs = presentationTimeUs
            lastBuffer = buffer
            tail = null
            // The copy stands in for the buffer until it is consumed, re-offers included.
            val out = if (process && buffer.isReadOnly) copyOf(buffer) else buffer
            lastOut = out
            if (process || meter != null) apply(out, process)
            if (out.hasRemaining()) clean = false
            if (pcm) nextPresentationUs = presentationTimeUs + out.remaining() / frameBytes() * 1_000_000L / sampleRate
        }
        return super.handleBuffer(lastOut ?: buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /**
     * A writable copy of [buffer] in a reused scratch buffer. Reuse is safe: a new buffer only arrives
     * once the inner sink has consumed the previous one, or after a flush.
     */
    private fun copyOf(buffer: ByteBuffer): ByteBuffer {
        val need = buffer.remaining()
        val s = scratch?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { scratch = it }
        s.clear()
        s.put(buffer.duplicate())
        s.flip()
        return s
    }

    override fun playToEndOfStream() {
        // The limiter still holds the last 2 ms: play them out first. The renderer keeps calling this
        // until the sink has ended, so when the inner sink is full the rest goes on the next call.
        val l = limiter
        if (l != null && pcm) {
            val t = tail ?: limiterTail().also { tail = it }
            if (t.hasRemaining()) {
                super.handleBuffer(t, nextPresentationUs, 1)
                if (t.hasRemaining()) return
            }
        }
        super.playToEndOfStream()
    }

    /** Silence run through the chain and the limiter: it pushes out the audio still in the delay line. */
    private fun limiterTail(): ByteBuffer {
        val frames = limiter?.lookahead ?: 0
        val b = ByteBuffer.allocateDirect(frames * frameBytes()).order(ByteOrder.nativeOrder())
        apply(b, process = true, measure = false)
        return b
    }

    private fun frameBytes() = (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * channels

    private fun apply(buffer: ByteBuffer, process: Boolean, measure: Boolean = true) {
        val savedOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val float = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val frameBytes = bytesPerSample * channels
        val f = frame
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + frameBytes <= limit) {
            for (c in 0 until channels) {
                val idx = pos + c * bytesPerSample
                f[c] = if (float) buffer.getFloat(idx).toDouble() else buffer.getShort(idx) / 32768.0
            }
            if (measure) meter?.addFrame(f)
            if (process) {
                val old = fading
                if (old != null) {
                    System.arraycopy(f, 0, other, 0, channels)
                    current.processFrame(f, channels)
                    old.processFrame(other, channels)
                    val t = 1.0 - fadeLeft.toDouble() / fadeLength
                    for (c in 0 until channels) f[c] = other[c] * (1.0 - t) + f[c] * t
                    if (--fadeLeft <= 0) fading = null
                } else {
                    current.processFrame(f, channels)
                }
                limiter?.processFrame(f)
                for (c in 0 until channels) {
                    val idx = pos + c * bytesPerSample
                    if (float) {
                        buffer.putFloat(idx, f[c].toFloat())
                    } else {
                        val y = dither?.quantize(c, f[c]) ?: (f[c] * 32768.0).roundToInt().coerceIn(-32768, 32767)
                        buffer.putShort(idx, y.toShort())
                    }
                }
            }
            pos += frameBytes
        }
        buffer.order(savedOrder) // absolute puts left position/limit untouched
        // Store the measurement as soon as the song has played through, not only at the next song.
        val m = meter
        val duration = track?.durationMs
        if (m != null && !stored && duration != null && m.seconds * 1000 >= duration * 0.98) finishMeasurement()
    }

    /** Stores the song's loudness when enough of it was measured (80% of its length, 10 s at least). */
    private fun finishMeasurement() {
        val m = meter ?: return
        val t = track ?: return
        if (stored) return
        val duration = t.durationMs ?: return
        val lufs = m.integratedLufs() ?: return
        if (m.seconds < 10.0 || m.seconds * 1000 < duration * 0.8) return
        loudness?.put(t.id, Loudness(lufs, m.samplePeak, t.album))
        stored = true
        app.cadence.backend.engine.CadenceLog.i("player", "loudness measured ${"%.1f".format(Locale.ROOT, lufs)} LUFS over ${m.seconds.roundToInt()}s")
    }

    override fun flush() {
        lastPresentationUs = Long.MIN_VALUE
        lastBuffer = null
        lastOut = null
        current.reset()
        fading = null
        limiter?.reset()
        dither?.reset()
        // After a seek the filters restart, but what was measured is kept.
        meter?.resetFilters()
        clean = true
        tail = null
        super.flush()
    }

    override fun reset() {
        finishMeasurement()
        configured = false
        clean = true
        tail = null
        limiter = null
        super.reset()
    }

    private companion object {
        /** Crossfade length when settings change. */
        const val FADE_MS = 20.0

        fun trackOf(config: AudioSink.AudioSinkConfig): TrackRef? = runCatching {
            val periodId = config.mediaPeriodId ?: return null
            val timeline: Timeline = config.timeline
            if (timeline.isEmpty) return null
            val period = timeline.getPeriodByUid(periodId.periodUid, Timeline.Period())
            val window = timeline.getWindow(period.windowIndex, Timeline.Window())
            val item = window.mediaItem
            val meta = item.mediaMetadata
            val album = meta.albumTitle?.let { title -> "$title|${meta.albumArtist ?: meta.artist ?: ""}" }
            TrackRef(item.mediaId, window.durationMs.takeIf { it != C.TIME_UNSET && it > 0 }, album)
        }.getOrNull()
    }
}
