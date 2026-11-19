package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Balance + stereo-width math. The transforms are exact and linear, so these are analytic checks:
 * a centred/normal setting is a pure pass-through, balance only attenuates the far channel, and width
 * is the mid/side scaling (0 = mono, 1 = identity, 2 = doubled side).
 */
class ChannelEngineTest {
    private fun frame(l: Double, r: Double, eng: ChannelEngine): DoubleArray {
        val f = doubleArrayOf(l, r)
        eng.processFrame(f)
        return f
    }

    @Test
    fun centredNormalStereoIsInactive() {
        assertFalse(ChannelEngine(channels = 2, balance = 0.0, width = 1.0).active)
    }

    @Test
    fun monoAudioIsInactive() {
        // No left/right to mix, so the stage never engages even with settings dialled in.
        assertFalse(ChannelEngine(channels = 1, balance = 0.5, width = 0.0).active)
    }

    @Test
    fun fullRightMutesTheLeftChannelOnly() {
        val eng = ChannelEngine(channels = 2, balance = 1.0, width = 1.0)
        assertTrue(eng.active)
        val f = frame(0.8, 0.5, eng)
        assertEquals("left muted", 0.0, f[0], 1e-12)
        assertEquals("right untouched", 0.5, f[1], 1e-12)
    }

    @Test
    fun halfLeftLeavesLeftAndAttenuatesRight() {
        // Panning toward the left keeps the left channel at unity and lowers the right.
        val f = frame(0.8, 0.4, ChannelEngine(channels = 2, balance = -0.5, width = 1.0))
        assertEquals("left untouched", 0.8, f[0], 1e-12)
        assertEquals("right halved", 0.2, f[1], 1e-12) // 0.4 * (1 + (-0.5)) = 0.2
    }

    @Test
    fun widthZeroCollapsesToMono() {
        val f = frame(0.6, 0.2, ChannelEngine(channels = 2, balance = 0.0, width = 0.0))
        val mid = (0.6 + 0.2) / 2
        assertEquals(mid, f[0], 1e-12)
        assertEquals(mid, f[1], 1e-12)
        assertEquals("both channels equal = mono", f[0], f[1], 1e-12)
    }

    @Test
    fun widthTwoDoublesTheSideSignal() {
        val l = 0.5; val r = 0.1
        val f = frame(l, r, ChannelEngine(channels = 2, balance = 0.0, width = 2.0))
        val mid = (l + r) / 2
        val side = (l - r) / 2
        assertEquals(mid + 2 * side, f[0], 1e-12)
        assertEquals(mid - 2 * side, f[1], 1e-12)
        // Widening increases the L-R difference versus the original.
        assertTrue((f[0] - f[1]) > (l - r))
    }

    @Test
    fun monoContentIsUnchangedByAnyWidth() {
        // When L == R the side signal is zero, so width scaling can't change anything.
        for (w in listOf(0.0, 0.5, 1.0, 2.0)) {
            val f = frame(0.33, 0.33, ChannelEngine(channels = 2, balance = 0.0, width = w))
            assertEquals("width=$w", 0.33, f[0], 1e-12)
            assertEquals("width=$w", 0.33, f[1], 1e-12)
        }
    }

    @Test
    fun widthPreservesTheMonoSumWhileChangingSeparation() {
        // Mid/side width keeps (L+R) — the mono downmix / loudness — invariant for any width.
        val l = 0.4; val r = -0.2
        for (w in listOf(0.0, 0.7, 1.0, 1.8)) {
            val f = frame(l, r, ChannelEngine(channels = 2, balance = 0.0, width = w))
            assertEquals("sum preserved at width=$w", l + r, f[0] + f[1], 1e-12)
        }
    }
}
