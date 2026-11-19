package app.cadence.backend.real

import app.cadence.backend.api.AuthError
import app.cadence.backend.api.AuthService
import app.cadence.backend.api.OtpChallenge
import app.cadence.backend.api.Session
import app.cadence.backend.engine.AmpConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session backed by the provisioned engine credentials.
 *
 * The UI hides all content while `session` is null, so until the OTP bridge
 * (the `cadence-auth-android` library) is wired in, the app must present a
 * non-null engine session or nothing renders. `requestCode`/`verifyCode` report
 * Network until W2 lands, so the real sign-in flow simply cannot be entered yet.
 */
class EngineAuthService(private val config: AmpConfig) : AuthService {

    private fun engineSession() = Session(
        userId = "local",
        email = "",
        // Not a person: the account screen must not present this as a signed-in user.
        displayName = "Local library",
        accessToken = config.devToken,
        refreshToken = config.mediaUserToken,
    )

    private val state = MutableStateFlow<Session?>(engineSession())
    override val session: StateFlow<Session?> = state

    override suspend fun requestCode(email: String, password: String): Result<OtpChallenge> =
        Result.failure(AuthError.Network())

    override suspend fun resendCode(challengeId: String): Result<OtpChallenge> =
        Result.failure(AuthError.Network())

    override suspend fun verifyCode(challengeId: String, code: String): Result<Session> =
        Result.success(engineSession())

    /** Signing out falls back to the engine identity rather than an empty app. */
    override suspend fun signOut() {
        state.value = engineSession()
    }
}
