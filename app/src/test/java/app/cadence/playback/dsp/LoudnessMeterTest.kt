package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Against the BS.1770 / EBU Tech 3341 reference cases. */
class LoudnessMeterTest {
    private fun sine(meter: LoudnessMeter, sr: Int, seconds: Double, peakDbfs: Double, freq: Double = 997.0, channels: Int = 2) {
        val amp = 10.0.pow(peakDbfs / 20)
        val frame = DoubleArray(channels)
        repeat((sr * seconds).toInt()) { i ->
            val x = amp * sin(2 * PI * freq * i / sr)
            for (c in 0 until channels) frame[c] = x
            meter.addFrame(frame)
        }
    }

    @Test
    fun aStereoSineAtMinus23DbfsReadsMinus23Lufs() {
        for (sr in listOf(44_100, 48_000, 96_000)) {
            val m = LoudnessMeter(sr, 2)
            sine(m, sr, 20.0, -23.0)
            assertEquals("at $sr Hz", -23.0, m.integratedLufs()!!, 0.1)
        }
    }

    @Test
    fun silenceIsGatedOut() {
        val m = LoudnessMeter(48_000, 2)
        sine(m, 48_000, 10.0, -20.0)
        sine(m, 48_000, 10.0, -200.0) // digital silence, below the absolute gate
        assertEquals(-20.0, m.integratedLufs()!!, 0.1)
    }

    @Test
    fun quietPassagesAreRelativelyGated() {
        // EBU Tech 3341 case 3 style: a loud part and a part 30 dB quieter; the quiet part is below
        // the relative gate, so the result is the loud part's loudness.
        val m = LoudnessMeter(48_000, 2)
        sine(m, 48_000, 10.0, -20.0)
        sine(m, 48_000, 10.0, -50.0)
        assertEquals(-20.0, m.integratedLufs()!!, 0.15)
    }

    @Test
    fun theKWeightingMatchesTheStandardsCoefficientsAt48k() {
        val shelf = LoudnessMeter.kShelf(48_000)
        val hp = LoudnessMeter.kHighPass(48_000)
        val tol = 1e-9
        assertEquals(1.53512485958697, shelf.b0, tol); assertEquals(-2.69169618940638, shelf.b1, tol); assertEquals(1.19839281085285, shelf.b2, tol)
        assertEquals(-1.69065929318241, shelf.a1, tol); assertEquals(0.73248077421585, shelf.a2, tol)
        assertEquals(1.0, hp.b0, 0.0); assertEquals(-2.0, hp.b1, 0.0); assertEquals(1.0, hp.b2, 0.0)
        assertEquals(-1.99004745483398, hp.a1, tol); assertEquals(0.99007225036621, hp.a2, tol)
    }

    @Test
    fun tooLittleAudioGivesNoValue() {
        val m = LoudnessMeter(48_000, 2)
        sine(m, 48_000, 0.3, -20.0)
        assertNull(m.integratedLufs())
    }

    @Test
    fun surroundChannelsUseTheStandardWeights() {
        val w = LoudnessMeter.channelWeights(6)
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0), w.take(4).toList())
        assertEquals(1.41, w[4], 0.005); assertEquals(1.41, w[5], 0.005)
    }
}
