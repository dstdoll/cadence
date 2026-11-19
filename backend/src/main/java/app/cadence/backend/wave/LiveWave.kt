package app.cadence.backend.wave

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The waveform of a track drawn while it plays, ported from the desktop visualiser's `onda-vivo.js`.
 *
 * It is the same measure as a waveform computed from a file: the RMS of each of [SEGMENTS]
 * slices of the track, relative to the 99.5th percentile, shaped by `0.06 + 0.94·x^1.15`. The
 * analyser supplies absolute power per 23 ms block; this spreads it over the slices that were
 * playing and normalises against what has been heard so far (a louder passage later shrinks the
 * earlier ones: there is no way to know before it plays). Seeks, pauses and late samples are
 * detected from the gap between playback position and the clock, so power is never credited to
 * the wrong part of the track.
 */
class LiveWave {
    /** What the seek bar draws: 0 = not measured yet (a flat line), 1..255 = height. */
    val levels = IntArray(SEGMENTS)
    private val sum = DoubleArray(SEGMENTS)
    private val count = DoubleArray(SEGMENTS)
    private val rms = DoubleArray(SEGMENTS)
    private val order = DoubleArray(SEGMENTS)
    private var lastUs = 0L
    private var positionUs = -1L
    private var guardUntilUs = 0L
    private var paintedUs = 0L
    private var changed = false

    /** Continuity broke (pause, no analyser data, another track): the next sample only marks a start. */
    fun cut() { positionUs = -1 }

    /**
     * Called every frame with what the analyser gathered since it was last emptied: the sum of
     * block powers and how many blocks. Returns true when the caller must empty its accumulator
     * (the data was used or discarded), false to keep gathering.
     */
    fun sample(nowUs: Long, posUs: Long, lengthUs: Long, rate: Double, energy: Double, blocks: Double): Boolean {
        val dt = nowUs - lastUs
        if (dt < SAMPLE_US || !(blocks > 0)) return false
        val previous = positionUs
        lastUs = nowUs
        positionUs = posUs
        if (previous < 0 || dt > MAX_INTERVAL_US || lengthUs <= 0) return true
        val r = if (rate > 0) rate else 1.0
        val advance = posUs - previous
        if (advance <= 0 || abs(advance - dt * r) > JUMP_US) {
            guardUntilUs = nowUs + GUARD_US
            return true
        }
        if (nowUs < guardUntilUs) return true
        // What was gathered played between the previous sample and this one, shifted by capture latency.
        val latency = LATENCY_US * r
        spread((previous - latency) / lengthUs * SEGMENTS, (posUs - latency) / lengthUs * SEGMENTS, energy, blocks)
        return true
    }

    private fun spread(from: Double, to: Double, energy: Double, blocks: Double) {
        if (!(to > from) || to <= 0 || from >= SEGMENTS) return
        val width = to - from
        val end = min(SEGMENTS - 1, ceil(to).toInt() - 1)
        for (i in max(0, floor(from).toInt())..end) {
            val w = (min(to, i + 1.0) - max(from, i.toDouble())) / width
            if (w > 0) { sum[i] += energy * w; count[i] += blocks * w }
        }
        changed = true
    }

    /** True when [levels] changed and must be redrawn (at most every 150 ms, only on new data). */
    fun paint(nowUs: Long, lengthUs: Long): Boolean {
        if (!changed || nowUs - paintedUs < PAINT_US) return false
        paintedUs = nowUs
        changed = false
        compute(lengthUs)
        return true
    }

    /** Recomputes [levels]; short gaps between measured slices are interpolated, the rest stay 0. */
    fun compute(lengthUs: Long) {
        var measured = 0
        for (i in 0 until SEGMENTS) {
            if (count[i] >= MIN_BLOCKS) { rms[i] = sqrt(sum[i] / count[i]); order[measured++] = rms[i] } else rms[i] = -1.0
        }
        if (measured == 0) { levels.fill(0); return }
        val reference = max(percentile(order, measured, 0.995), 1e-5)
        val gap = if (lengthUs > 0) MAX_GAP_US.toDouble() * SEGMENTS / lengthUs else 0.0
        var last = -1
        for (i in 0 until SEGMENTS) {
            if (rms[i] < 0) { levels[i] = 0; continue }
            levels[i] = curve(rms[i] / reference)
            val d = i - last
            if (last >= 0 && d > 1 && d - 1 <= gap) {
                for (j in 1 until d) levels[last + j] = curve((rms[last] + (rms[i] - rms[last]) * j / d) / reference)
            }
            last = i
        }
    }

    companion object {
        const val SEGMENTS = 1000
        private const val SAMPLE_US = 50_000L
        private const val PAINT_US = 150_000L
        private const val LATENCY_US = 50_000L
        private const val JUMP_US = 250_000L
        private const val GUARD_US = 300_000L
        private const val MAX_INTERVAL_US = 250_000L
        private const val MAX_GAP_US = 3_000_000L
        private const val MIN_BLOCKS = 0.5

        /** Height 0..255 for a slice at [x] times the reference loudness. */
        fun curve(x: Double): Int = Math.round(255 * (0.06 + 0.94 * min(1.0, x).pow(1.15))).toInt()

        /** The k-th smallest of a[0, n), reordering a in place (Hoare quickselect, no allocation). */
        private fun select(a: DoubleArray, n: Int, k: Int): Double {
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val pivot = a[(lo + hi) shr 1]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (a[i] < pivot) i++
                    while (a[j] > pivot) j--
                    if (i <= j) { val t = a[i]; a[i] = a[j]; a[j] = t; i++; j-- }
                }
                if (k <= j) hi = j else if (k >= i) lo = i else break
            }
            return a[k]
        }

        /** Percentile [q] in 0..1 of a[0, n) with linear interpolation, as numpy does (reorders a). */
        fun percentile(a: DoubleArray, n: Int, q: Double): Double {
            val x = q * (n - 1)
            val k = floor(x).toInt()
            val f = x - k
            val low = select(a, n, k)
            if (f == 0.0 || k + 1 >= n) return low
            var high = Double.POSITIVE_INFINITY
            for (i in k + 1 until n) if (a[i] < high) high = a[i]
            return low + (high - low) * f
        }
    }
}
