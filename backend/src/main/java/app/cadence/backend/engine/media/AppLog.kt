package app.cadence.backend.engine.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "Cadence"
    const val FILE_NAME = "cadence.log"
    const val DISPLAY_PATH = "files/logs/cadence.log"

    private var appContext: Context? = null
    private var file: File? = null
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var installed = false

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            // Private storage: other apps and a USB file browser must not be able to read it.
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            file = File(dir, FILE_NAME)
            if (!installed) {
                installed = true
                installCrashHandler()
                log("App", "=== Cadence start ===")
                log("App", "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}")
                log("App", "log=${file?.absolutePath}")
            }
        }
    }

    fun log(tag: String, rawMessage: String) {
        val message = app.cadence.backend.engine.LogRedactor.clean(rawMessage)
        runCatching { Log.i(TAG, "$tag: $message") }
        write("I", tag, message, null)
    }

    fun error(tag: String, rawMessage: String, throwable: Throwable? = null) {
        val message = app.cadence.backend.engine.LogRedactor.clean(rawMessage)
        runCatching { Log.e(TAG, "$tag: $message", throwable) }
        write("E", tag, message, throwable)
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file?.writeText("") }
            write("I", "Log", "log cleared", null)
        }
    }

    fun shareIntent(): Intent? {
        val context = appContext ?: return null
        val f = file ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.share", f)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(FILE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Copies the current log into Download/cadence-<timestamp>.log. Intended for a background thread. */
    fun publishToDownloads(): String {
        val context = appContext ?: return "no context"
        val text = runCatching { file?.readText().orEmpty() }.getOrDefault("")
        if (text.isBlank()) return "log empty"
        return try {
            val name = "cadence-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(collection, values)
                ?: return "insert failed"
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            "Download/$name"
        } catch (t: Throwable) {
            Log.e(TAG, "publish log failed", t)
            "publish failed: ${t.message}"
        }
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            val f = file ?: return
            f.parentFile?.mkdirs()
            val line = "${stamp.format(Date())} $level/${Thread.currentThread().name} $tag: $message\n"
            val body = if (throwable == null) line else buildString {
                append(line)
                append(Log.getStackTraceString(throwable))
                append('\n')
            }
            runCatching { f.appendText(body) }
                .onFailure { Log.e(TAG, "cannot append log", it) }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("Crash", "uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
