package app.cadence.backend.engine.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Downloads keep two segments in flight and decrypt each one as soon as it arrives, in order,
 * instead of fetching and decrypting strictly one after the other (or downloading everything
 * before decrypting anything).
 */
class SegmentPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class TimedSource(val lengths: List<Int>, val fetchMs: Long, val clearMs: Long) : SegmentSource {
        val remote: ByteArray = Random(3).nextBytes(lengths.sum())
        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val fetchEnded = Collections.synchronizedMap(HashMap<Long, Long>())
        val clearStarted = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        @Volatile var failOffset: Long? = null

        val media: HlsMedia
            get() {
                var at = 0L
                return HlsMedia("https://cdn.example/p.mp4", null, null, lengths.map { len ->
                    HlsSegment("https://cdn.example/p.mp4", at, len.toLong(), 15.0, "skd://k").also { at += len }
                })
            }

        override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(fetchMs)
                if (offset == failOffset) throw IOException("HTTP 503")
                return remote.copyOfRange(offset!!.toInt(), (offset + length!!).toInt())
            } finally {
                inFlight.decrementAndGet()
                fetchEnded[offset!!] = System.nanoTime()
            }
        }

        override fun clearInit(originalInit: ByteArray) = ByteArray(0)

        override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray): ByteArray {
            clearStarted += segment.offset to System.nanoTime()
            Thread.sleep(clearMs)
            return ByteArray(encrypted.size) { (encrypted[it].toInt() xor 0x5A).toByte() }
        }
    }

    private fun stream(source: TimedSource): ClearStream {
        val media = source.media
        return ClearStream("t", media, ByteArray(0), StreamLayout(ByteArray(16), LongArray(media.segments.size) { media.segments[it].length }), tmp.newFolder(), source)
    }

    @Test
    fun twoDownloadsInFlightAndEachSegmentDecryptedAsSoonAsItArrives() {
        val source = TimedSource(List(6) { 1000 }, fetchMs = 120, clearMs = 60)
        val stream = stream(source)
        val order = ArrayList<Int>()
        val started = System.nanoTime()
        stream.pipelined(0 until 6) { index, _ -> order += index }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("segments were not handed over in order", (0 until 6).toList(), order)
        assertEquals("downloads in flight", 2, source.maxInFlight.get())
        // Decrypted in order, and the first decrypt started before the last download finished.
        val clears = source.clearStarted.toList()
        assertEquals(source.media.segments.map { it.offset }, clears.map { it.first })
        val lastFetchEnd = source.fetchEnded.values.max()
        assertTrue("decrypting waited for the whole download", clears.first().second < lastFetchEnd)
        // Strictly sequential would take 6 x (120 + 60) = 1080 ms; two in flight overlaps them.
        assertTrue("took ${elapsedMs}ms; no overlap between download and decrypt", elapsedMs < 900)
        assertTrue(stream.isComplete)
    }

    @Test
    fun thePackageIsByteIdenticalAndCachedSegmentsAreNotFetchedAgain() {
        val source = TimedSource(listOf(700, 1300, 50, 999), fetchMs = 5, clearMs = 1)
        val stream = stream(source)
        stream.segment(1) // e.g. played earlier
        val before = source.fetchEnded.size

        val out = tmp.newFile("pkg.mp4")
        val progress = ArrayList<Int>()
        stream.writePackage(out) { done, _ -> progress += done }

        val expected = ByteArray(16) + ByteArray(source.remote.size) { (source.remote[it].toInt() xor 0x5A).toByte() }
        assertArrayEquals(expected, out.readBytes())
        assertEquals(listOf(0, 1, 2, 3, 4), progress)
        assertEquals("a cached segment was downloaded again", before + 3, source.fetchEnded.size)
    }

    @Test
    fun aFailedSegmentStopsTheDownloadWithItsError() {
        val source = TimedSource(List(5) { 400 }, fetchMs = 10, clearMs = 1).apply { failOffset = 800 }
        val stream = stream(source)
        val delivered = ArrayList<Int>()
        val error = assertThrows(IOException::class.java) { stream.pipelined(0 until 5) { i, _ -> delivered += i } }
        assertTrue(error.message!!.contains("503"))
        assertEquals("segments before the failure are still delivered in order", listOf(0, 1), delivered)
        assertTrue("no partial file left for the failed segment", File(tmp.root.listFiles()!!.first(), "2.seg").exists().not())
    }
}
