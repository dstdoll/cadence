package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ProcessNetworkTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun serversAreJoinedInOrderWithoutDuplicates() {
        assertEquals("192.168.1.1,2001:db8:0:0:0:0:0:53", ProcessNetwork.join(listOf(ip("192.168.1.1"), ip("2001:db8::53"), ip("192.168.1.1"))))
        assertEquals("", ProcessNetwork.join(emptyList()))
    }

    @Test fun aNewNetworkReplacesTheServersTheEngineStartedWith() {
        // Started on home Wi-Fi, now on mobile data: the router's address no longer answers.
        assertTrue(ProcessNetwork.shouldReplace("192.168.1.1", "10.177.0.34,10.177.0.35"))
        // Started offline (native public fallback), then a network came up.
        assertTrue(ProcessNetwork.shouldReplace("", "192.168.1.1"))
        assertTrue(ProcessNetwork.shouldReplace(null, "192.168.1.1"))
    }

    @Test fun theSameServersOrNoneAtAllLeaveTheEngineAlone() {
        assertFalse(ProcessNetwork.shouldReplace("192.168.1.1", "192.168.1.1"))
        // Between two networks Android briefly reports none: keep the last list rather than only the fallback.
        assertFalse(ProcessNetwork.shouldReplace("192.168.1.1", ""))
    }

    @Test fun theLogSummaryNeverContainsAnAddress() {
        val text = ProcessNetwork.describe("192.168.1.1,2001:db8::53")
        assertEquals("2 (ipv4=1 ipv6=1)", text)
        assertFalse(text.contains("192.168"))
        assertEquals("none (native fallback)", ProcessNetwork.describe(""))
    }
}
