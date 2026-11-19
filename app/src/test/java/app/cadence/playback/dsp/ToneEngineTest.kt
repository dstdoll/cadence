package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ToneEngineTest {
    private val fs = 48_000

    @Test
    fun flatIsInactiveAndSkipped() {
        assertFalse(ToneEngine(fs, 2, 0.0, 0.0).active)
    }

    @Test
    fun anyShelfMakesItActive() {
        assertTrue(ToneEngine(fs, 2, 4.0, 0.0).active)
        assertTrue(ToneEngine(fs, 2, 0.0, -3.0).active)
    }

    @Test
    fun bassLiftRaisesLowTonesNotHighOnes() {
        val eng = ToneEngine(fs, 1, bassDb = 6.0, trebleDb = 0.0)
        assertEquals("+6 dB at 30 Hz", 6.0, steadyGainDb(eng, 30.0), 0.1)
        assertEquals("~0 dB at 4 kHz", 0.0, steadyGainDb(eng, 4000.0), 0.1)
    }

    @Test
    fun trebleCutLowersHighTonesNotLowOnes() {
        val eng = ToneEngine(fs, 1, bassDb = 0.0, trebleDb = -6.0)
        assertEquals("-6 dB at 18 kHz", -6.0, steadyGainDb(eng, 18_000.0), 0.15)
        assertEquals("~0 dB at 200 Hz", 0.0, steadyGainDb(eng, 200.0), 0.1)
    }

    @Test
    fun channelsKeepIndependentState() {
        val eng = ToneEngine(fs, 2, 6.0, 6.0)
        repeat(100) { eng.processSample(0, 1.0) } // drive only channel 0
        val ref = ToneEngine(fs, 1, 6.0, 6.0)
        assertEquals("channel 1 untouched", ref.processSample(0, 0.3), eng.processSample(1, 0.3), 1e-9)
    }

    private fun steadyGainDb(eng: ToneEngine, f0: Double): Double {
        var inSq = 0.0; var outSq = 0.0
        val settle = 8_000; val n = fs * 2
        for (i in 0 until n) {
            val x = sin(2 * PI * f0 * i / fs)
            val y = eng.processSample(0, x)
            if (i >= settle) { inSq += x * x; outSq += y * y }
        }
        return 20.0 * log10(sqrt(outSq) / sqrt(inSq))
    }
}
