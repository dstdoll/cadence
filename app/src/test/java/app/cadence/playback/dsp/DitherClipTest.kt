package app.cadence.playback.dsp

import app.cadence.state.settings.DitherType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Shaped dither must not store up error while the output clips and release it afterwards. */
class DitherClipTest {
    @Test
    fun noThumpAfterClipping() {
        val eng = DitherEngine(DitherType.Shaped, channels = 1)
        repeat(48_000) { eng.quantize(0, 1.2) } // a second of clipped full scale
        // Back to silence: the first samples must be within a few LSB of zero, not thousands.
        val after = (0 until 64).map { abs(eng.quantize(0, 0.0)) }
        assertTrue("first samples after clipping: ${after.take(8)}", after.max() <= 3)
    }

    @Test
    fun normalSignalsAreUnaffected() {
        val eng = DitherEngine(DitherType.Shaped, channels = 1)
        val x = 1000.3 / 32768.0
        val mean = (0 until 100_000).sumOf { eng.quantize(0, x).toDouble() } / 100_000
        assertTrue("mean $mean should track the input", abs(mean - 1000.3) < 0.05)
    }
}
