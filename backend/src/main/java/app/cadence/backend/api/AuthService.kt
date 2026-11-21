package app.cadence.backend.api

import kotlinx.coroutines.flow.StateFlow

/** A signed-in user. BACKEND: returned by the verify endpoint. */
data class Session(
    // BACKEND: here goes the stable user id.
    val userId: String,
    // BACKEND: here goes the email the user signed in with.
    val email: String,
    // BACKEND: here goes the name shown in Settings -> Account.
    val displayName: String,
    // BACKEND: here goes the access token (short-lived) used on every API call.
    val accessToken: String,
    // BACKEND: here goes the refresh token. Store it in the Android Keystore / EncryptedSharedPreferences, never plain prefs.
    val refreshToken: String,
)

/** A one-time code that was just sent. */
data class OtpChallenge(
    // BACKEND: here goes the opaque id the server uses to match the code to this login attempt.
    val challengeId: String,
    // BACKEND: here goes where the code was sent, already masked server-side (e.g. "j•••@example.com").
    val maskedDestination: String,
    val codeLength: Int = 6,
    // BACKEND: here goes when the code stops working (epoch ms). 5-10 minutes is typical.
    val expiresAtMs: Long,
    // BACKEND: here goes when "Resend code" becomes available again (epoch ms). Rate-limits abuse.
    val resendAvailableAtMs: Long,
)

sealed class AuthError(message: String) : Exception(message) {
    class InvalidEmail : AuthError("That doesn't look like an email address.")
    class InvalidCredentials : AuthError("Email or password is incorrect.")
    class WeakPassword : AuthError("Password must be at least 8 characters.")
    class WrongCode(val attemptsLeft: Int) : AuthError("That code isn't right.")
    class Expired : AuthError("That code has expired. Request a new one.")
    class TooManyAttempts : AuthError("Too many attempts. Request a new code.")
    class RateLimited(val retryAtMs: Long) : AuthError("Please wait before requesting another code.")
    class Network : AuthError("Can't reach the server. Check your connection.")
    /** The sign-in reached Apple (the network is fine) but Apple did not accept it: usually a wrong email or password. */
    class SignInRejected : AuthError("Apple didn't accept that sign-in. Check your email and password and try again.")
    /** Apple was reachable but did not answer the sign-in in time. */
    class SignInTimedOut : AuthError("Apple took too long to answer. Try again in a moment.")
    /** Apple was reachable from the app, but the sign-in engine's own DNS lookups did not get an answer on this network. */
    class SignInDnsFailed : AuthError(
        "Apple is reachable, but sign-in couldn't look up Apple's servers on this network. " +
            "If you use a VPN, Private DNS or a DNS ad blocker, pause it or switch between Wi-Fi and mobile data, then try again."
    )
    /** The native playback/sign-in engine could not be loaded on this device (see the [NATIVE-LOAD] log line for why). */
    class EngineUnavailable : AuthError("Sign-in isn't available on this device: the playback engine couldn't start. The log has the details.")
    /** The Apple ID code was accepted but the MusicKit token exchange failed. */
    class SignInIncomplete(message: String) : AuthError(message)
}

/**
 * Two-step sign-in: email + password -> 6-digit one-time code -> session.
 * The code is only sent after the password checks out (second factor, not a replacement).
 *
 * BACKEND (suggested):
 *   POST /auth/login        { email, password }      -> OtpChallenge (verifies the password, then sends the code)
 *   POST /auth/otp/resend   { challengeId }          -> OtpChallenge
 *   POST /auth/otp/verify   { challengeId, code }    -> Session
 *   POST /auth/signout      { refreshToken }         -> 204
 */
interface AuthService {
    /** Null until signed in. The UI shows the sign-in flow whenever this is null. */
    val session: StateFlow<Session?>

    /** Checks [password] for [email]; on success sends a one-time code. */
    suspend fun requestCode(email: String, password: String): Result<OtpChallenge>
    suspend fun resendCode(challengeId: String): Result<OtpChallenge>
    suspend fun verifyCode(challengeId: String, code: String): Result<Session>

    /**
     * Retries the final token exchange after [verifyCode] reported
     * [AuthError.SignInIncomplete]; the 2FA code is already consumed.
     */
    suspend fun resumeSession(): Result<Session> = Result.failure(AuthError.Network())

    /**
     * Imports a media-user-token obtained from the PC browser login (Apple's
     * headless exchange is refused). Implementations validate it and persist it.
     */
    suspend fun importSession(mediaUserToken: String, storefront: String? = null): Result<Session> =
        Result.failure(AuthError.Network())

    /**
     * Asks Apple again for the account's media-user-token, for a session that signed in without one
     * (the request after a good sign-in can fail). Succeeds with the updated session; until then the
     * catalogue runs on the developer token and is not personalised.
     */
    suspend fun retryMediaToken(): Result<Session> = Result.failure(AuthError.Network())

    suspend fun signOut()
}
