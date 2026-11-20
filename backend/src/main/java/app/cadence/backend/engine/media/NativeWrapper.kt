package app.cadence.backend.engine.media

import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.NativeNotice
import app.cadence.backend.engine.NativeNotices

object NativeWrapper {
    init { System.loadLibrary("cadence-native") }

    /**
     * Called by the native layer (never from Kotlin) for every native error line: `[CXX-THROW]`,
     * `[CXX-SKIP]`, `[CXX-TERMINATE]`, `[RUST-PANIC]` and the `[NATIVE-INFO]` start-up line (see
     * native/cadence-native-rs/src/events.rs and native/APPLE_ERRORS.md). It lands in `cadence.log`
     * through [CadenceLog], so it is redacted like every other line and appears in order with the
     * Kotlin lines around it. Keep the signature: the native side looks it up by name (the R8 rule
     * in backend/consumer-rules.pro keeps it).
     */
    @JvmStatic
    fun onNativeEvent(level: Int, tag: String, message: String) {
        if (level >= 1) CadenceLog.e(tag, message) else CadenceLog.i(tag, message)
    }

    /** Called by the native layer when an error reached the user (a track skipped, a sign-in failed). */
    @JvmStatic
    fun onNativeNotice(kind: String, impact: String, entry: String, detail: String) {
        NativeNotices.post(NativeNotice(kind, impact, entry, detail))
    }

    /** [dnsServers] is a comma-separated list of the active network's DNS server IPs; the native
     *  resolver uses them (reachable on any network) instead of a fixed public resolver. */
    external fun init(baseDir: String, deviceId: String, dnsServers: String): Boolean
    external fun m3u8(adamId: String): String?
    external fun kd(adamId: String, keyUri: String): Long
    external fun decryptSample(kd: Long, sample: ByteArray): ByteArray?

    data class M4pAsset(val url: String, val decryptorCtx: Long)

    private external fun m4pAssetNative(adamId: String): String?
    external fun releaseM4pContext(ctx: Long)

    fun m4pAsset(adamId: String): M4pAsset? = parseM4pAsset(m4pAssetNative(adamId))

    // ---- Account sign-in through StoreServices (the same Apple libs that fetch keys). Apple mints
    // the session for these libraries where the headless HTTP MusicKit exchange is refused. ----
    /** Set the Apple ID + password before [login] (fresh device, no account in mpl_db yet). */
    external fun setCredentials(user: String, pass: String)
    /**
     * Sign in via the StoreServices lease. **Blocks** until sign-in completes, including waiting for
     * a 2FA code supplied through [submitCode] — call it off the main thread. Needs [init] first.
     */
    external fun login(): Boolean
    /** Poll: true while [login] is blocked waiting for the HSA2 code, so the UI can prompt for it. */
    external fun awaitingCode(): Boolean
    /** Supply the HSA2 verification code to unblock a [login] in progress. */
    external fun submitCode(code: String)
    /**
     * Mint the account's media-user-token via Apple's createMusicToken (call right after a successful
     * [login]). Returns null if the engine isn't ready or Apple returned no token.
     */
    external fun mintMusicToken(): String?
}

internal fun parseM4pAsset(raw: String?): NativeWrapper.M4pAsset? {
    if (raw == null) return null
    val sep = raw.indexOf('\n')
    if (sep <= 0) return null
    val ctx = raw.substring(0, sep).toLongOrNull() ?: return null
    val url = raw.substring(sep + 1)
    return if (url.isNotEmpty() && ctx != 0L) NativeWrapper.M4pAsset(url, ctx) else null
}
