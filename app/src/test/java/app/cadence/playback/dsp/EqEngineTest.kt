package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class EqEngineTest {
    private val fs = 48_000

    @Test
    fun flatConfigIsInactiveAndIsSkipped() {
        assertFalse(EqEngine(fs, 2, 0.0, EqEngine.flatGraphicBands()).active)
    }

    @Test
    fun preampOrAnyBandMakesItActive() {
        assertTrue(EqEngine(fs, 2, 0.0, listOf(EqBand(1000.0, 3.0, 1.0))).active)
        assertTrue(EqEngine(fs, 2, -6.0, EqEngine.flatGraphicBands()).active)
    }

    @Test
    fun preampScalesEveryChannelEqually() {
        val eng = EqEngine(fs, 2, -6.0, emptyList())
        val g = 10.0.pow(-6.0 / 20.0)
        assertEquals(0.5 * g, eng.processSample(0, 0.5), 1e-9)
        assertEquals(0.5 * g, eng.processSample(1, 0.5), 1e-9)
    }

    @Test
    fun oneBandAppliesItsGainAtItsFrequency() {
        val f0 = 2000.0
        val gainDb = 6.0
        val eng = EqEngine(fs, 1, 0.0, listOf(EqBand(f0, gainDb, 1.0)))
        var inSq = 0.0
        var outSq = 0.0
        for (i in 0 until fs) {
            val x = sin(2 * PI * f0 * i / fs)
            val y = eng.processSample(0, x)
            if (i >= 4_000) { inSq += x * x; outSq += y * y }
        }
        assertEquals(gainDb, 20.0 * log10(sqrt(outSq) / sqrt(inSq)), 0.05)
    }

    @Test
    fun channelsKeepIndependentFilterState() {
        val eng = EqEngine(fs, 2, 0.0, listOf(EqBand(1000.0, 6.0, 1.0)))
        repeat(100) { eng.processSample(0, 1.0) } // drive only channel 0
        val fresh = Biquad.peaking(fs, 1000.0, 6.0, 1.0)
        assertEquals("channel 1 untouched", fresh.process(0.3), eng.processSample(1, 0.3), 1e-9)
    }
}
