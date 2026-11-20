package app.cadence.backend.engine.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedKeyMemoryTest {
    private var clock = 1_000L
    private val memory = FailedKeyMemory(ttlMs = 60_000L, now = { clock })

    @Test fun anUnknownKeyIsNotFailing() {
        assertFalse(memory.isFailing("1|skd://a"))
    }

    @Test fun aFailedKeyIsRememberedUntilItExpires() {
        memory.failed("1|skd://a")
        assertTrue(memory.isFailing("1|skd://a"))
        clock += 59_999
        assertTrue(memory.isFailing("1|skd://a"))
        clock += 1
        assertFalse(memory.isFailing("1|skd://a"))
    }

    @Test fun aSuccessClearsTheEntryAtOnce() {
        memory.failed("1|skd://a")
        memory.succeeded("1|skd://a")
        assertFalse(memory.isFailing("1|skd://a"))
    }

    @Test fun keysAreIndependent() {
        memory.failed("1|skd://a")
        assertFalse(memory.isFailing("2|skd://b"))
    }

    @Test fun aSecondFailureRestartsTheWindow() {
        memory.failed("1|skd://a")
        clock += 50_000
        memory.failed("1|skd://a")
        clock += 50_000
        assertTrue(memory.isFailing("1|skd://a"))
    }
}
