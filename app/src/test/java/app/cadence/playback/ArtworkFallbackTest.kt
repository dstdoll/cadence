package app.cadence.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The notification art is the cover when it loads and the procedural art when it does not. */
class ArtworkFallbackTest {
    @Test
    fun theCoverWinsWhenItLoads() = runBlocking {
        assertEquals("cover", artworkOrFallback({ "cover" }, { "procedural" }))
    }

    @Test
    fun anUncachedCoverOfflineFallsBack() = runBlocking {
        assertEquals("procedural", artworkOrFallback<String>({ null }, { "procedural" }))
        assertEquals("procedural", artworkOrFallback<String>({ throw IOException("offline") }, { "procedural" }))
    }

    @Test
    fun nothingAtAllGivesNull() = runBlocking {
        assertNull(artworkOrFallback<String>({ null }, { null }))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowed(): Unit = runBlocking {
        artworkOrFallback<String>({ throw CancellationException("stopped") }, { "procedural" })
    }
}
