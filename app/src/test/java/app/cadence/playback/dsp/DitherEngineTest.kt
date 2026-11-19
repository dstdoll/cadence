package app.cadence.playback.dsp

import app.cadence.state.settings.DitherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dither's whole point is that it *linearizes* quantization: the average of the dithered 16-bit output
 * must equal the true sub-LSB input, which plain rounding can never do. These prove that, plus the
 * expected noise relationship (TPDF noisier than RPDF) and that "None" is exact rounding.
 */
class DitherEngineTest {
    private val n = 400_000
    private val lsb = 0.3 // a constant 0.3-LSB input: plain rounding would always give 0

    private fun stats(type: DitherType, valueLsb: Double = lsb): Pair<Double, Double> {
        val eng = DitherEngine(type, channels = 1)
        val x = valueLsb / 32768.0
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val q = eng.quantize(0, x)
            sum += q
            sumSq += q.toDouble() * q
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return mean to variance
    }

    @Test
    fun ditherMakesTheAverageTrackTheTrueSubLsbValue() {
        // Plain rounding of 0.3 LSB is always 0; dither must recover 0.3 on average.
        for (type in listOf(DitherType.Rectangular, DitherType.Triangular, DitherType.Shaped)) {
            val (mean, _) = stats(type)
            assertEquals("$type mean", lsb, mean, 0.01)
        }
    }

    @Test
    fun noneIsExactRoundingWithNoNoise() {
        val (mean, variance) = stats(DitherType.None)
        assertEquals("rounds 0.3 LSB to 0", 0.0, mean, 1e-9)
        assertEquals("no noise", 0.0, variance, 1e-9)
    }

    @Test
    fun triangularDithersOnAQuantizationLevelButRectangularDoesNot() {
        // The textbook TPDF advantage: a sample sitting exactly on a level (0 LSB). RPDF noise never
        // crosses a decision boundary there, so it adds nothing (correlated, no dithering); TPDF still
        // spreads the sample across levels, which is what keeps the noise floor signal-independent.
        val rpdf = stats(DitherType.Rectangular, valueLsb = 0.0).second
        val tpdf = stats(DitherType.Triangular, valueLsb = 0.0).second
        assertEquals("RPDF cannot dither on-level", 0.0, rpdf, 1e-9)
        assertTrue("TPDF still dithers on-level ($tpdf)", tpdf > 0.1)
    }

    @Test
    fun outputStaysInSixteenBitRange() {
        val eng = DitherEngine(DitherType.Triangular, channels = 1)
        for (x in listOf(1.0, -1.0, 0.9999, -0.9999)) {
            repeat(1000) {
                val q = eng.quantize(0, x)
                assertTrue("$q in range", q in -32768..32767)
            }
        }
    }

    @Test
    fun channelsAreIndependentForShaping() {
        val eng = DitherEngine(DitherType.Shaped, channels = 2)
        // Driving channel 0 must not disturb channel 1's error feedback / mean.
        repeat(50_000) { eng.quantize(0, 0.7) }
        var sum = 0.0
        val rep = 200_000
        repeat(rep) { sum += eng.quantize(1, lsb / 32768.0) }
        assertEquals(lsb, sum / rep, 0.01)
    }
}
