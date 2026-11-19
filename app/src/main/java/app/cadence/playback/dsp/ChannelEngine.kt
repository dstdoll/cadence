package app.cadence.playback.dsp

/**
 * Cross-channel stereo processing — **balance** and **stereo width** — applied at the frame level,
 * because unlike the per-channel stages (replay gain, tone, eq) it mixes the left and right channels
 * together. Stereo only: on mono or multichannel audio it stays inactive and passes through.
 *
 * - **balance** in `[-1, +1]`: 0 centred, +1 full right (left muted), -1 full left. Linear pan law —
 *   the side you turn toward keeps unity gain and only the far channel is attenuated, so there is no
 *   centre boost and a centred mix is left untouched.
 * - **width** in `[0, 2]` on the mid/side decomposition (`mid=(L+R)/2`, `side=(L-R)/2`):
 *   `L = mid + width*side`, `R = mid - width*side`. 1 is the original field, 0 collapses to mono
 *   (mid only), 2 doubles the stereo difference for a wider image.
 *
 * Balance is applied first, then width.
 */
class ChannelEngine(channels: Int, balance: Double, private val width: Double) {
    private val stereo = channels == 2
    private val leftGain = if (balance > 0) 1.0 - balance else 1.0
    private val rightGain = if (balance < 0) 1.0 + balance else 1.0

    /** Idle (pure pass-through) unless the audio is stereo and something is actually being changed. */
    val active: Boolean = stereo && (balance != 0.0 || width != 1.0)

    fun reset() {}

    /** Transforms one stereo frame `[L, R]` in place. No-op unless [active]. */
    fun processFrame(frame: DoubleArray) {
        if (!active) return
        var l = frame[0] * leftGain
        var r = frame[1] * rightGain
        if (width != 1.0) {
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5 * width
            l = mid + side
            r = mid - side
        }
        frame[0] = l
        frame[1] = r
    }
}
