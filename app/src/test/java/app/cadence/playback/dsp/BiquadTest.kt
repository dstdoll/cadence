package app.cadence.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer is only trustworthy if a band does exactly what its dB says. These prove the RBJ
 * peaking coefficients (1) give the asked-for gain at the band centre and ~0 dB far away, and (2) the
 * time-domain filter matches that frequency response. The same RBJ formulas drive ffmpeg's
 * `equalizer`, cross-checked empirically in the build log.
 */
class BiquadTest {
    private val fs = 48_000

    @Test
    fun peakingHitsTargetGainAtCentreAndIsFlatFarAway() {
        val f0 = 1000.0
        val bq = Biquad.peaking(fs, f0, gainDb = 6.0, q = 1.0)
        assertEquals("+6 dB at centre", 6.0, bq.magnitudeDb(f0, fs), 0.02)
        assertEquals("~0 dB two decades below", 0.0, bq.magnitudeDb(40.0, fs), 0.3)
        assertEquals("~0 dB near Nyquist", 0.0, bq.magnitudeDb(18_000.0, fs), 0.3)
    }

    @Test
    fun boostAndCutAreSymmetric() {
        val boost = Biquad.peaking(fs, 2000.0, 8.0, 1.5)
        val cut = Biquad.peaking(fs, 2000.0, -8.0, 1.5)
        assertEquals(8.0, boost.magnitudeDb(2000.0, fs), 0.02)
        assertEquals(-8.0, cut.magnitudeDb(2000.0, fs), 0.02)
    }

    @Test
    fun timeDomainGainMatchesTheMagnitudeResponse() {
        // A steady sine at the band centre, filtered: output/input RMS (after the filter settles)
        // must equal the analytical gain, proving the processing and the response agree.
        val f0 = 3000.0
        val gainDb = 5.0
        val bq = Biquad.peaking(fs, f0, gainDb, 1.0)
        var inSq = 0.0
        var outSq = 0.0
        val settle = 4_000
        val n = fs // one second
        for (i in 0 until n) {
            val x = sin(2 * PI * f0 * i / fs)
            val y = bq.process(x)
            if (i >= settle) { inSq += x * x; outSq += y * y }
        }
        val measuredDb = 20.0 * log10(sqrt(outSq) / sqrt(inSq))
        assertEquals(gainDb, measuredDb, 0.05)
    }

    @Test
    fun zeroGainIsUnity() {
        val bq = Biquad.peaking(fs, 1000.0, 0.0, 1.0)
        assertEquals(0.0, bq.magnitudeDb(1000.0, fs), 1e-9)
        assertEquals(0.5, bq.process(0.5), 1e-9) // passes samples through untouched
    }

    @Test
    fun lowShelfLiftsTheBassAndLeavesTrebleAlone() {
        // The bass tone control: everything well below the corner approaches the full shelf gain,
        // everything well above is untouched. Matches ffmpeg `bass=g=6:f=100:width_type=q:width=0.707`.
        val sh = Biquad.lowShelf(fs, 100.0, gainDb = 6.0)
        assertEquals("+6 dB deep in the bass", 6.0, sh.magnitudeDb(20.0, fs), 0.1)
        assertEquals("~0 dB up high", 0.0, sh.magnitudeDb(8000.0, fs), 0.1)
        assertEquals("half the shelf at the corner", 3.0, sh.magnitudeDb(100.0, fs), 0.2)
    }

    @Test
    fun highShelfLiftsTheTrebleAndLeavesBassAlone() {
        val sh = Biquad.highShelf(fs, 10_000.0, gainDb = -5.0)
        assertEquals("-5 dB up high", -5.0, sh.magnitudeDb(20_000.0, fs), 0.1)
        assertEquals("~0 dB down low", 0.0, sh.magnitudeDb(200.0, fs), 0.1)
    }

    @Test
    fun shelfTimeDomainMatchesResponse() {
        // A steady sine well inside the shelf band must gain exactly the shelf dB after settling.
        val gainDb = 6.0
        val sh = Biquad.lowShelf(fs, 100.0, gainDb)
        val f0 = 30.0 // inside the bass shelf
        var inSq = 0.0; var outSq = 0.0
        val settle = 8_000; val n = fs * 2
        for (i in 0 until n) {
            val x = sin(2 * PI * f0 * i / fs)
            val y = sh.process(x)
            if (i >= settle) { inSq += x * x; outSq += y * y }
        }
        assertEquals(gainDb, 20.0 * log10(sqrt(outSq) / sqrt(inSq)), 0.1)
    }

    @Test
    fun flatShelfIsUnity() {
        assertEquals(0.5, Biquad.lowShelf(fs, 100.0, 0.0).process(0.5), 1e-9)
        assertEquals(0.5, Biquad.highShelf(fs, 10_000.0, 0.0).process(0.5), 1e-9)
    }

    @Test
    fun shelvesDoNotOvershoot() {
        // A tone control must never ring past its target (unlike ffmpeg's width=0.707 shelf): the
        // boost shelf stays within [0, gainDb] across the whole band, the cut within [gainDb, 0].
        val boost = Biquad.lowShelf(fs, 100.0, 6.0)
        var f = 10.0
        while (f < 24_000) { val m = boost.magnitudeDb(f, fs); assertTrue("bass $f=$m in [0,6]", m >= -0.01 && m <= 6.01); f += 10 }
        val cut = Biquad.highShelf(fs, 10_000.0, -6.0)
        f = 10.0
        while (f < 24_000) { val m = cut.magnitudeDb(f, fs); assertTrue("treble $f=$m in [-6,0]", m <= 0.01 && m >= -6.01); f += 10 }
    }
}
