package app.cadence.backend.real

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.cadence.backend.api.AuthError
import java.net.HttpURLConnection
import java.net.URL

/**
 * Why a sign-in that did not succeed failed, from what can be told apart on the device. The native sign-in
 * only answers true/false, so "wrong password" and "no connection" used to look the same ("Can't reach the
 * server"). A connection error is now reported only when the device is offline or Apple cannot be reached.
 *
 * The sign-in resolves names with the engine's own resolver, not Android's (see ProcessNetwork), so Apple
 * can be reachable from the app while the sign-in cannot find it: [engineResolves] tells that case apart.
 */
internal object SignInFailure {
    enum class Reach { Offline, AppleUnreachable, Reachable }

    /** The host the engine's sign-in asks first (StoreServices' bag). */
    const val ENGINE_PROBE_HOST = "init.itunes.apple.com"

    /**
     * The error to show. [timedOut]: the sign-in never answered; otherwise it answered with a failure.
     * [engineResolves]: whether the engine's resolver could look Apple up after the failure. It only matters
     * for a sign-in that never answered: one that answered reached Apple, so its DNS worked and a refusal is a
     * refusal, whatever a later lookup says.
     */
    fun classify(timedOut: Boolean, reach: Reach, engineResolves: Boolean = true): AuthError = when {
        reach != Reach.Reachable -> AuthError.Network()
        !timedOut -> AuthError.SignInRejected()
        !engineResolves -> AuthError.SignInDnsFailed()
        else -> AuthError.SignInTimedOut()
    }

    /** A one-line, greppable description for cadence.log. */
    fun describe(timedOut: Boolean, reach: Reach, error: AuthError, engineResolves: Boolean = true): String =
        "sign-in failed: ${if (timedOut) "no answer in time" else "Apple answered with a failure"}, network=$reach, " +
            "engine dns=${if (engineResolves) "ok" else "failed"} -> ${error.javaClass.simpleName}"

    /**
     * Offline when Android has no network with internet; otherwise any HTTP answer from Apple (even a 401)
     * means Apple is reachable. A short, cheap probe: it only runs after a sign-in already failed.
     */
    fun reach(context: Context, probe: (String) -> Boolean = ::httpAnswers): Reach {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Reach.Offline
        return if (probe(APPLE_PROBE)) Reach.Reachable else Reach.AppleUnreachable
    }

    private const val APPLE_PROBE = "https://amp-api.music.apple.com/v1/test"

    private fun httpAnswers(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 5_000; c.requestMethod = "HEAD"
        try { c.responseCode; true } finally { c.disconnect() }
    } catch (_: Throwable) { false }
}
