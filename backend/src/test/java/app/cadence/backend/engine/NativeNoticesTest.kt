package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNoticesTest {
    private var clock = 0L
    private fun limiter() = NoticeLimiter(perKindMs = 30_000, globalWindowMs = 60_000, globalMax = 3, now = { clock })

    @Test fun theSameKindIsShownOnceThenHeldBack() {
        val l = limiter()
        assertTrue(l.allow("KEY_REJECTED"))
        clock += 29_999
        assertFalse(l.allow("KEY_REJECTED"))
        clock += 1
        assertTrue(l.allow("KEY_REJECTED"))
    }

    @Test fun differentKindsAreIndependentUntilTheGlobalCap() {
        val l = limiter()
        assertTrue(l.allow("KEY_REJECTED"))
        assertTrue(l.allow("DECRYPT"))
        assertTrue(l.allow("SIGN_IN"))
        assertFalse("a 4th notice inside a minute", l.allow("TOKEN_MINT"))
        clock += 60_000
        assertTrue("the window moved on", l.allow("TOKEN_MINT"))
    }

    @Test fun aFailingPlaylistCannotBuryTheScreen() {
        val l = limiter()
        val shown = (1..50).count { clock += 1_000; l.allow("KEY_REJECTED") }
        assertEquals("one per 30 s over 50 s", 2, shown)
    }

    @Test fun noticesReachTheListenerAndAreRateLimited() {
        val seen = mutableListOf<NativeNotice>()
        val old = NativeNotices.listener
        NativeNotices.limiter = limiter()
        NativeNotices.listener = { seen += it }
        try {
            val n = NativeNotice("KEY_REJECTED", "track", "kd", "adam=1 key=c23")
            NativeNotices.post(n)
            NativeNotices.post(n)
            assertEquals(listOf(n), seen)
        } finally {
            NativeNotices.listener = old
            NativeNotices.limiter = NoticeLimiter()
        }
    }
}
