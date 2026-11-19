package app.cadence.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import app.cadence.playback.dsp.EqEngine
import app.cadence.playback.dsp.ToneEngine
import app.cadence.state.settings.ChannelConfig
import app.cadence.state.settings.DitherConfig
import app.cadence.state.settings.EqConfig
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.RgMode
import app.cadence.state.settings.ToneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Smooth changes, the limiter, auto headroom and measured replay gain, on real PCM through the sink. */
class DspEngineV2Test {
    private val sr = 44_100
    /** Everything the inner sink was handed, in order. */
    private val played = java.io.ByteArrayOutputStream()

    /** An inner sink that takes every buffer whole and records it. */
    private val inner = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
        if (method.name == "handleBuffer") {
            val b = args[0] as ByteBuffer
            ByteArray(b.remaining()).also { b.get(it) }.let(played::write)
        }
        when (method.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as AudioSink

    /** The first channel of everything played so far (16-bit stereo), full scale 1. */
    private fun playedLeft(): DoubleArray {
        val b = ByteBuffer.wrap(played.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(b.remaining() / 4) { b.getShort(it * 4) / 32768.0 }
    }

    private fun settings(
        tone: ToneConfig = ToneConfig(),
        eq: EqConfig = EqConfig(),
        rg: ReplayGainConfig = ReplayGainConfig(),
    ) = DspSettings(rg, tone, eq, ChannelConfig(), DitherConfig())

    private fun pcmConfig(channels: Int = 2, item: MediaItem? = null, durationMs: Long = 0): AudioSink.AudioSinkConfig {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(sr).setChannelCount(channels).build()
        val builder = AudioSink.AudioSinkConfig.Builder(format)
        if (item != null) {
            val timeline = SinglePeriodTimeline(durationMs * 1000, true, false, false, null, item)
            builder.setTimeline(timeline).setMediaPeriodId(MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)))
        }
        return builder.build()
    }

    /** Streams [seconds] of a sine through [sink] in 10 ms buffers; returns the first channel's output, full scale 1. */
    private fun stream(sink: DspAudioSink, seconds: Double, amp: Double, freq: Double = 1000.0, channels: Int = 2, start: Int = 0, onBuffer: (Int) -> Unit = {}): DoubleArray {
        val frames = (sr * seconds).toInt()
        val out = DoubleArray(frames)
        var n = 0
        var index = 0
        while (n < frames) {
            onBuffer(index++)
            val count = minOf(441, frames - n)
            val b = ByteBuffer.allocate(count * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val s = (amp * 32767 * sin(2 * PI * freq * (start + n + i) / sr)).toInt().toShort()
                repeat(channels) { b.putShort(s) }
            }
            b.flip()
            sink.handleBuffer(b, ((start + n) * 1_000_000L) / sr, 1)
            for (i in 0 until count) out[n + i] = b.getShort(i * 2 * channels) / 32768.0
            n += count
        }
        return out
    }

    private fun maxStep(x: DoubleArray, from: Int, to: Int) = (from + 1 until to).maxOf { abs(x[it] - x[it - 1]) }

    /**
     * The largest sample step when the settings go from [before] to [after] mid-song. At 1012.5 Hz each
     * 441-frame buffer advances the sine by 10.125 cycles, so changing before buffers 30 to 37 lands the
     * change on eight phases an eighth of a cycle apart, peaks included.
     */
    private fun worstStep(before: DspSettings, after: DspSettings): Double = (30..37).maxOf { at ->
        var cfg = before
        val sink = DspAudioSink(inner) { cfg }.apply { configure(pcmConfig()) }
        val out = stream(sink, 0.5, 0.25, freq = FREQ) { i -> if (i == at) cfg = after }
        maxStep(out, 0, out.size)
    }

    /** The largest sample step of a steady sine of amplitude [amp] at [FREQ], with 5% to spare. */
    private fun naturalStep(amp: Double) = amp * 2 * PI * FREQ / sr * 1.05

    @Test
    fun changingSettingsMidSongDoesNotClick() {
        val plus3 = settings(eq = EqConfig(enabled = true, bands = EqConfig.defaultBands.map { if (it.freq == 1000) it.copy(gainDb = 3f) else it }, autoHeadroom = false))
        // The 1 kHz band jumps from +3 dB to -9 dB: a 12 dB step in level.
        val minus9 = plus3.copy(eq = plus3.eq.copy(bands = plus3.eq.bands.map { if (it.freq == 1000) it.copy(gainDb = -9f) else it }))
        val step = worstStep(plus3, minus9)
        assertTrue("largest step $step vs the louder sine's own ${naturalStep(0.25 * 1.42)}", step <= naturalStep(0.25 * 1.42))
    }

    @Test
    fun enablingProcessingMidSongDoesNotClick() {
        // Tone with auto headroom changes the level at once; with "prevent clipping" on (the default) the
        // limiter engages at the same moment, and its delay must already be running.
        val step = worstStep(settings(), settings(tone = ToneConfig(enabled = true, bassDb = 6f, trebleDb = 6f)))
        assertTrue("largest step $step", step <= naturalStep(0.25))
    }

