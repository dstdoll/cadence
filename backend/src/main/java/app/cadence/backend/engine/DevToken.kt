package app.cadence.backend.engine

import android.content.Context
import app.cadence.backend.engine.media.Http
import java.io.File
import java.util.Base64

/**
 * The MusicKit developer token that catalogue requests carry as `Authorization: Bearer`. It is not
 * stored in the repository or the app: like Apple's own web player, it is read from the player's
 * public script (the page names one `/assets/index~<hash>.js`, which embeds the token the player
 * itself uses), kept in `files/engine/dev_token` and renewed shortly before it expires.
 */
object DevToken {
    private const val PAGE = "https://music.apple.com/us/browse"
    private const val ORIGIN = "https://music.apple.com"
    private const val WEB_PLAYER_KID = "WebPlayKid"
    private const val RENEW_BEFORE_EXPIRY_S = 24 * 60 * 60L
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val SCRIPT = Regex("""src="(/assets/index[^"]+\.js)"""")
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}""")
    private val EXP = Regex(""""exp"\s*:\s*(\d+)""")
    private val KID = Regex(""""kid"\s*:\s*"([^"]+)"""")

    private fun file(context: Context) = File(context.filesDir, "engine/dev_token")

    /** The stored token while it still has more than a day left; no network. */
    fun cached(context: Context, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        runCatching { SecretFiles.read(file(context))?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && isFresh(it, nowSeconds) }

    /** The stored token, or a freshly fetched one (blocking network: call off the main thread). */
    fun ensure(context: Context): String? {
        cached(context)?.let { return it }
        val token = runCatching { fetch() }
            .onFailure { CadenceLog.e("devtoken", "could not read the developer token", it) }
            .getOrNull() ?: return null
        runCatching { file(context).apply { parentFile?.mkdirs() }.let { SecretFiles.write(it, token) } }
        CadenceLog.i("devtoken", "developer token renewed")
        return token
    }

    /**
     * [ensure] from a thread that may be the main one: the network runs on a worker and the caller
     * waits at most [timeoutMs], so a dead network delays the first start instead of hanging it.
     */
    fun ensureWithin(context: Context, timeoutMs: Long): String? {
        val token = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val worker = Thread({ token.set(ensure(context)) }, "cadence-devtoken").apply { isDaemon = true; start() }
        worker.join(timeoutMs)
        return token.get()
    }

    private fun fetch(): String? {
        val page = Http.get(PAGE, mapOf("User-Agent" to DESKTOP_UA, "Accept" to "text/html"))
        if (page.code != 200) return null
        val script = scriptPath(page.body) ?: return null
        val js = Http.get(ORIGIN + script, mapOf("User-Agent" to DESKTOP_UA, "Accept" to "*/*"))
        if (js.code != 200) return null
        return webPlayerToken(js.body)
    }

    /** The player's main script named by the page (`/assets/index~<hash>.js`). */
    internal fun scriptPath(html: String): String? = SCRIPT.find(html)?.groupValues?.get(1)

    /** The web player's own token (the script embeds several, for different Apple clients). */
    internal fun webPlayerToken(script: String): String? =
        JWT.findAll(script).map { it.value }.firstOrNull { header(it)?.let { h -> KID.find(h)?.groupValues?.get(1) } == WEB_PLAYER_KID }

    internal fun isFresh(token: String, nowSeconds: Long): Boolean {
        val exp = expiry(token) ?: return false
        return exp - nowSeconds > RENEW_BEFORE_EXPIRY_S
    }

    private fun expiry(token: String): Long? = part(token, 1)?.let { EXP.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun header(token: String): String? = part(token, 0)

    private fun part(token: String, index: Int): String? = runCatching {
        String(Base64.getUrlDecoder().decode(token.split('.')[index]), Charsets.UTF_8)
    }.getOrNull()
}
