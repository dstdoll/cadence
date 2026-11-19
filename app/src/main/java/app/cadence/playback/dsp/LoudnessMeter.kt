package app.cadence.playback.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness (ITU-R BS.1770-4 / EBU R128) of the audio fed to it, in LUFS.
 *
 * Each channel is K-weighted (a high shelf then a high-pass, built for the stream's own sample rate
 * with the bilinear-transform forms and fitted parameters that reproduce the standard's 48 kHz
 * coefficients, as the reference implementation libebur128 does), squared and summed with the
 * channel weights (1.0 for left, right and centre, 1.41 for the surrounds, 0 for LFE). Mean squares are
 * kept per 100 ms; a gating block is 400 ms (four of them, 75% overlap). The integrated value gates
 * blocks below -70 LUFS, then those more than 10 LU below the mean of the rest.
 *
 * Also tracks the sample peak. Not thread-safe: fed from the audio thread only.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { kShelf(sampleRate) }
    private val highPass = Array(channels) { kHighPass(sampleRate) }
    private val weights = channelWeights(channels)
    private val stepFrames = sampleRate / 10
    private var stepSum = 0.0
    private var stepFill = 0
    private val steps = ArrayList<Double>()

    /** Largest absolute sample seen, full scale 1.0. */
    var samplePeak = 0.0
        private set

    /** Seconds of audio measured. */
    val seconds: Double get() = (steps.size * stepFrames + stepFill).toDouble() / sampleRate

    /** Feeds one frame of [channels] samples, full scale 1.0. */
    fun addFrame(frame: DoubleArray) {
        var sum = 0.0
        for (c in 0 until channels) {
            val x = frame[c]
            samplePeak = max(samplePeak, abs(x))
            val k = highPass[c].process(shelf[c].process(x))
            sum += weights[c] * k * k
        }
        stepSum += sum
        if (++stepFill == stepFrames) {
            steps += stepSum / stepFrames
            stepSum = 0.0
            stepFill = 0
        }
    }

    /** Clears the filters (after a seek) but keeps what was measured. */
    fun resetFilters() {
        shelf.forEach { it.reset() }
        highPass.forEach { it.reset() }
        stepSum = 0.0
        stepFill = 0
    }

    /** The gated integrated loudness in LUFS, or null with less than one gating block of sound. */
    fun integratedLufs(): Double? {
        if (steps.size < 4) return null
        val blocks = (0..steps.size - 4).map { (steps[it] + steps[it + 1] + steps[it + 2] + steps[it + 3]) / 4 }
        val absolute = blocks.filter { lufs(it) > ABSOLUTE_GATE }
        if (absolute.isEmpty()) return null
        val relativeGate = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) > relativeGate }
        return if (gated.isEmpty()) null else lufs(gated.average())
    }

    companion object {
        // Parameters fitted to the BS.1770 48 kHz filters, so they can be built for any sample rate.
        const val SHELF_HZ = 1681.974450955533
        const val SHELF_DB = 3.999843853973347
        const val SHELF_Q = 0.7071752369554196
        const val HIGHPASS_HZ = 38.13547087602444
        const val HIGHPASS_Q = 0.5003270373238773
        const val ABSOLUTE_GATE = -70.0

        fun lufs(meanSquare: Double): Double = -0.691 + 10.0 * log10(meanSquare)

        /** Stage 1 of the K-weighting: the head-related high shelf (+4 dB above about 1.7 kHz). */
        fun kShelf(sampleRate: Int): Biquad {
            val k = tan(PI * SHELF_HZ / sampleRate)
            val vh = 10.0.pow(SHELF_DB / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / SHELF_Q + k * k
            return Biquad(
                (vh + vb * k / SHELF_Q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / SHELF_Q + k * k) / a0,
                2.0 * (k * k - 1.0) / a0, (1.0 - k / SHELF_Q + k * k) / a0,
            )
        }

        /** Stage 2 of the K-weighting: the RLB high-pass (about 38 Hz), numerator 1, -2, 1 as the standard writes it. */
        fun kHighPass(sampleRate: Int): Biquad {
            val k = tan(PI * HIGHPASS_HZ / sampleRate)
            val a0 = 1.0 + k / HIGHPASS_Q + k * k
            return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / HIGHPASS_Q + k * k) / a0)
        }

        /** BS.1770 weights in the usual channel order (L R C LFE Ls Rs ...): surrounds 1.41, LFE excluded. */
        fun channelWeights(channels: Int): DoubleArray = DoubleArray(channels) { c ->
            when {
                channels >= 6 && c == 3 -> 0.0
                channels >= 6 && c >= 4 -> 10.0.pow(1.5 / 10.0)
                else -> 1.0
            }
        }
    }
}
