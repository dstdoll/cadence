package app.cadence.backend.engine

import java.io.File

/**
 * What the Apple libraries have provisioned on this device, as one log line: names and sizes only,
 * never contents. A rejected key ("Invalid CKC") is read against this: a missing or tiny `fsi.pdat`
 * (FairPlay subscription state), a missing `adi.pb` (device identity) or an empty account store
 * point at provisioning; a complete set points at the account itself (no subscription, storefront).
 */
object Provisioning {
    @Volatile var dir: File? = null
    private val files = listOf("adi.pb", "fsi.pdat", "IC-Info.sids", "STOREFRONT_ID", "MUSIC_TOKEN", "ANDROID_ID")
    private var lastKeyContext = 0L

    /** What the account side looked like when keys were last asked for, for the "key rejected" line. */
    @Volatile var subscription: String = "unknown"
    @Volatile var loginAtMs: Long = 0L
    @Volatile var storefront: String = "unknown"

    fun snapshot(root: File, now: Long = System.currentTimeMillis()): String {
        // The age says whether a file was written before or after sign-in (a state made while signed out is stale).
        val parts = files.map { n ->
            val f = File(root, n)
            "$n=" + if (f.isFile) "${f.length()}B/${((now - f.lastModified()) / 1000).coerceAtLeast(0)}s-old" else "missing"
        }
        val db = File(root, "mpl_db")
        val dbFiles = db.listFiles()?.count { it.isFile } ?: 0
        val retired = root.listFiles()?.count { it.name.endsWith(".retired") } ?: 0
        return parts.joinToString(" ") + " mpl_db=" + (if (db.isDirectory) "${dbFiles}files" else "missing") + " retired=$retired"
    }

    /**
     * The account's subscription as Apple reports it (`meta.subscription` of `me/account`): booleans and
     * short strings only, so a missing or inactive Apple Music subscription is visible in the log.
     */
    fun describeSubscription(body: String): String = runCatching {
        val sub = org.json.JSONObject(body).optJSONObject("meta")?.optJSONObject("subscription")
            ?: return@runCatching "no meta.subscription in the response"
        sub.keys().asSequence().sorted().mapNotNull { k ->
            when (val v = sub.opt(k)) {
                is Boolean -> "$k=$v"
                is String -> if (v.length <= 24) "$k=$v" else null
                is Number -> "$k=$v"
                else -> null
            }
        }.joinToString(" ").ifBlank { "meta.subscription is empty" }
    }.getOrElse { "unreadable account response" }

    /** Logs the snapshot under [why]; null [dir] (native not initialised yet) is logged as such. */
    fun log(why: String) {
        val root = dir
        CadenceLog.i("provisioning", "$why: " + (root?.let { snapshot(it) } ?: "native base dir not set"))
    }

    @Synchronized internal fun resetThrottle() { lastKeyContext = 0L }

    /** One context line per minute when keys are being rejected, so a burst of failures does not repeat it. */
    @Synchronized
    fun logOnKeyRejected(now: Long = System.currentTimeMillis()): Boolean {
        if (now - lastKeyContext < 60_000L) return false
        lastKeyContext = now
        val sinceLogin = if (loginAtMs > 0) "${(now - loginAtMs) / 1000}s after sign-in" else "no sign-in this run"
        log("key rejected, state ($sinceLogin, storefront=$storefront, subscription: $subscription)")
        return true
    }
}
