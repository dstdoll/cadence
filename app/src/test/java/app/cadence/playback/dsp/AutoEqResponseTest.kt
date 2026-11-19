package app.cadence.playback.dsp

import app.cadence.playback.DspChain
import app.cadence.playback.DspSettings
import app.cadence.state.settings.AutoEq
import app.cadence.state.settings.ChannelConfig
import app.cadence.state.settings.DitherConfig
import app.cadence.state.settings.EqConfig
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.ToneConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A loaded AutoEQ profile must sound the way AutoEQ designed it. The reference curve is
 * AutoEQ's own coefficient formulas evaluated with scipy.signal.sosfreqz,
 * independently of the app's Biquad. The test compares (1) the equalizer's steady-state response and
 * (2) the gain of real sine tones run through the filters, against that curve.
 */
class AutoEqResponseTest {
    private val profile = AutoEq.parse(javaClass.getResource("/autoeq/dt770pro80.txt")!!.readText(), "DT 770 Pro (80 Ohm) ParametricEQ.txt")
    private val ref = Json.parseToJsonElement(javaClass.getResource("/autoeq/dt770pro80-48k.json")!!.readText()).jsonObject
    private val fs = ref.getValue("fs").jsonPrimitive.int
    private val freqs = ref.getValue("freqs").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
    private val expected = ref.getValue("filtersDb").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    /** The equalizer as the sink builds it: the profile first, then the (flat) graphic bands. */
    private fun engine(cfg: EqConfig = EqConfig(enabled = true, headphone = profile)) =
        EqEngine(fs, 1, DspChain.eqPreampDb(cfg), DspChain.eqBands(cfg))

    @Test
    fun theResponseMatchesTheScipyReference() {
        val eq = engine()
        assertEquals(ref.getValue("filters").jsonPrimitive.int, profile.filters.size)
        var worst = 0.0
        for (i in freqs.indices) worst = maxOf(worst, kotlin.math.abs(eq.magnitudeDb(freqs[i], fs) - expected[i]))
        println("autoeq: ${freqs.size} points, worst deviation from scipy %.6f dB".format(worst))
        assertTrue("worst deviation from scipy: $worst dB", worst < 0.001)
    }

    @Test
    fun sineTonesThroughTheFiltersGetTheReferenceGain() {
        // A few points across the curve: the 49 Hz cut, the shelf corner, the 205 Hz peak, the presence
        // peak near 3.6 kHz and the treble cut.
        for (f in listOf(49.0, 105.0, 205.0, 1000.0, 3603.0, 5956.0, 12000.0)) {
            val eq = engine()
            val n = fs * 2
            var inPow = 0.0
            var outPow = 0.0
            for (k in 0 until n) {
                val x = sin(2 * PI * f * k / fs) * 0.1
                val y = eq.processSample(0, x)
                if (k >= fs) { inPow += x * x; outPow += y * y } // skip the first second (filters settle)
            }
            val measured = 10 * log10(outPow / inPow)
            val want = eq.magnitudeDb(f, fs)
            println("autoeq: %6.0f Hz  sine %+7.3f dB  response %+7.3f dB".format(f, measured, want))
            assertEquals("gain at $f Hz", want, measured, 0.02)
        }
    }

    @Test
    fun autoHeadroomReplacesTheFilesPreampAndOffUsesIt() {
        val on = EqConfig(enabled = true, autoHeadroom = true, headphone = profile)
        assertEquals(0.0, DspChain.eqPreampDb(on), 0.0)
        val boost = DspChain.peakBoostDb(null, engine(on), null, fs)
        assertTrue("headroom $boost dB should cover the profile's boost (AutoEQ preamp 4.6 dB)", boost in 4.3..4.7)
        val off = EqConfig(enabled = true, autoHeadroom = false, headphone = profile)
        assertEquals(-4.6, DspChain.eqPreampDb(off), 1e-6)
        val chain = DspChain.build(DspSettings(ReplayGainConfig(), ToneConfig(), on, ChannelConfig(), DitherConfig()), fs, 2, pcm = true, levellingDb = null)
        assertEquals(-boost, 20 * log10(chain.preGain), 1e-9)
    }

    @Test
    fun theGraphicBandsStackOnTopOfTheProfile() {
        val bands = EqConfig.defaultBands.map { if (it.freq == 1000) it.copy(gainDb = 6f) else it }
        val both = engine(EqConfig(enabled = true, headphone = profile, bands = bands))
        val alone = engine()
        val band = Biquad.peaking(fs, 1000.0, 6.0, 1.0).magnitudeDb(1000.0, fs)
        assertEquals(alone.magnitudeDb(1000.0, fs) + band, both.magnitudeDb(1000.0, fs), 1e-9)
    }

    @Test
    fun noProfileLeavesTheGraphicEqualizerAsItWas() {
        val cfg = EqConfig(enabled = true)
        assertEquals(10, DspChain.eqBands(cfg).size)
        assertTrue(DspChain.eqBands(cfg).all { it.shape == EqShape.Peaking })
        assertEquals(0.0, sqrt(DspChain.eqPreampDb(cfg)), 0.0)
    }
}
