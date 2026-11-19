package app.cadence.playback

import app.cadence.state.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowTest {
    private val mb = 1024L * 1024L

    @Test
    fun theWholeSongWindowOnlyFillsOnMobileDataWhenAllowed() {
        val s = UserSettings(streamAheadMb = 500, streamBehindMb = 300, streamCacheMb = 2048, streamPreloadNextSec = 30, streamPreloadPreviousSec = 15)
        val wifi = streamConfigFor(s, metered = false)
        assertEquals(500 * mb, wifi.aheadBytes)
        assertEquals(300 * mb, wifi.behindBytes)
        val mobile = streamConfigFor(s, metered = true)
        assertEquals(0L, mobile.aheadBytes)
        assertEquals(0L, mobile.behindBytes)
        // The neighbours' opening and the cache cap apply on any network.
        assertEquals(30, mobile.nextSeconds)
        assertEquals(15, mobile.previousSeconds)
        assertEquals(2048 * mb, mobile.cacheBytes)
        val allowed = streamConfigFor(s.copy(streamWindowOnMobile = true), metered = true)
        assertEquals(500 * mb, allowed.aheadBytes)
    }

    @Test
    fun everySettingThatShapesTheWindowRecomputesIt() {
        val base = UserSettings()
        val changes = listOf<(UserSettings) -> UserSettings>(
            { it.copy(streamAheadMb = it.streamAheadMb + 1) },
            { it.copy(streamBehindMb = it.streamBehindMb + 1) },
            { it.copy(streamWindowOnMobile = !it.streamWindowOnMobile) },
            { it.copy(streamPreloadNextSec = it.streamPreloadNextSec + 1) },
            { it.copy(streamPreloadPreviousSec = it.streamPreloadPreviousSec + 1) },
            { it.copy(streamCacheMb = it.streamCacheMb + 1) },
            { it.copy(streamCodec = if (it.streamCodec == "aac") "alac" else "aac") },
            { it.copy(bitDepth = if (it.bitDepth == 24) 16 else 24) },
            { it.copy(maxSampleRate = it.maxSampleRate + 48000) },
        )
        for ((i, change) in changes.withIndex()) {
            assertNotEquals("change #$i does not recompute the window", streamWindowKey(base), streamWindowKey(change(base)))
        }
        // Settings unrelated to streaming leave it alone.
        assertEquals(streamWindowKey(base), streamWindowKey(base.copy(karaoke = !base.karaoke, haptics = !base.haptics)))
    }

    @Test
    fun skipSilenceWinsOverHiResOutput() {
        assertTrue(useFloatOutput(UserSettings(hiResOutput = true, skipSilence = false)))
        assertFalse(useFloatOutput(UserSettings(hiResOutput = true, skipSilence = true)))
        assertFalse(useFloatOutput(UserSettings(hiResOutput = false, skipSilence = false)))
    }
}
