package app.cadence.backend.wave

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Real-time spectrum analysis of what is playing, ported from the desktop visualiser's `nivel.py` so both draw
 * the same thing from the same audio. Every [HOP] mono samples (23 ms at 44.1 kHz) it yields
 * eight frequency bands (40 Hz to 12 kHz), the overall level and a bass-hit pulse, all 0..1, plus
 * the block's absolute power in dBFS.
 *
 * The bands are relative on purpose: each is measured against its own recent range, so a quiet
 * master and a heavily compressed one move a visualiser alike. [Frame.energyDb] is the absolute
 * figure, and it is what [LiveWave] accumulates into a waveform faithful to the track's loudness.
 */
class BandAnalyzer(sampleRate: Int = 44_100) {

    class Frame {
        /** Eight bands, low to high. */
        val bands = FloatArray(BANDS)
        var level = 0f
        var hit = 0f
        /** Mean power of the block in dBFS; -120 is digital silence. */
        var energyDb = -120f
    }

    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }
    private val samples = FloatArray(N)
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val binStart = IntArray(BANDS)
    private val binEnd = IntArray(BANDS)
    private val values = DoubleArray(BANDS + 1)
    private val norm = DoubleArray(BANDS + 1)
    private var hi: DoubleArray? = null
    private var lo = DoubleArray(BANDS + 1)
    private var slow = DoubleArray(BANDS + 1)
    private var previousLow = 0.0
    private val driftDb = 2.0 * HOP / sampleRate
    private val slowRate = 1.0 - exp(-(HOP.toDouble() / sampleRate) / 1.2)

    init {
        for (b in 0 until BANDS) {
            // First and one-past-last FFT bin whose centre frequency falls in [EDGES[b], EDGES[b+1]).
            binStart[b] = firstBinAtOrAbove(EDGES[b].toDouble(), sampleRate)
            binEnd[b] = firstBinAtOrAbove(EDGES[b + 1].toDouble(), sampleRate)
        }
    }

    private fun firstBinAtOrAbove(hz: Double, rate: Int): Int {
        var k = 0
        while (k <= N / 2 && k.toDouble() * rate / N < hz) k++
        return k
    }

    /** Consumes exactly [HOP] mono samples in -1..1 and writes the analysis into [out]. */
    fun process(block: FloatArray, out: Frame) {
        require(block.size == HOP) { "block must hold $HOP samples" }
        System.arraycopy(samples, HOP, samples, 0, N - HOP)
        System.arraycopy(block, 0, samples, N - HOP, HOP)
        for (i in 0 until N) { re[i] = samples[i] * window[i]; im[i] = 0.0 }
        fft(re, im)

        for (b in 0 until BANDS) {
            var sum = 0.0
            for (k in binStart[b] until binEnd[b]) sum += re[k] * re[k] + im[k] * im[k]
            val count = binEnd[b] - binStart[b]
            values[b] = 10.0 * log10((if (count > 0) sum / count else 0.0) + 1e-12)
        }
        var power = 0.0
        for (s in block) power += s.toDouble() * s
        val levelDb = 10.0 * log10(power / HOP + 1e-12) + 60.0
        values[BANDS] = levelDb

        if (levelDb < -10.0) {
            norm.fill(0.0) // about -70 dBFS: a pause or real silence
        } else {
            val top = hi ?: DoubleArray(BANDS + 1).also { fresh ->
                for (i in fresh.indices) { fresh[i] = values[i] + 3.0; lo[i] = values[i] - 9.0; slow[i] = values[i] }
                hi = fresh
            }
            for (i in 0..BANDS) {
                top[i] = max(values[i], top[i] - driftDb)
                lo[i] = min(values[i], lo[i] + driftDb)
                val span = max(top[i] - lo[i], MIN_RANGE_DB)
                val energy = ((values[i] - (top[i] - span)) / span).coerceIn(0.0, 1.0).pow(GAMMA)
                slow[i] += (values[i] - slow[i]) * slowRate
                val hits = ((values[i] - slow[i]) / HIT_DB).coerceIn(0.0, 1.0)
                norm[i] = (0.4 * energy + 0.65 * hits).coerceIn(0.0, 1.0)
            }
        }
        val low = norm[0] + norm[1]
        val hit = max(0.0, low - previousLow) * 1.8
        previousLow = low

        for (b in 0 until BANDS) out.bands[b] = norm[b].toFloat()
        out.level = norm[BANDS].toFloat()
        out.hit = min(1.0, hit).toFloat()
        out.energyDb = (levelDb - 60.0).toFloat()
    }

    /** In-place iterative radix-2 FFT; [re]/[im] must have a power-of-two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle); val wi = sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val N = 2048
        const val HOP = 1024
        const val BANDS = 8
        private val EDGES = intArrayOf(40, 90, 180, 350, 700, 1400, 2800, 5600, 12000)
        private const val MIN_RANGE_DB = 6.0
        private const val GAMMA = 1.5
        private const val HIT_DB = 6.0
    }
}
