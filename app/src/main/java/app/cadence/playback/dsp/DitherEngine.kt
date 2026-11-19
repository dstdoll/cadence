package app.cadence.playback.dsp

import app.cadence.state.settings.DitherType
import kotlin.math.roundToInt

/**
 * The dither stage: it quantizes a high-resolution sample (processed in `double`) down to a 16-bit
 * integer, adding a tiny, carefully-shaped noise so that quantization error becomes uncorrelated
 * noise instead of harmonic distortion. This is the **last** step of the sink's 16-bit path; on a
 * float output path nothing quantizes here, so the sink never builds this engine.
 *
 * Units: one 16-bit LSB equals 1.0 in the scaled integer domain, so the dither amplitudes below are
 * expressed directly in LSBs. Per-channel error state (for [DitherType.Shaped]) means one instance is
 * owned by the sink and driven single-threaded, like the other engines.
 *
 * - [DitherType.Rectangular]: one uniform `[-0.5, 0.5]` LSB (RPDF). Decorrelates error; noise is
 *   signal-dependent in level.
 * - [DitherType.Triangular]: sum of two uniforms → triangular `[-1, 1]` LSB (TPDF). The standard
 *   choice: it fully decouples both the mean and the variance of the error from the signal.
 * - [DitherType.Shaped]: TPDF plus first-order error feedback `(1 - z⁻¹)`, moving noise power toward
 *   higher, less-audible frequencies.
 */
class DitherEngine(private val type: DitherType, channels: Int) {
    private val error = DoubleArray(channels)
    private var state = 0x2545F4914F6CDD1DUL // xorshift64 seed, advanced per draw

    fun reset() {
        error.fill(0.0)
    }

    /** One uniform in [0, 1) from a fast xorshift64 (no allocation, audio-thread friendly). */
    private fun uniform(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x shr 7)
        x = x xor (x shl 17)
        state = x
        return (x shr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun ditherLsb(): Double = when (type) {
        DitherType.Rectangular -> uniform() - 0.5
        DitherType.Triangular, DitherType.Shaped -> (uniform() - 0.5) + (uniform() - 0.5)
        DitherType.None -> 0.0
    }

    /** Quantizes [x] (nominal full-scale ±1.0) for [channel] to a dithered 16-bit sample. */
    fun quantize(channel: Int, x: Double): Int {
        val scaled = x * 32768.0
        val shaped = if (type == DitherType.Shaped) scaled + error[channel] else scaled
        val target = shaped + ditherLsb()
        val q = target.roundToInt().coerceIn(-32768, 32767)
        // In normal use the error stays within 1.5 LSB (0.5 rounding plus 1 of TPDF). While the output
        // clips it would grow without bound and then dump into the next samples as a thump.
        if (type == DitherType.Shaped) error[channel] = (shaped - q).coerceIn(-MAX_ERROR, MAX_ERROR)
        return q
    }

    private companion object {
        const val MAX_ERROR = 1.5
    }
}
