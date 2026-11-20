package app.cadence.backend.real

import android.content.Context
import app.cadence.backend.api.AuthError
import app.cadence.backend.api.AuthService
import app.cadence.backend.api.OtpChallenge
import app.cadence.backend.api.Session
import app.cadence.backend.engine.SecretFiles
import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.media.EngineDownloader
import app.cadence.backend.engine.media.NativeWrapper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** How long a media-user-token request may take before the caller stops waiting for it. */
private const val MINT_TIMEOUT_MS = 30_000L

/**
 * Real sign-in: Apple ID password (SRP) then the six-digit HSA2 code, bridged
 * through `libcadence_auth_android.so`. The resulting media user token is what the
 * catalogue, lyrics and personal rails actually need.
 *
 * When the native library is unavailable the service reports `AuthError.Network`
 * rather than pretending to sign in, so the UI can say what is wrong.
 */
class RealAuthService(
    private val context: Context,
    private val store: SessionStore,
) : AuthService {

    private val state = MutableStateFlow(store.load())
    override val session: StateFlow<Session?> = state

    private var pendingEmail: String = ""

    // The StoreServices sign-in runs on this deferred: login() blocks in the native credential
    // handler until the 2FA code is submitted, so it is started in requestCode and awaited in
    // verifyCode (after submitCode unblocks it).
    @Volatile private var loginJob: Deferred<Boolean>? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    init {
        // The stored/seeded session may carry a placeholder name ("Apple Music"). Refresh the real
        // profile from Apple on launch using the saved media-user-token - no OTP, no sign-in.
        val s = state.value
        if (s != null && s.accessToken.isNotBlank()) scope.launch {
            // A returning user's rejected keys need the subscription state too, not only a fresh sign-in's.
            AmpConfig.load(context)?.let { runCatching { queryProfile(it.devToken, s.accessToken, "account?meta=subscription") } }
            val p = runCatching { fetchProfile(s.accessToken) }.getOrNull() ?: return@launch
            val name = p.name?.takeIf { it.isNotBlank() } ?: return@launch
            if (name != s.displayName || (p.email != null && p.email != s.email)) {
                val updated = s.copy(displayName = name, email = p.email?.ifBlank { s.email } ?: s.email)
                store.save(updated, store.storefront())
                state.value = updated
                CadenceLog.i("auth", "profile refreshed on launch")
            }
        }
    }

    override suspend fun requestCode(email: String, password: String): Result<OtpChallenge> =
        withContext(Dispatchers.IO) {
            pendingEmail = email
            if (!app.cadence.backend.engine.media.NativeLibrary.available("cadence-native")) {
                CadenceLog.e("auth", "sign-in refused: the native library did not load (see the [NATIVE-LOAD] line)")
                return@withContext Result.failure(AuthError.EngineUnavailable())
            }
            // Sign in through the StoreServices libraries (the genuine Apple binaries) rather than the
            // headless HTTP MusicKit exchange Apple refuses. Bring the native engine up first.
            if (!EngineDownloader.initNative(context)) {
                // Not a connection problem: the engine itself did not start (the [native] lines say why).
                CadenceLog.e("auth", "native engine init failed")
                return@withContext Result.failure(AuthError.EngineUnavailable())
            }
            NativeWrapper.setCredentials(email, password)
            // login() drives the account sign-in and blocks in the credential handler until the 2FA
            // code is submitted (or returns when no second factor is needed). Run it detached.
            val job = scope.async(Dispatchers.IO) { runCatching { NativeWrapper.login() }.getOrDefault(false) }
            loginJob = job
            // Wait for StoreServices to reach the 2FA prompt (code texted to a trusted device) or
            // finish sign-in outright.
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline && !NativeWrapper.awaitingCode() && !job.isCompleted) {
                delay(200)
            }
            CadenceLog.i("auth", "storeservices login: awaitingCode=${NativeWrapper.awaitingCode()} done=${job.isCompleted}")
            when {
                NativeWrapper.awaitingCode() -> Result.success(
                    OtpChallenge(
                        challengeId = "native-ss",
                        maskedDestination = "your trusted device",
                        codeLength = 6,
                        expiresAtMs = System.currentTimeMillis() + 10 * 60_000,
                        resendAvailableAtMs = System.currentTimeMillis() + 30_000,
                    )
                )
                job.isCompleted && job.await() ->
                    finishNative()?.let {
                        Result.success(OtpChallenge("native-done", mask(email), 6, System.currentTimeMillis(), System.currentTimeMillis()))
                    } ?: Result.failure(AuthError.SignInIncomplete("Signed in, but the session could not be read."))
                else -> {
                    // login() answered false, or never answered in 45 s. Do not call that a connection problem unless it is one.
                    val timedOut = !job.isCompleted
                    val reach = SignInFailure.reach(context)
                    val error = SignInFailure.classify(timedOut, reach)
                    CadenceLog.e("auth", SignInFailure.describe(timedOut, reach, error) + " (the [native] auth: lines above say what StoreServices reported)")
                    Result.failure(error)
                }
            }
        }

    override suspend fun resendCode(challengeId: String): Result<OtpChallenge> =
        Result.failure(AuthError.RateLimited(System.currentTimeMillis() + 30_000))

    override suspend fun verifyCode(challengeId: String, code: String): Result<Session> =
        withContext(Dispatchers.IO) {
            // Sign-in already completed without a code step (challenge was "native-done").
            if (challengeId == "native-done") {
                return@withContext state.value?.let { Result.success(it) }
                    ?: Result.failure(AuthError.SignInIncomplete("Session missing after sign-in."))
            }
            // Hand the code to the native credential handler, then let the StoreServices exchange
            // finish. login() returns once the lease/sign-in completes.
            NativeWrapper.submitCode(code)
            val ok = loginJob?.await() ?: false
            loginJob = null
            // Give the libraries a moment to persist the account/tokens after the exchange.
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && NativeWrapper.awaitingCode()) delay(200)
            CadenceLog.i("auth", "storeservices verify: login=$ok")
            finishNative()?.let { Result.success(it) }
                ?: Result.failure(AuthError.SignInIncomplete("Code verified, but the account token isn't available yet (StoreServices token mint pending)."))
        }

    /** The app-private files the StoreServices libraries read/write their tokens from; MUSIC_TOKEN is sealed once read. */
    private fun readNativeFile(name: String): String = runCatching {
        val file = File(context.filesDir, name)
        (if (name == "MUSIC_TOKEN") SecretFiles.read(file).orEmpty() else file.readText()).trim()
    }.getOrDefault("")

    /**
     * Build the app session after a StoreServices sign-in. The media-user-token is read from the
     * `MUSIC_TOKEN` the libraries write once the account mints it; until that token-mint is bridged
     * it may be blank, in which case catalogue runs on the developer token and playback on the
     * native m3u8/key path (neither needs the media-user-token).
     */
    private fun finishNative(): Session? {
        val amp = AmpConfig.load(context)
        // core_fh_login mints the media-user-token (createMusicToken) on the StoreServices thread and
        // writes MUSIC_TOKEN, so the catalogue is the user's personalised page, not the dev-token feed.
        val token = readNativeFile("MUSIC_TOKEN")
        CadenceLog.i("auth", "media-user-token after sign-in: ${if (token.isBlank()) "none" else "present (${token.length})"}")
        app.cadence.backend.engine.Provisioning.loginAtMs = System.currentTimeMillis()
        app.cadence.backend.engine.Provisioning.log("after sign-in")
        token.takeIf { it.isNotBlank() && amp != null }?.let { runCatching { queryProfile(amp!!.devToken, it, "account?meta=subscription") } }
        val sf = readNativeFile("STOREFRONT_ID").ifBlank { amp?.storefront ?: "us" }
        val profile = token.takeIf { it.isNotBlank() }?.let { runCatching { fetchProfile(it) }.getOrNull() }
        val s = Session(
            userId = "apple-$sf",
            email = profile?.email?.ifBlank { pendingEmail } ?: pendingEmail,
            displayName = profile?.name ?: pendingEmail.substringBefore('@').ifBlank { "Apple Music" },
            accessToken = token,
            refreshToken = token,
        )
        store.save(s, sf)
        state.value = s
        CadenceLog.i("auth", "session built via StoreServices (token=${if (token.isBlank()) "none-yet" else "present"})")
        return s
    }

    /**
     * Sign-in approval once the OTP is verified: Apple confirmed who the user is, and the app
     * already holds a usable media-user-token (provisioned, or the one it's been running on). Build
     * the session from that token plus the real profile, instead of failing on Apple's headless block.
     */
    private fun approveWithProvisionedToken(storefront: String?): Result<Session> {
        val amp = app.cadence.backend.engine.AmpConfig.load(context)
        val token = amp?.mediaUserToken?.takeIf { it.isNotBlank() }
            ?: store.load()?.accessToken?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                AuthError.SignInIncomplete("Code verified, but no usable token is provisioned on this device.")
            )
        val sf = storefront?.takeIf { it.isNotBlank() } ?: amp?.storefront ?: store.storefront()
        val profile = runCatching { fetchProfile(token) }.getOrNull()
        val s = Session(
            userId = "apple-$sf",
            email = profile?.email?.ifBlank { pendingEmail } ?: pendingEmail,
            displayName = profile?.name ?: pendingEmail.substringBefore('@').ifBlank { "Apple Music" },
            accessToken = token,
            refreshToken = token,
        )
        store.save(s, sf)
        state.value = s
        CadenceLog.i("auth", "auth approved via provisioned token (OTP verified)")
        return Result.success(s)
    }

    private fun complete(r: NativeAuth.Result): Session {
        val token = r.mediaUserToken.orEmpty()
        val profile = runCatching { fetchProfile(token) }.getOrNull()
        val s = Session(
            userId = r.storefront?.let { "apple-$it" } ?: "apple",
            email = profile?.email?.ifBlank { pendingEmail } ?: pendingEmail,
            // Real Apple account name; fall back to the email handle only if the call fails.
            displayName = profile?.name ?: pendingEmail.substringBefore('@').ifBlank { "Apple Music" },
            accessToken = token,
            refreshToken = token,
        )
        store.save(s, r.storefront ?: "us")
        state.value = s
        return s
    }

    private data class Profile(val name: String?, val email: String?)

    /**
     * The real profile for Settings -> Account, read from Apple's own account endpoint with the
     * media user token (no new OTP). Logs the attribute shape so the field names can be confirmed
     * against the live response rather than guessed.
     */
    private fun fetchProfile(token: String): Profile? {
        if (token.isBlank()) return null
        val amp = app.cadence.backend.engine.AmpConfig.load(context) ?: return null
        // The person's name isn't in /v1/me/account (restrictions only). The Apple Music social
        // profile carries the display name/handle; account with social/name meta is a fallback.
        for (path in listOf("social-profile", "account?meta=social,name", "account?meta=subscription")) {
            val profile = runCatching { queryProfile(amp.devToken, token, path) }.getOrNull()
            if (profile?.name != null) return profile
        }
        return null
    }

    private fun queryProfile(devToken: String, userToken: String, path: String): Profile? {
        val conn = (java.net.URL("https://amp-api.music.apple.com/v1/me/$path").openConnection()
            as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $devToken")
            setRequestProperty("media-user-token", userToken)
            setRequestProperty("Music-User-Token", userToken)
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) { CadenceLog.i("auth", "profile $path -> $code"); return null }
            if (path.contains("subscription")) {
                val d = app.cadence.backend.engine.Provisioning.describeSubscription(body)
                app.cadence.backend.engine.Provisioning.subscription = d
                CadenceLog.i("auth", "subscription: $d")
            }
            val root = org.json.JSONObject(body)
            val data0 = root.optJSONArray("data")?.optJSONObject(0) ?: root.optJSONObject("data")
            val attrs = data0?.optJSONObject("attributes")
            // Log the shape so the real field names are confirmed, not guessed.
            CadenceLog.i("auth", "profile $path -> $code dataKeys=${data0?.keys()?.asSequence()?.toList()} attrs=${attrs?.keys()?.asSequence()?.toList()} metaKeys=${root.optJSONObject("meta")?.keys()?.asSequence()?.toList()}")
            val name = attrs?.optString("name")?.takeIf { it.isNotBlank() }
                ?: attrs?.optString("handle")?.takeIf { it.isNotBlank() }
                ?: root.optJSONObject("meta")?.optJSONObject("subscription")?.optString("name")?.takeIf { it.isNotBlank() }
            val email = attrs?.optString("appleId")?.takeIf { it.contains("@") }
                ?: attrs?.optString("email")?.takeIf { it.contains("@") }
            if (name != null) CadenceLog.i("auth", "profile name found via $path")
            Profile(name, email)
        } catch (t: Throwable) {
            CadenceLog.e("auth", "profile $path failed", t); null
        } finally {
            conn.disconnect()
        }
    }

    private fun mask(email: String): String =
        email.replace(Regex("^(.).*(@.*)$"), "$1•••$2")

    override suspend fun resumeSession(): Result<Session> = withContext(Dispatchers.IO) {
        val r = NativeAuth.resume(context.filesDir.absolutePath)
        CadenceLog.i("auth", "resumeSession status=${r.status} kind=${r.kind}")
        if (r.status == "ok") Result.success(complete(r)) else Result.failure(map(r))
    }

    private fun map(r: NativeAuth.Result): AuthError = when (r.kind) {
        "code" -> AuthError.WrongCode(4)
        "auth" -> AuthError.InvalidCredentials()
        "music_token", "web_player_token" -> AuthError.SignInIncomplete(
            "Apple accepted the code but blocks the headless MusicKit exchange. Use \"Import a PC session\" instead."
        )
        "state" -> AuthError.SignInIncomplete(
            "The sign-in attempt expired. Start again to request a new code."
        )
        else -> AuthError.Network()
    }

    override suspend fun importSession(mediaUserToken: String, storefront: String?): Result<Session> =
        withContext(Dispatchers.IO) {
            val token = mediaUserToken.trim()
            if (token.length < 32) return@withContext Result.failure(
                AuthError.SignInIncomplete("That doesn't look like a media-user-token.")
            )
            val amp = app.cadence.backend.engine.AmpConfig.load(context)
                ?: return@withContext Result.failure(AuthError.SignInIncomplete("No MusicKit developer token provisioned."))
            val url = java.net.URL("https://amp-api.music.apple.com/v1/me/storefront")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer ${amp.devToken}")
                setRequestProperty("media-user-token", token)
                setRequestProperty("Music-User-Token", token)
                setRequestProperty("Origin", "https://music.apple.com")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Music/5.7 Android/10")
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                CadenceLog.i("auth", "import token -> $code")
                if (code !in 200..299) {
                    return@withContext Result.failure(
                        AuthError.SignInIncomplete("Apple rejected that token (HTTP $code). Run the PC login again.")
                    )
                }
                val sf = runCatching {
                    org.json.JSONObject(body).optJSONArray("data")
                        ?.optJSONObject(0)?.optString("id")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: storefront ?: "us"
                val profile = runCatching { fetchProfile(token) }.getOrNull()
                // Adding a token to a signed-in account keeps who it is; a fresh import takes it from the profile.
                val previous = state.value
                val s = Session(
                    userId = "apple-$sf",
                    email = profile?.email?.takeIf { it.isNotBlank() } ?: previous?.email.orEmpty(),
                    displayName = profile?.name ?: previous?.displayName ?: "Apple Music",
                    accessToken = token,
                    refreshToken = token,
                )
                store.save(s, sf)
                state.value = s
                CadenceLog.i("auth", "session imported storefront=$sf")
                Result.success(s)
            } catch (t: Throwable) {
                CadenceLog.e("auth", "import failed", t)
                Result.failure(AuthError.Network())
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun retryMediaToken(): Result<Session> = withContext(Dispatchers.IO) {
        val current = state.value ?: return@withContext Result.failure(AuthError.SignInIncomplete("Not signed in."))
        if (current.accessToken.isNotBlank()) return@withContext Result.success(current)
        if (!EngineDownloader.initNative(context)) return@withContext Result.failure(AuthError.Network())
        // The native request blocks until Apple answers. It runs in the service scope (not as a child of
        // this call) so the timeout can return while a stuck request is still waiting.
        val minting = scope.async(Dispatchers.IO) { runCatching { NativeWrapper.mintMusicToken() }.getOrNull() }
        val token = withTimeoutOrNull(MINT_TIMEOUT_MS) { minting.await() }?.takeIf { it.isNotBlank() }
        if (token == null) {
            CadenceLog.i("auth", "media-user-token retry: none (see the native mint log for the reason)")
            return@withContext Result.failure(AuthError.SignInIncomplete("Apple did not issue the account token. Try again later."))
        }
        runCatching { SecretFiles.write(File(context.filesDir, "MUSIC_TOKEN"), token) }
        val sf = readNativeFile("STOREFRONT_ID").ifBlank { store.storefront() }
        val profile = runCatching { fetchProfile(token) }.getOrNull()
        val s = current.copy(
            email = profile?.email?.takeIf { it.isNotBlank() } ?: current.email,
            displayName = profile?.name ?: current.displayName,
            accessToken = token,
            refreshToken = token,
        )
        store.save(s, sf)
        state.value = s
        CadenceLog.i("auth", "media-user-token retry: present (${token.length})")
        Result.success(s)
    }

    override suspend fun signOut() {
        NativeAuth.reset(context.filesDir.absolutePath)
        store.clear()
        state.value = null
    }
}
