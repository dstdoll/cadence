package app.cadence.playback.dsp

/**
 * The tone-control DSP: a bass low-shelf and a treble high-shelf applied per channel (each channel
 * keeps its own filter state). It is the coarse "bass/treble" pair, distinct from the many-band
 * [EqEngine]; both stages can be on at once (tone runs first, then the equalizer).
 *
 * Built once per stream format / config and driven sample-by-sample from the audio thread, so it is
 * deliberately **not** thread-safe — the sink owns one instance and is the only caller. [active] is
 * false when both shelves are flat, letting the sink skip the stage entirely.
 */
class ToneEngine(
    sampleRate: Int,
    channels: Int,
    bassDb: Double,
    trebleDb: Double,
) {
    private val bass: Array<Biquad>? =
        if (bassDb != 0.0) Array(channels) { Biquad.lowShelf(sampleRate, BASS_HZ, bassDb) } else null
    private val treble: Array<Biquad>? =
        if (trebleDb != 0.0) Array(channels) { Biquad.highShelf(sampleRate, TREBLE_HZ, trebleDb) } else null

    val active: Boolean = bass != null || treble != null

    fun reset() {
        bass?.forEach { it.reset() }
        treble?.forEach { it.reset() }
    }

    /** Continues [old]'s shelf memory where both have the shelf, so a change does not restart them. */
    fun copyStateFrom(old: ToneEngine?) {
        if (old == null) return
        val ob = old.bass; val ot = old.treble
        bass?.let { b -> if (ob != null && ob.size == b.size) b.indices.forEach { b[it].copyStateFrom(ob[it]) } }
        treble?.let { t -> if (ot != null && ot.size == t.size) t.indices.forEach { t[it].copyStateFrom(ot[it]) } }
    }

    /** The steady-state gain at [freq] Hz in dB of both shelves. */
    fun magnitudeDb(freq: Double, sampleRate: Int): Double =
        (bass?.firstOrNull()?.magnitudeDb(freq, sampleRate) ?: 0.0) + (treble?.firstOrNull()?.magnitudeDb(freq, sampleRate) ?: 0.0)

    /** Filters one sample of [channel] (0-based) through the bass then treble shelves. */
    fun processSample(channel: Int, x: Double): Double {
        var y = x
        bass?.let { y = it[channel].process(y) }
        treble?.let { y = it[channel].process(y) }
        return y
    }

    companion object {
        /** Shelf corners: bass lifts below 100 Hz, treble lifts above 10 kHz. */
        const val BASS_HZ = 100.0
        const val TREBLE_HZ = 10_000.0
    }
}
