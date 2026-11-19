package app.cadence.playback

import androidx.media3.exoplayer.audio.AudioOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bit-perfect output picks only an exact mixer, never truncates 24-bit audio, and converts losslessly. */
class BitPerfectTest {
    private val stereo = Pcm.OUT_STEREO
    private fun opt(enc: Int, rate: Int, bp: Boolean = true) = MixerOption(enc, rate, stereo, bp)
    private fun choose(options: List<MixerOption>, enabled: Boolean = true, sdk: Int = 35, usb: Boolean = true, dec: Int = Pcm.FLOAT, rate: Int = 192_000) =
        chooseBitPerfect(enabled, sdk, usb, dec, rate, stereo, options)

    @Test
    fun theReasonItIsInactiveIsReported() {
        assertEquals(BitPerfectChoice.Inactive("disabled"), choose(emptyList(), enabled = false))
        assertEquals(BitPerfectChoice.Inactive("API < 34"), choose(emptyList(), sdk = 33))
        assertEquals(BitPerfectChoice.Inactive("no USB device"), choose(emptyList(), usb = false))
    }

    @Test
    fun multichannelIsNeverBitPerfectEvenWithAMatchingMixer() {
        val surround = 252 // 5.1: front L/R, centre, LFE, back L/R
        val options = listOf(MixerOption(Pcm.PCM_24_PACKED, 48_000, surround, true))
        assertEquals(BitPerfectChoice.Inactive("multichannel (6 ch)"), chooseBitPerfect(true, 35, true, Pcm.FLOAT, 48_000, surround, options))
    }

    @Test
    fun onlyTheExactRateQualifiesAndNothingIsResampled() {
        val c = choose(listOf(opt(Pcm.PCM_24_PACKED, 96_000), opt(Pcm.PCM_24_PACKED, 192_000, bp = false)))
        assertTrue(c is BitPerfectChoice.Inactive)
        assertEquals(BitPerfectChoice.Active(Pcm.PCM_24_PACKED, 192_000, stereo), choose(listOf(opt(Pcm.PCM_24_PACKED, 192_000))))
    }

    @Test
    fun floatOutputNeverDropsTo16Bit() {
        assertTrue(choose(listOf(opt(Pcm.PCM_16, 192_000))) is BitPerfectChoice.Inactive)
        assertEquals(Pcm.PCM_24_PACKED, (choose(listOf(opt(Pcm.PCM_16, 192_000), opt(Pcm.PCM_32, 192_000), opt(Pcm.PCM_24_PACKED, 192_000))) as BitPerfectChoice.Active).encoding)
        assertEquals(Pcm.PCM_32, (choose(listOf(opt(Pcm.PCM_16, 192_000), opt(Pcm.PCM_32, 192_000))) as BitPerfectChoice.Active).encoding)
        assertEquals(Pcm.FLOAT, (choose(listOf(opt(Pcm.FLOAT, 192_000))) as BitPerfectChoice.Active).encoding)
    }

    @Test
    fun sixteenBitDecoderOutputUsesTheNarrowestExactMixer() {
        assertEquals(Pcm.PCM_16, (choose(listOf(opt(Pcm.PCM_32, 44_100), opt(Pcm.PCM_16, 44_100)), dec = Pcm.PCM_16, rate = 44_100) as BitPerfectChoice.Active).encoding)
    }

    private fun floats(vararg v: Float): ByteBuffer = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) }; flip() }

    @Test
    fun everyTwentyFourBitValueSurvivesFloatAndComesBackExactly() {
        val ints = intArrayOf(-8388608, -1, 0, 1, 8388607, 1234567, -7654321, 4096)
        val src = floats(*FloatArray(ints.size) { ints[it] / 8388608f })
        val dst = ByteBuffer.allocate(ints.size * 3)
        convertSamples(src, Pcm.FLOAT, dst, Pcm.PCM_24_PACKED, ints.size)
        dst.flip()
        val back = IntArray(ints.size) { val b0 = dst.get().toInt() and 0xFF; val b1 = dst.get().toInt() and 0xFF; val b2 = dst.get().toInt(); (b2 shl 16) or (b1 shl 8) or b0 }
        assertArrayEquals(ints, back)
        val d32 = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        convertSamples(floats(*FloatArray(ints.size) { ints[it] / 8388608f }), Pcm.FLOAT, d32, Pcm.PCM_32, ints.size)
        d32.flip()
        assertArrayEquals(IntArray(ints.size) { ints[it] shl 8 }, IntArray(ints.size) { d32.getInt() })
    }

    @Test
    fun aPartialWriteResumesWhereTheTrackStopped() {
        var accepted = 0
        val chunks = ArrayList<Int>()
        val inner = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioOutput::class.java)) { _, m, args ->
            when (m.name) {
                "write" -> { val b = args[0] as ByteBuffer; val n = minOf(b.remaining(), 30); b.position(b.position() + n); accepted += n; chunks += n; !b.hasRemaining() }
                else -> when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; else -> null }
            }
        } as AudioOutput
        val out = ConvertingAudioOutput(inner, Pcm.FLOAT, Pcm.PCM_24_PACKED, channels = 2)
        val src = floats(*FloatArray(20) { (it - 10) / 16f }) // 10 stereo frames: 80 bytes in, 60 bytes out
        var calls = 0
        while (src.hasRemaining() && calls < 10) { out.write(src, 1, 0L); calls++ }
        assertEquals("all output bytes", 60, accepted)
        assertEquals("input fully consumed in step with the output", 0, src.remaining())
        assertEquals(listOf(30, 30), chunks)
    }
}
