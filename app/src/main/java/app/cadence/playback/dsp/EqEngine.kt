package app.cadence.playback.dsp

import kotlin.math.pow

/** The shape of an equalizer band. */
enum class EqShape { Peaking, LowShelf, HighShelf }

/** One equalizer band: a filter of [shape] at [freq] Hz with [gainDb] and quality (or shelf slope) [q]. */
data class EqBand(val freq: Double, val gainDb: Double, val q: Double, val shape: EqShape = EqShape.Peaking)

/**
 * The equalizer DSP: a preamp gain followed by a cascade of [Biquad]s, applied independently
 * per channel (each channel keeps its own filter state). Built once per stream format / config and
 * then driven sample-by-sample from the audio thread, so it is deliberately **not** thread-safe — the
 * sink owns one instance and is the only caller.
 *
 * [active] is false when the config is a no-op (0 dB preamp and every band flat), letting the sink
 * skip the whole stage and pass audio through untouched.
 */
class EqEngine(
    sampleRate: Int,
    private val channels: Int,
    private val preampDb: Double,
    bands: List<EqBand>,
) {
    private val preamp = 10.0.pow(preampDb / 20.0)
    private val filters: Array<Array<Biquad>> = Array(channels) {
        Array(bands.size) { i -> design(sampleRate, bands[i]) }
    }

    val active: Boolean = preampDb != 0.0 || bands.any { it.gainDb != 0.0 }

    fun reset() {
        for (ch in filters) for (f in ch) f.reset()
    }

    /** Continues [old]'s filter memory when the layout matches, so a change does not restart the filters. */
    fun copyStateFrom(old: EqEngine?) {
        if (old == null || old.filters.size != filters.size || old.filters.firstOrNull()?.size != filters.firstOrNull()?.size) return
        for (c in filters.indices) for (b in filters[c].indices) filters[c][b].copyStateFrom(old.filters[c][b])
    }

    /** The steady-state gain at [freq] Hz in dB: the preamp plus every band. */
    fun magnitudeDb(freq: Double, sampleRate: Int): Double =
        preampDb + (filters.firstOrNull()?.sumOf { it.magnitudeDb(freq, sampleRate) } ?: 0.0)

    /** Filters one sample of [channel] (0-based) through the preamp and the band cascade. */
    fun processSample(channel: Int, x: Double): Double {
        var y = x * preamp
        val ch = filters[channel]
        for (f in ch) y = f.process(y)
        return y
    }

    companion object {
        /** The RBJ cookbook filter for [band], the same formulas AutoEQ uses to design its profiles. */
        fun design(sampleRate: Int, band: EqBand): Biquad = when (band.shape) {
            EqShape.Peaking -> Biquad.peaking(sampleRate, band.freq, band.gainDb, band.q)
            EqShape.LowShelf -> Biquad.lowShelf(sampleRate, band.freq, band.gainDb, band.q)
            EqShape.HighShelf -> Biquad.highShelf(sampleRate, band.freq, band.gainDb, band.q)
        }

        /** A standard 10-band graphic-EQ layout (ISO octave centres), all flat. */
        fun flatGraphicBands(): List<EqBand> =
            listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
                .map { EqBand(it, 0.0, 1.0) }
    }
}
