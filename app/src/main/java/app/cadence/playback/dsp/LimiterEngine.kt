package app.cadence.playback.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A look-ahead peak limiter, the last stage before the output format: it keeps every sample at or
 * below [thresholdDb] without the distortion of a hard clip.
 *
 * Audio is delayed by the look-ahead (about 2 ms). For each incoming frame the gain that keeps it under
 * the threshold is computed; the minimum over the look-ahead window, smoothed by a moving average of
 * the same length, is applied to the delayed audio. The gain therefore starts falling before a peak
 * leaves the delay line and reaches its value exactly when it does, with no step that would click.
 * Releases are exponential. Channels are linked (one gain for the frame), so the stereo image stays.
 *
 * Below the threshold the gain stays at 1.0 and the stage only delays, leaving every sample exactly as
 * it was. Out of [engaged] it only delays: incoming frames ask for no reduction, so whatever reduction is
 * left releases and the frames already in the delay line keep the protection they were given. The delay
 * itself never changes, so switching between the two never clicks. Not thread-safe: the sink owns it.
 */
class LimiterEngine(
    sampleRate: Int,
    private val channels: Int,
    thresholdDb: Double = -0.5,
    lookaheadMs: Double = 2.0,
    releaseMs: Double = 80.0,
) {
    private val threshold = 10.0.pow(thresholdDb / 20.0)
    /** Delay and smoothing length, in frames. */
    val lookahead: Int = max(1, (sampleRate * lookaheadMs / 1000.0).roundToInt())
    private val release = exp(-1.0 / (sampleRate * releaseMs / 1000.0))
    private val delay = Array(channels) { DoubleArray(lookahead) }
    // One more than the delay, so the minimum still covers a peak on the frame it leaves the delay line.
    private val targets = DoubleArray(lookahead + 1) { 1.0 }
    private val box = DoubleArray(lookahead) { 1.0 }
    private var boxSum = lookahead.toDouble()
    private var envelope = 1.0
    private var at = 0
    private var targetAt = 0

    /** Whether incoming frames are limited; out of it the stage only delays them. */
    var engaged = true

    /** The gain applied to the last frame (1.0 means untouched). */
    var gain = 1.0
        private set

    fun reset() {
        delay.forEach { it.fill(0.0) }
        targets.fill(1.0); box.fill(1.0)
        boxSum = lookahead.toDouble(); envelope = 1.0; gain = 1.0; at = 0; targetAt = 0
    }

    /** Takes one frame in [frame] and leaves in it the delayed, limited frame. */
    fun processFrame(frame: DoubleArray) {
        var peak = 0.0
        for (c in 0 until channels) peak = max(peak, abs(frame[c]))
        targets[targetAt] = if (engaged && peak > threshold) threshold / peak else 1.0
        targetAt = (targetAt + 1) % targets.size
        var wanted = 1.0
        for (t in targets) wanted = min(wanted, t)
        envelope = if (wanted < envelope) wanted else wanted + (envelope - wanted) * release
        boxSum += envelope - box[at]
        box[at] = envelope
        // Summing leaves rounding residue, so a gain within 1e-9 of unity counts as unity: untouched.
        gain = (boxSum / lookahead).let { if (it > UNITY) 1.0 else it }
        for (c in 0 until channels) {
            val out = delay[c][at]
            delay[c][at] = frame[c]
            // The smoothing guarantees the threshold; the clamp only guards rounding.
            frame[c] = if (gain == 1.0) out else (out * gain).coerceIn(-threshold, threshold)
        }
        at = (at + 1) % lookahead
        if (at == 0) boxSum = box.sum() // keeps the running sum from drifting over hours of audio
    }

    private companion object {
        const val UNITY = 1.0 - 1e-9
    }
}
