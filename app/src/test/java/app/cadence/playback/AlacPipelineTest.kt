package app.cadence.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import app.cadence.state.settings.ChannelConfig
import app.cadence.state.settings.DitherConfig
import app.cadence.state.settings.EqConfig
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.ToneConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@OptIn(UnstableApi::class)
class AlacPipelineTest {
    private fun fakeSink(supportFloat: Boolean): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "supportsFormat" -> {
                    val fmt = args?.get(0) as? Format
                    when (fmt?.pcmEncoding) {
                        C.ENCODING_PCM_FLOAT -> supportFloat
                        C.ENCODING_PCM_16BIT -> true
                        else -> false
                    }
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> true
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink

    private fun renderer(supportFloat: Boolean = true): AlacAudioRenderer =
        AlacAudioRenderer(null, null, fakeSink(supportFloat))

    private fun alacFormat(cookie: ByteArray? = ByteArray(24)): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setSampleRate(96_000)
            .setChannelCount(2)
            .apply { if (cookie != null) setInitializationData(listOf(cookie)) }
            .build()

    @Test
    fun supportsOnlyAlacWithCookie() {
        val r = renderer(supportFloat = true)
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(r.supportsFormat(alacFormat())))
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(r.supportsFormat(alacFormat(cookie = null))))

        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44_100).setChannelCount(2).build()
        val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setSampleRate(48_000).setChannelCount(6).build()
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(r.supportsFormat(aac)))
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(r.supportsFormat(eac3)))
    }

    @Test
    fun supportsAlacWhenSinkRejectsFloat() {
        val r = renderer(supportFloat = false)
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(r.supportsFormat(alacFormat())))
    }

    @Test
    fun dspProcessesBothPcm16AndPcmFloatFromAlacRenderer() {
        val settings = DspSettings(
            replayGain = ReplayGainConfig(),
            tone = ToneConfig(enabled = true, bassDb = 6f, trebleDb = 0f),
            eq = EqConfig(enabled = true, bands = EqConfig.defaultBands.map { if (it.freq == 1000) it.copy(gainDb = 6f) else it }),
            channel = ChannelConfig(),
            dither = DitherConfig(),
        )

        // 1. PCM 16-bit
        val format16 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44_100).setChannelCount(2).build()
        val dsp16 = DspAudioSink(fakeSink(false)) { settings }.apply {
            configure(AudioSink.AudioSinkConfig.Builder(format16).build())
        }
        val buf16 = ByteBuffer.allocate(1024 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1024) { i ->
            val s = (8192 * sin(2 * PI * 100 * i / 44_100.0)).toInt().toShort()
            buf16.putShort(s); buf16.putShort(s)
        }
        buf16.flip()
        val before16 = ByteArray(buf16.remaining()).also { buf16.duplicate().get(it) }
        dsp16.handleBuffer(buf16, 0L, 1)
        val after16 = ByteArray(buf16.remaining()).also { buf16.duplicate().get(it) }
        assertFalse("EQ/Tone must modify PCM_16BIT", before16.contentEquals(after16))

        // 2. PCM Float (24-bit Hi-Res path)
        val formatFloat = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(96_000).setChannelCount(2).build()
        val dspFloat = DspAudioSink(fakeSink(true)) { settings }.apply {
            configure(AudioSink.AudioSinkConfig.Builder(formatFloat).build())
        }
        val bufFloat = ByteBuffer.allocate(1024 * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1024) { i ->
            val s = (0.25 * sin(2 * PI * 100 * i / 96_000.0)).toFloat()
            bufFloat.putFloat(s); bufFloat.putFloat(s)
        }
        bufFloat.flip()
        val beforeFloat = ByteArray(bufFloat.remaining()).also { bufFloat.duplicate().get(it) }
        dspFloat.handleBuffer(bufFloat, 0L, 1)
        val afterFloat = ByteArray(bufFloat.remaining()).also { bufFloat.duplicate().get(it) }
        assertFalse("EQ/Tone must modify PCM_FLOAT", beforeFloat.contentEquals(afterFloat))

        // 3. Bit-perfect bypass leaves PCM_FLOAT bit-identical
        val dspBypass = DspAudioSink(fakeSink(true)) { settings.copy(bypassed = true) }.apply {
            configure(AudioSink.AudioSinkConfig.Builder(formatFloat).build())
        }
        val bufBypass = ByteBuffer.wrap(beforeFloat.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        dspBypass.handleBuffer(bufBypass, 0L, 1)
        val afterBypass = ByteArray(bufBypass.remaining()).also { bufBypass.duplicate().get(it) }
        assertArrayEquals(beforeFloat, afterBypass)
    }
}
