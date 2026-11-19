package app.cadence

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap diagnostics export. Both logs live in the app's external files dir,
 * which Android 11+ blocks for other apps (Termux included), so the app itself
 * copies them to a user-visible Download/Cadence file that can be shared or
 * uploaded.
 */
object LogExport {
    data class Exported(val displayPath: String, val uri: Uri)

    private const val MAX_CHARS = 1_500_000

    fun export(context: Context): Exported? {
        return runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "Cadence-logs-$stamp.txt"
            val logDir = File(context.filesDir, "logs")
            val text = buildString {
                appendLine("Cadence diagnostics")
                appendLine("generated=${Date()}")
                appendLine("package=${context.packageName}")
                appendLine("version=${versionName(context)}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
                for (file in listOf("cadence.log", "native-crash.log")) {
                    appendLine()
                    appendLine("===== $file =====")
                    // The native crash handler writes to the app's external log directory.
                    val f = if (file == app.cadence.backend.engine.ExternalLogs.NATIVE_CRASH)
                        app.cadence.backend.engine.ExternalLogs.nativeCrashFile(context) ?: File(logDir, file)
                    else File(logDir, file)
                    if (f.isFile) {
                        val body = f.readText()
                        if (body.length > MAX_CHARS) appendLine("(last $MAX_CHARS chars)")
                        appendLine(if (body.length > MAX_CHARS) body.takeLast(MAX_CHARS) else body)
                    } else {
                        appendLine("(missing: the engine log appears after the first download)")
                    }
                }
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Cadence")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("no output stream")
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return@runCatching Exported("Download/Cadence/$name", uri)
            }
            // Fallback: FileProvider-backed cache/share path.
            val f = File(context.cacheDir, "share/$name").apply {
                parentFile?.mkdirs()
                writeText(text)
            }
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.share", f)
            Exported("cache/share/$name", fileUri)
        }.getOrNull()
    }

    fun share(context: Context, exported: Exported) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cadence logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share Cadence logs"))
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
