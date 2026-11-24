package app.cadence.playback

import app.cadence.backend.engine.stream.StreamConfig
import app.cadence.state.settings.UserSettings
import kotlin.math.abs

private const val MEGABYTE = 1024L * 1024L

internal val STREAM_PRELOAD_NEXT_STOPS = listOf(15, 30, 45, 60, -1)
internal val STREAM_PRELOAD_PREV_STOPS = listOf(15, 30, 45, 60)
internal val STREAM_WINDOW_STOPS_MB = listOf(128, 256, 512, 1024, 2048)
internal val STREAM_CACHE_STOPS_MB = listOf(128, 256, 512, 1024, 2048, 4096, 8192)

/** Returns the index of the stop in [stops] closest to [value]. */
internal fun snapStopIndex(value: Int, stops: List<Int>): Int {
    if (stops.isEmpty()) return 0
    val exact = stops.indexOf(value)
    if (exact >= 0) return exact
    return stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0
}

/**
 * How much the stream cache prepares around the playing song. The window of whole songs is a lot
 * of data, so on a metered connection it only fills when the user allowed it; the opening of the
 * next and previous songs is always prepared.
 */
internal fun streamConfigFor(s: UserSettings, metered: Boolean): StreamConfig {
    if (!s.streamCacheEnabled) {
        return StreamConfig(
            nextSeconds = 0,
            previousSeconds = 0,
            cacheBytes = 0L,
            aheadBytes = 0L,
            behindBytes = 0L,
        )
    }
    val wide = !metered || s.streamWindowOnMobile
    return StreamConfig(
        nextSeconds = s.streamPreloadNextSec,
        previousSeconds = s.streamPreloadPreviousSec,
        cacheBytes = s.streamCacheMb * MEGABYTE,
        aheadBytes = if (wide) s.streamAheadMb * MEGABYTE else 0,
        behindBytes = if (wide) s.streamBehindMb * MEGABYTE else 0,
    )
}

/**
 * Every setting that changes what the stream cache should hold. When any of them changes the
 * window is recomputed at once, so a running fill stops without waiting for the next song.
 * Quality is included because it selects which rendition is downloaded.
 */
internal fun streamWindowKey(s: UserSettings): List<Any> = listOf(
    s.streamCacheEnabled, s.streamPreloadNextSec, s.streamPreloadPreviousSec, s.streamCacheMb,
    s.streamAheadMb, s.streamBehindMb, s.streamWindowOnMobile,
    s.streamCodec, s.streamAtmos, s.bitDepth, s.maxSampleRate,
)

/**
 * Float output keeps 24-bit audio intact, but ExoPlayer bypasses its audio processors for float
 * PCM, which disables skip silence. An explicit "Skip silence" therefore wins over hi-res.
 */
internal fun useFloatOutput(s: UserSettings): Boolean = s.hiResOutput && !s.skipSilence
