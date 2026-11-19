package app.cadence.backend.engine.media

object NativeWrapper {
    init { System.loadLibrary("cadence-native") }
    /** [dnsServers] is a comma-separated list of the active network's DNS server IPs; the native
     *  resolver uses them (reachable on any network) instead of a fixed public resolver. */
    external fun init(baseDir: String, deviceId: String, dnsServers: String): Boolean
    external fun m3u8(adamId: String): String?
    external fun kd(adamId: String, keyUri: String): Long
    external fun decryptSample(kd: Long, sample: ByteArray): ByteArray?

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
