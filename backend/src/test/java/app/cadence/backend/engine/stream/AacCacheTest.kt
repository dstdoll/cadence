package app.cadence.backend.engine.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AacCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("aac-cache-test").toFile()

    @Test
    fun cacheHitReturnsFileAndSkipsRefetch() {
        val dir = tempDir()
        try {
            val calls = AtomicInteger(0)
            val cache = AacCache(dir, AacCache.TrackFetcher { _, out ->
                calls.incrementAndGet()
                out.writeBytes(byteArrayOf(1, 2, 3, 4))
                true
            })
            val first = cache.fetchOne("1568453070")
            assertNotNull(first)
            assertEquals(1, calls.get())

            val second = cache.cachedFile("1568453070")
            assertNotNull(second)
            assertEquals(4L, second!!.length())
            assertEquals(4L, cache.sizeOnDisk())

            // Fetching again is a no-op
            assertNotNull(cache.fetchOne("1568453070"))
            assertEquals(1, calls.get())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedFetchLeavesNoTmpOrFinalFile() {
        val dir = tempDir()
        try {
            val cache = AacCache(dir, AacCache.TrackFetcher { _, out ->
                out.writeBytes(byteArrayOf(9, 9))
                false
            })
            assertNull(cache.fetchOne("111"))
            assertNull(cache.cachedFile("111"))
            assertEquals(0L, cache.sizeOnDisk())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun trimToBudgetEvictsOldestUnprotectedFiles() {
        val dir = tempDir()
        try {
            val cache = AacCache(dir, AacCache.TrackFetcher { id, out ->
                out.writeBytes(ByteArray(100) { id.hashCode().toByte() })
                true
            })
            val f1 = cache.fetchOne("t1")!!
            val f2 = cache.fetchOne("t2")!!
            val f3 = cache.fetchOne("t3")!!
            f1.setLastModified(1_000L)
            f2.setLastModified(2_000L)
            f3.setLastModified(3_000L)

            // Budget = 200 bytes, protect t1 -> t2 (oldest unprotected) must be evicted, t1 & t3 kept.
            cache.trimToBudget(200L, protect = setOf("t1"))
            assertNotNull(cache.cachedFile("t1"))
            assertNull(cache.cachedFile("t2"))
            assertNotNull(cache.cachedFile("t3"))
            assertEquals(200L, cache.sizeOnDisk())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setWindowPrefetchesInBackgroundAndClearRemovesAll() {
        val dir = tempDir()
        try {
            val latch = CountDownLatch(2)
            val cache = AacCache(dir, AacCache.TrackFetcher { _, out ->
                out.writeBytes(ByteArray(64))
                latch.countDown()
                true
            })
            cache.setWindow(listOf("a", "b"), cacheBytes = 1024L)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            Thread.sleep(50) // allow renameTo to complete
            assertNotNull(cache.cachedFile("a"))
            assertNotNull(cache.cachedFile("b"))

            cache.clear()
            assertNull(cache.cachedFile("a"))
            assertEquals(0L, cache.sizeOnDisk())
        } finally {
            dir.deleteRecursively()
        }
    }
}
