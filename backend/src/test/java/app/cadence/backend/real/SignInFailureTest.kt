package app.cadence.backend.real

import app.cadence.backend.api.AuthError
import app.cadence.backend.real.SignInFailure.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInFailureTest {
    @Test fun aRefusalWhileOnlineIsNotAConnectionProblem() {
        val e = SignInFailure.classify(timedOut = false, reach = Reach.Reachable)
        assertTrue(e is AuthError.SignInRejected)
        assertTrue(e.message!!, e.message!!.contains("email and password"))
        assertTrue(!e.message!!.contains("connection") && !e.message!!.contains("reach"))
    }

    @Test fun offlineOrUnreachableAppleIsAConnectionProblemWhetherOrNotItTimedOut() {
        for (reach in listOf(Reach.Offline, Reach.AppleUnreachable)) for (timedOut in listOf(true, false)) {
            assertTrue("$reach timedOut=$timedOut", SignInFailure.classify(timedOut, reach) is AuthError.Network)
        }
    }

    @Test fun aSlowAppleWhileOnlineIsATimeoutNotARejection() {
        assertTrue(SignInFailure.classify(timedOut = true, reach = Reach.Reachable) is AuthError.SignInTimedOut)
    }

    @Test fun theLogLineSaysWhatWasObservedAndWhatWasShown() {
        val e = SignInFailure.classify(false, Reach.Reachable)
        assertEquals("sign-in failed: Apple answered with a failure, network=Reachable -> SignInRejected", SignInFailure.describe(false, Reach.Reachable, e))
        assertTrue(SignInFailure.describe(true, Reach.Offline, AuthError.Network()).contains("no answer in time, network=Offline -> Network"))
    }
}
