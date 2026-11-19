package app.cadence.backend.real

import android.content.Context
import app.cadence.backend.api.Session
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.SecretFiles
import org.json.JSONObject
import java.io.File

/**
 * Persists the signed-in session in app-private storage so it survives restarts
 * until the user signs out. One file per account, so profiles can be added later
 * without touching the auth flow. The file holds tokens and the account's email, so it is
 * encrypted at rest ([SecretFiles]).
 */
class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val file = File(dir, "active.json")

    fun save(session: Session, storefront: String) {
        runCatching {
            val o = JSONObject()
                .put("userId", session.userId)
                .put("email", session.email)
                .put("displayName", session.displayName)
                .put("accessToken", session.accessToken)
                .put("refreshToken", session.refreshToken)
                .put("storefront", storefront)
                .put("savedAt", System.currentTimeMillis())
            SecretFiles.write(file, o.toString())
            CadenceLog.i("auth", "session saved for ${session.email.ifBlank { session.userId }}")
        }.onFailure { CadenceLog.e("auth", "save failed", it) }
    }

    /**
     * Adopts the engine's provisioned credentials as the signed-in session when nothing
     * is stored yet, so the app opens authenticated instead of asking to sign in.
     */
    fun seedIfEmpty(mediaUserToken: String, storefront: String) {
        if (load() != null) return
        if (mediaUserToken.isBlank()) return
        save(
            Session(
                userId = "engine",
                email = "",
                displayName = "Apple Music",
                accessToken = mediaUserToken,
                refreshToken = mediaUserToken,
            ),
            storefront.ifBlank { "us" },
        )
    }

    fun load(): Session? = runCatching { parse(SecretFiles.read(file) ?: return null) }.getOrNull()

    fun storefront(): String = runCatching {
        SecretFiles.read(file)?.let { JSONObject(it).optString("storefront", "us") } ?: "us"
    }.getOrDefault("us")

    fun clear() { runCatching { file.delete() } }

    companion object {
        /**
         * A stored session. It is valid with or without the account's media-user-token: a sign-in whose
         * token request failed is still a signed-in account (playback and the developer-token catalogue
         * work), so it has to survive a restart instead of sending the user back to the sign-in screen.
         */
        internal fun parse(text: String): Session? {
            val o = JSONObject(text)
            val userId = o.optString("userId")
            if (userId.isBlank()) return null
            return Session(
                userId = userId,
                email = o.optString("email"),
                displayName = o.optString("displayName"),
                accessToken = o.optString("accessToken"),
                refreshToken = o.optString("refreshToken"),
            )
        }
    }
}
