package app.cadence.playback.dsp

import kotlin.math.pow

/**
 * The replay-gain / preamp DSP stage: a single linear gain applied to every sample, optionally
 * hard-clamped to full scale so a boost can't clip. This is the first stage in the sink chain
 * (before tone and the equalizer), so later stages see already-levelled audio.
 *
 * The gain is resolved once per stream from the config: a track's own replay-gain value when it has
 * one, otherwise the "no info" fallback, plus the master preamp — all in dB, converted to linear
 * here. Stateless across samples (a constant multiply), so [reset] is a no-op; still per-sink-owned
 * and single-threaded like the other engines. [active] is false for unity gain so the sink can skip it.
 */
class ReplayGainEngine(effectiveGainDb: Double, private val preventClip: Boolean) {
    private val gain: Double = 10.0.pow(effectiveGainDb / 20.0)

    /** A boost needs the limiter; unity gain (and pure attenuation, which can't clip) does not. */
    val active: Boolean = effectiveGainDb != 0.0

    fun reset() { /* constant gain: no state to clear */ }

    fun processSample(@Suppress("UNUSED_PARAMETER") channel: Int, x: Double): Double {
        val y = x * gain
        return if (preventClip && gain > 1.0) y.coerceIn(-1.0, 1.0) else y
    }

    companion object {
        /** Resolves the dB the stage applies: the track's own gain when present, else the fallback. */
        fun effectiveDb(trackGainDb: Double?, noInfoDb: Double, preampDb: Double): Double =
            (trackGainDb ?: noInfoDb) + preampDb
    }
}
