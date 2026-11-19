package app.cadence.backend.wave

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The Kotlin ports must draw what the desktop visualiser draws. The audio is an original synthetic
 * piece (the committed file is its first 8 s); the references were produced from the full 40 s by the
 * visualiser's own level and waveform code.
 */
class WaveParityTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/wave/$name")!!.readBytes()

    @Test
    fun analyserMatchesNivelPy() {
        val pcm = ByteBuffer.wrap(resource("music-8s.s16")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val reference = String(resource("nivel_ref.txt")).trim().lines().map { l -> l.split(' ').map(String::toDouble) }
        val analyser = BandAnalyzer(44_100)
        val frame = BandAnalyzer.Frame()
        val block = FloatArray(BandAnalyzer.HOP)
        val blocks = pcm.remaining() / 2 / BandAnalyzer.HOP
        var worst = 0.0
        var worstDb = 0.0
        for (n in 0 until blocks) {
            // nivel.py: float32 mean of the two channels, then /32768.
            for (i in block.indices) block[i] = ((pcm.get().toFloat() + pcm.get().toFloat()) / 2f) / 32768f
            analyser.process(block, frame)
            val expected = reference[n]
            for (b in 0 until 8) worst = maxOf(worst, abs(frame.bands[b] - expected[b]))
            worst = maxOf(worst, abs(frame.level - expected[8]), abs(frame.hit - expected[9]))
            worstDb = maxOf(worstDb, abs(frame.energyDb - expected[10]))
        }
        println("[wave] analyser blocks=$blocks worst band/level/hit diff=${"%.5f".format(worst)} worst energy diff=${"%.4f".format(worstDb)} dB")
        assertTrue("only $blocks blocks compared", blocks > 300)
        // The reference is printed with 3 decimals (2 for dB), so half a unit of rounding plus float noise.
        assertTrue("bands differ by $worst", worst < 0.002)
        assertTrue("energy differs by $worstDb dB", worstDb < 0.02)
    }

    @Test
    fun liveWaveMatchesOndaVivoJs() {
        val expected = JSONObject(String(resource("onda_ref.json")))
        val reference = String(resource("nivel_ref.txt")).trim().lines().map { l -> l.split(' ')[10].toDouble() }
        val length = 48_000_000L // samples of the full 40 s piece the references were made from
        val hop = 1024.0 / 44100 * 1e6
        val wave = LiveWave()
        var now = 1e6; var pos = 0.0; var energy = 0.0; var blocks = 0.0; var paints = 0
        for (i in reference.indices) {
            energy += 10.0.pow(reference[i] / 10); blocks += 1; now += hop; pos += hop
            if (i == 700) pos += 4e6       // a seek forward
            if (i == 1200) wave.cut()      // a pause
            if (wave.sample(now.roundToLong(), pos.roundToLong(), length, 1.0, energy, blocks)) { energy = 0.0; blocks = 0.0 }
            if (wave.paint(now.roundToLong(), length)) paints++
        }
        wave.compute(length)
        val v = expected.getJSONArray("v")
        assertEquals(LiveWave.SEGMENTS, expected.getInt("tramos"))
        assertArrayEquals(IntArray(v.length()) { v.getInt(it) }, wave.levels)
        assertEquals(expected.getInt("pintados"), paints)
        println("[wave] live wave: ${wave.levels.count { it > 0 }} of ${LiveWave.SEGMENTS} slices measured, identical to onda-vivo.js; repaints=$paints")

        val a = doubleArrayOf(5.0, 1.0, 9.0, 3.0, 7.0, 2.0, 8.0, 6.0, 4.0, 0.0, 11.0, 10.0)
        assertEquals(expected.getDouble("p995"), LiveWave.percentile(a.copyOf(), 12, 0.995), 1e-12)
        assertEquals(expected.getDouble("p50"), LiveWave.percentile(a.copyOf(), 12, 0.5), 1e-12)
    }
}