    @Test
    fun withEveryStageOffTheLimiterOnlyDelaysAndTheTailIsPlayedAtTheEnd() {
        played.reset()
        val sink = DspAudioSink(inner) { settings() }.apply { configure(pcmConfig()) }
        val input = stream(DspAudioSink(inner) { settings(rg = ReplayGainConfig(preventClip = false)) }.apply { configure(pcmConfig()) }, 0.2, 0.999)
        played.reset()
        stream(sink, 0.2, 0.999) // peaks above the limiter's threshold, left alone with nothing boosting them
        sink.playToEndOfStream()
        val out = playedLeft()
        val delay = out.size - input.size
        assertEquals(88, delay) // 2 ms at 44.1 kHz
        for (i in input.indices) assertEquals(input[i], out[i + delay], 0.0)
    }

    @Test
    fun theNextSongOfAGaplessRunFollowsWithoutAGap() {
        played.reset()
        val cfg = settings(eq = EqConfig(enabled = true, bands = EqConfig.defaultBands.map { it.copy(gainDb = 3f) }))
        val sink = DspAudioSink(inner) { cfg }.apply { configure(pcmConfig(item = MediaItem.Builder().setMediaId("a").build(), durationMs = 300)) }
        val first = (sr * 0.3).toInt()
        stream(sink, 0.3, 0.25, freq = FREQ)
        sink.configure(pcmConfig(item = MediaItem.Builder().setMediaId("b").build(), durationMs = 300))
        stream(sink, 0.3, 0.25, freq = FREQ, start = first) // the same sine carries on into the next song
        val out = playedLeft()
        val around = maxStep(out, first - 500, first + 500)
        assertTrue("step $around at the song change", around <= naturalStep(0.25 * 1.42))
        assertTrue("no silence spliced in", (first - 200 until first + 200).none { i -> (i until i + 8).all { out[it] == 0.0 } })
    }

