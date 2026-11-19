package app.cadence.backend.engine

import app.cadence.backend.engine.media.Http
import app.cadence.backend.engine.media.mapByteRange
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Throttling, slow links, servers that ignore Range, and the download queue under load. */
class NetworkHardeningTest {
    private fun httpDate(d: Date) = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(d)

    @Test
    fun retryAfterIsReadAsSecondsOrAsADateOnTheServersClock() {
        val now = Date(1_800_000_000_000)
        assertEquals(120, Http.retryAfterSeconds(" 120 ", null, now))
        assertEquals(Int.MAX_VALUE, Http.retryAfterSeconds("99999999999", null, now))
        // A date 30 s after the server's Date header, whatever the phone's clock says.
        val server = Date(1_700_000_000_000)
        assertEquals(30, Http.retryAfterSeconds(httpDate(Date(server.time + 30_000)), server, now))
        assertEquals(0, Http.retryAfterSeconds(httpDate(Date(server.time - 5_000)), server, now))
        assertEquals(45, Http.retryAfterSeconds(httpDate(Date(now.time + 45_000)), null, now))
        assertNull(Http.retryAfterSeconds("soon", null, now))
        assertNull(Http.retryAfterSeconds(null, null, now))
    }

    @Test
    fun bigRangesGetTimeToArriveOnASlowLink() {
        assertEquals(45, Http.rangeTimeoutSeconds(0))
        // ~10 MB hi-res segment: 45 s + 160 s at 64 KB/s.
        assertTrue(Http.rangeTimeoutSeconds(10L * 1024 * 1024) >= 200)
    }

    @Test
    fun aServerIgnoringRangeIsReadOnlyForTheRange() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        assertArrayEquals(data.copyOfRange(1200, 1700), Http.readRange(Buffer().write(data), 1200, 500))
        assertThrows(EOFException::class.java) { Http.readRange(Buffer().write(data), 4800, 500) }
    }

    @Test
    fun aMapRangeWithoutOffsetStartsAtZero() {
        assertEquals(1000L to 714L, mapByteRange("#EXT-X-MAP:URI=\"p.mp4\",BYTERANGE=\"714@1000\""))
        assertEquals(0L to 714L, mapByteRange("#EXT-X-MAP:URI=\"p.mp4\",BYTERANGE=\"714\""))
        assertNull(mapByteRange("#EXT-X-MAP:URI=\"init.mp4\""))
    }

    @Test
    fun theDownloadQueueNeverDropsAQueuedJob() {
        fun job(i: Int, phase: String) = DownloadEngine.Job("t$i", "T$i", "A", phase = phase, order = i.toLong())
        val queued = (0 until 250).map { job(it, "queued") }
        val done = (250 until 260).map { job(it, "done") }
        val trimmed = trimJobs(done + queued)
        assertEquals("a queued job was dropped", 250, trimmed.count { it.active })
        assertTrue(trimmed.none { !it.active })

        val mixed = (0 until 100).map { job(it, "done") } + (100 until 250).map { job(it, if (it % 2 == 0) "queued" else "downloading") }
        val kept = trimJobs(mixed)
        assertEquals(200, kept.size)
        assertEquals(150, kept.count { it.active })
        assertEquals((50 until 100).map { "t$it" }, kept.filter { !it.active }.map { it.trackId }) // the newest finished rows
        val small = mixed.take(10)
        assertSame("a list under the cap is returned as is", small, trimJobs(small))
    }

    @Test
    fun everyCallerSharesOneEnginePerAccount() {
        val a = AmpConfig("dev", "user", "us", "en-US")
        assertSame(AmpEngine.shared(a), AmpEngine.shared(a.copy()))
        assertNotSame(AmpEngine.shared(a), AmpEngine.shared(a.copy(storefront = "gb")))
    }
}
