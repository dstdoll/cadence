package app.cadence.playback

import app.cadence.state.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamWindowTest {

    @Test
    fun snapStopIndexMatchesExactAndNearestLegacyValues() {
        assertEquals(0, snapStopIndex(128, STREAM_CACHE_STOPS_MB))
        assertEquals(1, snapStopIndex(256, STREAM_CACHE_STOPS_MB))
        assertEquals(2, snapStopIndex(512, STREAM_CACHE_STOPS_MB))
        assertEquals(4, snapStopIndex(2048, STREAM_CACHE_STOPS_MB))
        // Legacy window values (100, 250, 500) snap to 128, 256, 512
        assertEquals(0, snapStopIndex(100, STREAM_WINDOW_STOPS_MB))
        assertEquals(1, snapStopIndex(250, STREAM_WINDOW_STOPS_MB))
        assertEquals(2, snapStopIndex(500, STREAM_WINDOW_STOPS_MB))
    }

    @Test
    fun disabledStreamCacheZerosAllBytesAndPreloads() {
        val settings = UserSettings(
            streamCacheEnabled = false,
            streamCacheMb = 2048,
            streamPreloadNextSec = 30,
            streamPreloadPreviousSec = 15,
            streamAheadMb = 512,
            streamBehindMb = 512,
        )
        val cfg = streamConfigFor(settings, metered = false)
        assertEquals(0, cfg.nextSeconds)
        assertEquals(0, cfg.previousSeconds)
        assertEquals(0L, cfg.cacheBytes)
        assertEquals(0L, cfg.aheadBytes)
        assertEquals(0L, cfg.behindBytes)
    }

    @Test
    fun enabledStreamCacheRespectsMeteredWindowFlag() {
        val settings = UserSettings(
            streamCacheEnabled = true,
            streamCacheMb = 1024,
            streamPreloadNextSec = 30,
            streamPreloadPreviousSec = 15,
            streamAheadMb = 512,
            streamBehindMb = 256,
            streamWindowOnMobile = false,
        )
        val meteredCfg = streamConfigFor(settings, metered = true)
        assertEquals(30, meteredCfg.nextSeconds)
        assertEquals(15, meteredCfg.previousSeconds)
        assertEquals(1024L * 1024L * 1024L, meteredCfg.cacheBytes)
        assertEquals(0L, meteredCfg.aheadBytes)
        assertEquals(0L, meteredCfg.behindBytes)

        val wifiCfg = streamConfigFor(settings, metered = false)
        assertEquals(512L * 1024L * 1024L, wifiCfg.aheadBytes)
        assertEquals(256L * 1024L * 1024L, wifiCfg.behindBytes)
    }
}
