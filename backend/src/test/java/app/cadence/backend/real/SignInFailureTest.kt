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
            // Offline wins over the engine's DNS: there is nothing the engine could have resolved.
            assertTrue("$reach timedOut=$timedOut", SignInFailure.classify(timedOut, reach, engineResolves = false) is AuthError.Network)
        }
    }

    @Test fun aSlowAppleWhileOnlineIsATimeoutNotARejection() {
        assertTrue(SignInFailure.classify(timedOut = true, reach = Reach.Reachable) is AuthError.SignInTimedOut)
    }

    @Test fun appleReachableButTheEngineCannotResolveItIsADnsProblemNotAPasswordOne() {
        for (timedOut in listOf(true, false)) {
            val e = SignInFailure.classify(timedOut, Reach.Reachable, engineResolves = false)
            assertTrue("timedOut=$timedOut", e is AuthError.SignInDnsFailed)
            assertTrue(e.message!!, !e.message!!.contains("password"))
            assertTrue(e.message!!, e.message!!.contains("Private DNS"))
        }
    }

    @Test fun theLogLineSaysWhatWasObservedAndWhatWasShown() {
        val e = SignInFailure.classify(false, Reach.Reachable)
        assertEquals(
            "sign-in failed: Apple answered with a failure, network=Reachable, engine dns=ok -> SignInRejected",
            SignInFailure.describe(false, Reach.Reachable, e),
        )
        assertTrue(SignInFailure.describe(true, Reach.Offline, AuthError.Network()).contains("no answer in time, network=Offline, engine dns=ok -> Network"))
        val dns = SignInFailure.classify(true, Reach.Reachable, engineResolves = false)
        assertTrue(SignInFailure.describe(true, Reach.Reachable, dns, engineResolves = false).endsWith("engine dns=failed -> SignInDnsFailed"))
    }
}
