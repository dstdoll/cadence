package app.cadence.playback.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second-order IIR (biquad) filter — the building block of the equalizer. One instance holds the
 * state for **one audio channel**, so a stereo band uses two. Coefficients are normalised so `a0 = 1`
 * and processing is Direct Form I. The design functions are the Robert Bristow-Johnson "Audio EQ
 * Cookbook" formulas, which is exactly what ffmpeg's `equalizer`/`bass`/`treble` filters use, so our
 * response matches ffmpeg's for the same parameters (see BiquadTest + the ffmpeg cross-check).
 *
 * Math is done in `double` (the 64-bit pipeline): only the sink narrows back to the output format.
 */
class Biquad internal constructor(
    internal val b0: Double,
    internal val b1: Double,
    internal val b2: Double,
    internal val a1: Double,
    internal val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /**
     * Takes over [other]'s memory, so a filter with new coefficients continues the signal instead of
     * restarting from silence (no click when a control moves).
     */
    fun copyStateFrom(other: Biquad) {
        x1 = other.x1; x2 = other.x2; y1 = other.y1; y2 = other.y2
    }

    /** Clears the filter memory (call on flush/seek so old samples don't bleed across a cut). */
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    /** Filters one sample (Direct Form I). */
    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /** The steady-state magnitude response |H(e^jω)| in dB at [freq] Hz (ignores filter state). */
    fun magnitudeDb(freq: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * freq / sampleRate
        val cosW = cos(w); val cos2W = cos(2 * w); val sinW = sin(w); val sin2W = sin(2 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        return 20.0 * log10(hypot(numRe, numIm) / hypot(denRe, denIm))
    }

    companion object {
        /**
         * RBJ cookbook **peaking EQ** at [freq] Hz with [gainDb] boost/cut and quality [q]. This is the
         * per-band filter for both the graphic and parametric equalizer. Gain 0 dB yields a bypass.
         */
        fun peaking(sampleRate: Int, freq: Double, gainDb: Double, q: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q.coerceAtLeast(1e-4))
            val b0 = 1 + alpha * a
            val b1 = -2 * cosW0
            val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val a1 = -2 * cosW0
            val a2 = 1 - alpha / a
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /**
         * RBJ cookbook **low-shelf** at [freq] Hz with [gainDb], shelf steepness set by [q]. This is
         * the bass tone control: everything below the corner is lifted/cut by [gainDb], the corner
         * itself sits at half the gain. The default [q] of 0.707 gives a monotonic, non-overshooting
         * shelf (bounded to `[0, gainDb]`), unlike ffmpeg's `bass`/`lowshelf` at the same width, which
         * rings slightly past the target in the transition band (verified in the build log); both agree
         * exactly at the plateau and the corner.
         */
        fun lowShelf(sampleRate: Int, freq: Double, gainDb: Double, q: Double = 0.707): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q.coerceAtLeast(1e-4))
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha)
            val b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
            val b2 = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
            val a1 = -2 * ((a - 1) + (a + 1) * cosW0)
            val a2 = (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /**
         * RBJ cookbook **high-shelf** at [freq] Hz with [gainDb], steepness [q] (0.707 = monotonic,
         * non-overshooting default). This is the treble tone control: everything above the corner is
         * lifted/cut by [gainDb], the corner at half the gain. Same cookbook family as ffmpeg's
         * `treble`/`highshelf`, agreeing exactly at plateau and corner (see the build-log cross-check).
         */
        fun highShelf(sampleRate: Int, freq: Double, gainDb: Double, q: Double = 0.707): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q.coerceAtLeast(1e-4))
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha)
            val b1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
            val b2 = a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha
            val a1 = 2 * ((a - 1) - (a + 1) * cosW0)
            val a2 = (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
