package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class LimiterEngineTest {
    private val sr = 48_000

    private fun run(lim: LimiterEngine, input: List<Double>): List<Double> =
        input.map { x -> DoubleArray(2) { x }.also(lim::processFrame)[0] }

    @Test
    fun nothingEverExceedsTheThreshold() {
        val lim = LimiterEngine(sr, 2, thresholdDb = -0.5)
        // A sine boosted to twice full scale, with sudden full-scale clicks on top.
        val input = (0 until sr).map { i -> 2.0 * sin(2 * PI * 440 * i / sr) + if (i % 5000 == 0) 3.0 else 0.0 }
        val ceiling = 10.0.pow(-0.5 / 20)
        val worst = run(lim, input).maxOf { abs(it) }
        assertTrue("peak $worst over $ceiling", worst <= ceiling + 1e-12)
    }

    @Test
    fun belowTheThresholdItOnlyDelays() {
        val lim = LimiterEngine(sr, 2)
        val input = (0 until 4800).map { i -> 0.5 * sin(2 * PI * 1000 * i / sr) }
        val out = run(lim, input)
        for (i in lim.lookahead until input.size) assertEquals(input[i - lim.lookahead], out[i], 0.0)
        assertEquals(96, lim.lookahead) // 2 ms at 48 kHz
    }

    @Test
    fun theGainMovesSmoothlyWithoutSteps() {
        val lim = LimiterEngine(sr, 2)
        val gains = (0 until 9600).map { i ->
            DoubleArray(2) { if (i in 4000..4010) 4.0 else 0.1 }.also(lim::processFrame)
            lim.gain
        }
        val largestStep = gains.zipWithNext { a, b -> abs(b - a) }.max()
        // A sudden 12 dB peak is ramped over the look-ahead: no single-frame jump near the full drop.
        assertTrue("largest gain step $largestStep", largestStep < 0.05)
    }

    @Test
    fun onStandbyItOnlyDelaysEvenAboveTheThreshold() {
        val lim = LimiterEngine(sr, 2).apply { engaged = false }
        val input = (0 until 4800).map { i -> 0.999 * sin(2 * PI * 1000 * i / sr) }
        val out = run(lim, input)
        for (i in lim.lookahead until input.size) assertEquals(input[i - lim.lookahead], out[i], 0.0)
    }

    @Test
    fun goingToStandbyKeepsWhatIsInTheDelayLineProtectedThenReleasesToExactlyUnity() {
        val lim = LimiterEngine(sr, 2)
        val ceiling = 10.0.pow(-0.5 / 20)
        val loud = (0 until 4800).map { i -> 2.0 * sin(2 * PI * 440 * i / sr) }
        run(lim, loud)
        lim.engaged = false
        // The boosted frames still in the delay line come out under the ceiling...
        val drained = run(lim, List(lim.lookahead) { 0.0 })
        assertTrue(drained.all { abs(it) <= ceiling + 1e-12 })
        // ...then the reduction releases and the stage is exactly transparent again.
        val quiet = (0 until 5 * sr).map { i -> 0.3 * sin(2 * PI * 440 * i / sr) }
        val out = run(lim, quiet)
        assertEquals(1.0, lim.gain, 0.0)
        val n = quiet.size
        for (i in n - 1000 until n) assertEquals(quiet[i - lim.lookahead], out[i], 0.0)
    }
}
