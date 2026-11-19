package app.cadence.backend.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pacing and cache rules, on a virtual clock so the timing is exact. */
class AmpGateTest {
    private var now = 0L
    private fun gate(perSecond: Double = 4.0, burst: Int = 8) =
        AmpGate(perSecond, burst, nowMs = { now }, sleep = { now += it })

    @Test
    fun burstIsImmediateThenPaced() = runBlocking {
        val g = gate()
        repeat(8) { g.acquire() }
        assertEquals("the burst must not wait", 0L, now)
        repeat(8) { g.acquire() }
        // 8 more at 4/s cannot take less than 2 s of virtual time.
        assertTrue("paced requests took ${now}ms", now in 2000..2100)
    }

    @Test
    fun neverExceedsRateOverALongRun() = runBlocking {
        val g = gate(perSecond = 4.0, burst = 8)
        repeat(200) { g.acquire() }
        val allowed = 8 + now * 4.0 / 1000.0
        assertTrue("200 grants in ${now}ms exceeds 4/s + burst", 200 <= allowed + 1)
    }

    @Test
    fun throttleHonoursRetryAfterAndBacksOff() = runBlocking {
        val g = gate()
        assertEquals(7_000L, g.throttled(7))
        g.acquire()
        assertTrue("acquire returned before Retry-After elapsed", now >= 7_000)
        // No header: 2 s, then doubling, capped at 60 s.
        val waits = (1..7).map { g.throttled(null) }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), waits)
        g.succeeded()
        assertEquals(2_000L, g.throttled(null))
    }

    @Test
    fun aBurstRejectedByOneWindowIsOneThrottle() = runBlocking {
        val g = gate()
        // Eight requests leave together and Apple rejects all eight, without Retry-After.
        val sentIn = List(8) { g.acquire() }
        val waits = sentIn.map { g.throttled(null, it) }
        assertEquals("each 429 of the burst doubled the pause", List(8) { 2_000L }, waits)
        val retry = g.acquire()
        assertTrue("one burst paused everyone for ${now}ms", now in 2_000..2_100)
        // A request sent after that pause and rejected again is a repeat: now it doubles.
        assertEquals(4_000L, g.throttled(null, retry))
    }

    @Test
    fun aLateSuccessFromBeforeThePauseKeepsTheBackoff() = runBlocking {
        val g = gate()
        val slow = g.acquire()
        val rejected = g.acquire()
        assertEquals(2_000L, g.throttled(null, rejected))
        // The slow request was sent before the pause; its 200 says nothing about the limit now.
        g.succeeded(slow)
        val retry = g.acquire()
        assertEquals("a stale success reset the backoff", 4_000L, g.throttled(null, retry))
    }

    @Test
    fun aClockSteppingBackDoesNotStallTheGate() = runBlocking {
        val g = gate()
        repeat(4) { g.acquire() }
        now -= 3_600_000 // the clock is corrected an hour back
        val before = now
        g.acquire()
        assertEquals("a backwards clock step held the gate for ${now - before}ms", before, now)
    }

    @Test
    fun cacheExpiresAndStaysBounded() {
        val c = AmpResponseCache(maxEntries = 8, nowMs = { now })
        c.put("a", 200, "A", 60_000)
        assertEquals("A", c.get("a")?.body)
        now += 59_999; assertEquals("A", c.get("a")?.body)
        now += 1; assertNull("entry outlived its max-age", c.get("a"))
        c.put("no-store", 200, "x", 0)
        assertNull(c.get("no-store"))
        repeat(40) { c.put("k$it", 200, "v", 60_000) }
        assertTrue((0 until 40).count { c.get("k$it") != null } <= 8)
    }
}