    /** One buffer of [frames] stereo frames of a 1 kHz sine at a quarter of full scale. */
    private fun sineBuffer(frames: Int): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val s = (8192 * sin(2 * PI * 1000 * i / sr)).toInt().toShort()
            b.putShort(s); b.putShort(s)
        }
        return b.flip() as ByteBuffer
    }

    @Test
    fun aReadOnlyDecoderBufferIsProcessedThroughACopy() {
        // MediaCodec decoders (the Dolby one among them) hand out read-only buffers.
        val boost = settings(tone = ToneConfig(enabled = true, bassDb = 6f, trebleDb = 6f))
        played.reset()
        DspAudioSink(inner) { boost }.apply { configure(pcmConfig()) }.handleBuffer(sineBuffer(4410), 0, 1)
        val writable = played.toByteArray()
        played.reset()
        val readOnly = sineBuffer(4410).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val untouched = ByteArray(readOnly.remaining()).also { readOnly.duplicate().get(it) }
        DspAudioSink(inner) { boost }.apply { configure(pcmConfig()) }.handleBuffer(readOnly, 0, 1)
        assertTrue("the copy is processed exactly as a writable buffer is", writable.contentEquals(played.toByteArray()))
        assertTrue(!writable.contentEquals(untouched))
    }

    @Test
    fun aReadOnlyBufferReofferedAfterAPartialWriteContinuesWithTheSameCopy() {
        val handed = ArrayList<ByteBuffer>()
        val out = java.io.ByteArrayOutputStream()
        var calls = 0
        // Takes half of the first buffer and reports it unfinished, then the rest.
        val half = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            if (method.name == "handleBuffer") {
                val b = args[0] as ByteBuffer
                handed += b
                val n = if (calls++ == 0) b.remaining() / 2 else b.remaining()
                ByteArray(n).also { b.get(it) }.let(out::write)
                return@newProxyInstance !b.hasRemaining()
            }
            when (method.returnType) { java.lang.Boolean.TYPE -> true; Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; else -> null }
        } as AudioSink
        val boost = settings(tone = ToneConfig(enabled = true, bassDb = 6f, trebleDb = 6f))
        val sink = DspAudioSink(half) { boost }.apply { configure(pcmConfig()) }
        val readOnly = sineBuffer(4410).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(!sink.handleBuffer(readOnly, 0, 1))
        assertTrue(sink.handleBuffer(readOnly, 0, 1))
        assertTrue("the same copy both times", handed[0] === handed[1])
        played.reset()
        DspAudioSink(inner) { boost }.apply { configure(pcmConfig()) }.handleBuffer(sineBuffer(4410), 0, 1)
        assertTrue("processed once, every byte delivered", played.toByteArray().contentEquals(out.toByteArray()))
    }

    @Test
    fun theLimiterKeepsABigBoostFromClipping() {
        val cfg = settings(tone = ToneConfig(enabled = true, bassDb = 12f, trebleDb = 12f), eq = EqConfig(autoHeadroom = false))
        val sink = DspAudioSink(inner) { cfg }.apply { configure(pcmConfig()) }
        val out = stream(sink, 1.0, 0.7, freq = 60.0) // +12 dB on a -3 dBFS bass tone would reach 2.8x full scale
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak", peak <= 10.0.pow(-0.5 / 20) + 1.0 / 32768)
    }

    @Test
    fun autoHeadroomMatchesTheLargestBoost() {
        val tone = ToneEngine(sr, 2, 6.0, 0.0)
        assertEquals(6.0, DspChain.peakBoostDb(tone, null, null, sr), 0.1)
        val cutsOnly = EqEngine(sr, 2, 0.0, EqConfig.defaultBands.map { app.cadence.playback.dsp.EqBand(it.freq.toDouble(), -4.0, it.q.toDouble()) })
        assertTrue(DspChain.peakBoostDb(null, cutsOnly, null, sr) < 0)
        assertEquals(6.02, DspChain.peakBoostDb(null, null, 2.0, sr), 0.01) // double width: up to +6 dB on side content
        // With auto headroom, a +6 dB bass boost on a full-scale bass tone stays under full scale without the limiter.
        val cfg = settings(tone = ToneConfig(enabled = true, bassDb = 6f), rg = ReplayGainConfig(preventClip = false))
        val sink = DspAudioSink(inner) { cfg }.apply { configure(pcmConfig()) }
        val out = stream(sink, 0.5, 0.95, freq = 40.0)
        assertTrue(out.maxOf { abs(it) } < 0.999)
    }

    private class MemoryStore : LoudnessStore {
        val map = HashMap<String, Loudness>()
        override fun get(trackId: String) = map[trackId]
        override fun put(trackId: String, loudness: Loudness) { map[trackId] = loudness }
        override fun albumLufs(album: String): Double? = map.values.filter { it.album == album }.takeIf { it.isNotEmpty() }
            ?.let { s -> 10 * kotlin.math.log10(s.sumOf { 10.0.pow(it.lufs / 10) } / s.size) }
    }

    @Test
    fun replayGainMeasuresASongThenLevelsItToMinus18Lufs() {
        val store = MemoryStore()
        val item = MediaItem.Builder().setMediaId("t1").setMediaMetadata(MediaMetadata.Builder().setAlbumTitle("A").setArtist("X").build()).build()
        val cfg = settings(rg = ReplayGainConfig(enabled = true))
        val first = DspAudioSink(inner, store) { cfg }.apply { configure(pcmConfig(item = item, durationMs = 12_000)) }
        val amp = 10.0.pow(-23.0 / 20)
        stream(first, 12.0, amp, freq = 997.0)
        val measured = assertNotNull(store.get("t1")).let { store.get("t1")!! }
        assertEquals(-23.0, measured.lufs, 0.2)
        assertEquals("A|X", measured.album)
        // Next play: levelled by -18 - (-23) = +5 dB.
        val second = DspAudioSink(inner, store) { cfg }.apply { configure(pcmConfig(item = item, durationMs = 12_000)) }
        val out = stream(second, 2.0, amp, freq = 997.0)
        val rms = sqrt(out.drop(sr).sumOf { it * it } / (out.size - sr))
        assertEquals(10.0.pow(5.0 / 20), rms / (amp / sqrt(2.0)), 0.03)
    }

    @Test
    fun aPartlyPlayedSongIsNotStored() {
        val store = MemoryStore()
        val item = MediaItem.Builder().setMediaId("t2").build()
        val sink = DspAudioSink(inner, store) { settings(rg = ReplayGainConfig(enabled = true)) }.apply { configure(pcmConfig(item = item, durationMs = 60_000)) }
        stream(sink, 12.0, 0.1)
        sink.configure(pcmConfig(item = MediaItem.Builder().setMediaId("t3").build(), durationMs = 60_000)) // skipped at 20%
        assertEquals(null, store.get("t2"))
    }

    @Test
    fun albumModeUsesTheAlbumsMeasuredSongs() {
        val dir = java.nio.file.Files.createTempDirectory("loud").toFile()
        val file = java.io.File(dir, "loudness.json")
        FileLoudnessStore(file).apply {
            put("a", Loudness(-20.0, 0.9, "Album|Artist"))
            put("b", Loudness(-26.0, 0.5, "Album|Artist"))
            put("c", Loudness(-10.0, 1.0, "Other|Artist"))
        }
        val reloaded = FileLoudnessStore(file)
        assertEquals(-22.04, reloaded.albumLufs("Album|Artist")!!, 0.01)
        assertEquals(-20.0, reloaded.get("a")!!.lufs, 0.0)
        assertEquals(null, reloaded.albumLufs("Nope"))
        dir.deleteRecursively()
    }

    @Test
    fun sixChannelAudioIsProcessed() {
        val cfg = settings(eq = EqConfig(enabled = true, bands = EqConfig.defaultBands.map { it.copy(gainDb = 2f) }))
        val sink = DspAudioSink(inner) { cfg }.apply { configure(pcmConfig(channels = 6)) }
        val out = stream(sink, 0.3, 0.2, channels = 6)
        assertTrue(out.any { it != 0.0 } && out.all { abs(it) < 1.0 })
    }

    private companion object {
        const val FREQ = 1012.5
    }
}
