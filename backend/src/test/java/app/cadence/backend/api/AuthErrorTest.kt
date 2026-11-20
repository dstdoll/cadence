package app.cadence.backend.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorTest {
    @Test fun anUnavailableEngineIsNotReportedAsANetworkProblem() {
        val e: AuthError = AuthError.EngineUnavailable()
        assertFalse(e is AuthError.Network)
        assertFalse(e.message!!.contains("connection"))
        assertTrue(e.message!!.contains("this device"))
    }
}
