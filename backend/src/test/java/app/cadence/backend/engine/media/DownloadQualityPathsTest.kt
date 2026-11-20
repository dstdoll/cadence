package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against the one way per-download quality silently breaks: a download path that goes back to reading the
 * global quality settings (the cache key, or the old four-parameter resolve) instead of the download's own policy.
 */
class DownloadQualityPathsTest {
    private val downloader = File("src/main/java/app/cadence/backend/engine/media/EngineDownloader.kt").readText()

    @Test fun everyDownloadResolveUsesThePolicyNotTheFourOlderSettings() {
        val calls = Regex("""WrapperKeyClient\.resolve\(""").findAll(downloader).count()
        assertTrue("expected the downloader to resolve renditions", calls >= 3)
        assertFalse("a download resolve still passes the old parameters", Regex("""resolve\([^)]*prefs\.bitDepth""").containsMatchIn(downloader))
        assertFalse(Regex("""DownloadEngine\.preferences\.(codec|bitDepth|maxSampleRate|atmos)""").containsMatchIn(downloader))
        assertFalse("the resolver must not read the global download quality for a specific download",
            Regex("""val prefs = DownloadEngine\.preferences""").containsMatchIn(downloader))
    }

    @Test fun theDedicatedCacheIsAlwaysOpenedWithTheDownloadsOwnResolver() {
        assertTrue(downloader.contains("cache.stream(item.trackId, resolver)"))
        assertTrue(downloader.contains("DownloadStreamResolver(context, policy)"))
    }

    @Test fun aDepthLimitedPolicyNeverSharesPlaybacksCache() {
        assertTrue(downloader.contains("policy.ceiling.bitDepth < 24"))
        assertTrue(downloader.contains("&& !depthLimited"))
    }

    @Test fun theStagedQualityIsRecordedOnEveryPath() {
        assertEquals("the streaming, two-phase and m4p paths each set it", 3, Regex("""quality = (resolved\.plan\?\.spec\.orEmpty\(\)|plan\.spec|"aac:256")""").findAll(downloader).count())
    }
}
