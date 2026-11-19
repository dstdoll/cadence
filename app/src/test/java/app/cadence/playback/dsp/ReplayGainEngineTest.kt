package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ReplayGainEngineTest {

    @Test
    fun unityGainIsInactive() {
        assertFalse(ReplayGainEngine(0.0, preventClip = true).active)
    }

    @Test
    fun anyGainIsActive() {
        assertTrue(ReplayGainEngine(-6.0, preventClip = false).active)
        assertTrue(ReplayGainEngine(3.0, preventClip = true).active)
    }

    @Test
    fun appliesExactLinearGain() {
        val eng = ReplayGainEngine(-6.0, preventClip = false)
        val expected = 0.5 * 10.0.pow(-6.0 / 20.0)
        assertEquals(expected, eng.processSample(0, 0.5), 1e-12)
    }

    @Test
    fun clipGuardLimitsBoostsButKeepsAttenuation() {
        // +12 dB boost: a loud sample would exceed full scale; the guard clamps it to 1.0.
        val boosted = ReplayGainEngine(12.0, preventClip = true)
        assertEquals(1.0, boosted.processSample(0, 0.9), 1e-12)
        assertEquals(-1.0, boosted.processSample(0, -0.9), 1e-12)
        // A quiet sample under full scale is boosted normally, not clamped.
        val small = 0.1 * 10.0.pow(12.0 / 20.0)
        assertEquals(small, boosted.processSample(0, 0.1), 1e-12)
    }

    @Test
    fun attenuationNeverClamps() {
        val cut = ReplayGainEngine(-3.0, preventClip = true)
        val expected = 1.0 * 10.0.pow(-3.0 / 20.0) // full-scale input, attenuated, stays < 1
        assertEquals(expected, cut.processSample(0, 1.0), 1e-12)
    }

    @Test
    fun effectiveDbPrefersTrackGainElseFallbackPlusPreamp() {
        assertEquals(-8.0 + 2.0, ReplayGainEngine.effectiveDb(trackGainDb = -8.0, noInfoDb = -5.0, preampDb = 2.0), 1e-12)
        assertEquals(-5.0 + 2.0, ReplayGainEngine.effectiveDb(trackGainDb = null, noInfoDb = -5.0, preampDb = 2.0), 1e-12)
    }
}
