package app.cadence.backend.engine

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Android side of [SessionBundle]: exports and imports through the document picker or a file. */
object SessionTransfer {
    /** The config as it is used now: a signed-in account's token and storefront win over the provisioned ones. */
    private fun effectiveAmpJson(context: Context): String {
        val cfg = AmpConfig.load(context) ?: throw IOException("there is no session to export")
        val account = runCatching { JSONObject(SecretFiles.read(File(context.filesDir, "sessions/active.json"))!!) }.getOrNull()
        val token = account?.optString("accessToken").orEmpty().ifBlank { cfg.mediaUserToken }
        val storefront = account?.optString("storefront").orEmpty().ifBlank { cfg.storefront }
        return JSONObject().put("devToken", cfg.devToken).put("mediaUserToken", token)
            .put("storefront", storefront).put("language", cfg.language).toString(2)
    }

    fun export(context: Context, uri: Uri): Result<SessionBundle.Summary> = logged("export") {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write the file")
        out.use { SessionBundle.export(context.filesDir, effectiveAmpJson(context), it) }
    }

    fun exportTo(context: Context, file: File): Result<SessionBundle.Summary> = logged("export") {
        file.outputStream().use { SessionBundle.export(context.filesDir, effectiveAmpJson(context), it) }
    }

    /** Installs a session file; the app must restart afterwards so every component picks it up. */
    fun import(context: Context, uri: Uri): Result<SessionBundle.Summary> = logged("import") {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot read the file")
        input.use { SessionBundle.import(context.filesDir, it) }
    }

    fun importFrom(context: Context, file: File): Result<SessionBundle.Summary> = logged("import") {
        file.inputStream().use { SessionBundle.import(context.filesDir, it) }
    }

    private fun logged(what: String, block: () -> SessionBundle.Summary): Result<SessionBundle.Summary> =
        runCatching(block)
            .onSuccess { CadenceLog.i("session", "$what entries=${it.entries} bytes=${it.bytes}") }
            .onFailure { CadenceLog.e("session", "$what failed", it) }
}
