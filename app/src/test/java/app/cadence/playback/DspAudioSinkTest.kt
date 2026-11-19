package app.cadence.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import app.cadence.state.settings.ChannelConfig
import app.cadence.state.settings.DitherConfig
import app.cadence.state.settings.EqConfig
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.ToneConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The DSP sink on real PCM, in front of a sink that accepts everything. */
class DspAudioSinkTest {
    private val inner = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as AudioSink

    private val boost = DspSettings(
        ReplayGainConfig(), ToneConfig(enabled = true, bassDb = 6f, trebleDb = 6f), EqConfig(), ChannelConfig(), DitherConfig(),
    )

    private fun sink(channels: Int = 2, settings: () -> DspSettings): DspAudioSink {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44_100).setChannelCount(channels).build()
        return DspAudioSink(inner, config = settings).apply { configure(AudioSink.AudioSinkConfig.Builder(format).build()) }
    }

    /** One stereo buffer of a 1 kHz tone at a quarter of full scale. */
    private fun tone(frames: Int = 1024): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val s = (8192 * sin(2 * PI * 1000 * i / 44_100.0)).toInt().toShort()
            b.putShort(s); b.putShort(s)
        }
        return b.flip() as ByteBuffer
    }

    private fun bytes(b: ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    @Test
    fun bitPerfectBypassLeavesEverySampleUntouched() {
        val dsp = sink { boost.copy(bypassed = true) }
        val buffer = tone()
        val before = bytes(buffer)
        dsp.handleBuffer(buffer, 0L, 1)
        assertArrayEquals(before, bytes(buffer))
    }

    @Test
    fun theBypassLeavesMultichannelAudioProcessed() {
        // Bit-perfect covers stereo only; a 5.1 stream (three of these stereo pairs) still gets the DSP.
        val dsp = sink(channels = 6) { boost.copy(bypassed = true) }
        val buffer = tone(1026)
        val before = bytes(buffer)
        dsp.handleBuffer(buffer, 0L, 1)
        assertFalse(before.contentEquals(bytes(buffer)))
    }

    @Test
    fun activeStagesProcessTheSamples() {
        val dsp = sink { boost }
        val buffer = tone()
        val before = bytes(buffer)
        dsp.handleBuffer(buffer, 0L, 1)
        assertFalse(before.contentEquals(bytes(buffer)))
    }

    @Test
    fun aReofferedBufferIsProcessedOnceAndADifferentOneWithTheSameTimestampAgain() {
        val dsp = sink { boost }
        val buffer = tone()
        dsp.handleBuffer(buffer, 1_000L, 1)
        val once = bytes(buffer)
        dsp.handleBuffer(buffer, 1_000L, 1) // the inner sink had no room: same buffer again
        assertArrayEquals("a re-offered buffer must not be processed twice", once, bytes(buffer))
        val other = tone()
        val untouched = bytes(other)
        dsp.handleBuffer(other, 1_000L, 1)
        assertFalse("a new buffer with the same timestamp must still be processed", untouched.contentEquals(bytes(other)))
    }

    @Test
    fun theBypassNeedsBothBitPerfectAndAUsbOutput() {
        assertTrue(dspBypassed(bitPerfectUsb = true, usbOutputConnected = true))
        assertFalse(dspBypassed(bitPerfectUsb = true, usbOutputConnected = false))
        assertFalse(dspBypassed(bitPerfectUsb = false, usbOutputConnected = true))
        assertEquals(false, dspBypassed(bitPerfectUsb = false, usbOutputConnected = false))
    }
}
