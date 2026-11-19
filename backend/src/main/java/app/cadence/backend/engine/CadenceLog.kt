
package app.cadence.backend.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal logger: logcat plus a file in the app's private storage. Every line is redacted first
 * ([LogRedactor]). On a debug build a run can still be read headlessly with
 * `adb shell run-as app.cadence cat files/logs/cadence.log`; other apps cannot read it.
 */
object CadenceLog {
    private const val TAG = "Cadence"
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        ExternalLogs.prepare(context)
        runCatching {
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            file = File(dir, "cadence.log")
            file?.let { rescrub(it, File(dir, ".redaction")) }
            file?.appendText("\n=== start ${Date()} ===\n")
        }
    }

    fun i(tag: String, rawMessage: String) {
        val message = LogRedactor.clean(rawMessage)
        runCatching { Log.i(TAG, "$tag: $message") }
        write("I/$tag", message)
    }

    fun e(tag: String, rawMessage: String, t: Throwable? = null) {
        val message = LogRedactor.clean(rawMessage)
        runCatching { Log.e(TAG, "$tag: $message", t) }
        write("E/$tag", if (t == null) message else "$message :: ${t.javaClass.simpleName}: ${LogRedactor.clean(t.message.orEmpty())}")
    }

    /** Bumped whenever [LogRedactor] gains a rule, so older log lines get the new rule too. */
    const val REDACTION_VERSION = "2"

    /**
     * Lines written before a redaction rule existed still hold what it now removes (the device's
     * address, until version 2). They are passed through [LogRedactor] once per [REDACTION_VERSION],
     * before anything else writes to the file.
     */
    internal fun rescrub(log: File, marker: File) {
        if (marker.isFile && marker.readText().trim() == REDACTION_VERSION) return
        runCatching {
            if (log.isFile) {
                val tmp = File(log.parentFile, log.name + ".tmp")
                log.bufferedReader().useLines { lines ->
                    tmp.bufferedWriter().use { out -> lines.forEach { out.write(LogRedactor.clean(it)); out.newLine() } }
                }
                if (!tmp.renameTo(log)) tmp.delete()
            }
            marker.writeText(REDACTION_VERSION)
        }
    }

    private fun write(level: String, message: String) {
        val f = file ?: return
        runCatching { f.appendText("${time.format(Date())} $level $message\n") }
    }
}
