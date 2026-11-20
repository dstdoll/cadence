
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

    fun init(context: Context) {
        if (file != null) return
        ExternalLogs.prepare(context)
        runCatching {
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            file = File(dir, "cadence.log")
            LogSink.file = file
            file?.let { rescrub(it, File(dir, ".redaction")) }
            file?.let { trimToTail(it) }
            file?.appendText("\n=== start ${Date()} ===\n")
        }
        AppleLogcat.start()
    }

    fun i(tag: String, rawMessage: String) {
        val message = LogRedactor.clean(rawMessage)
        if (tag == "amp" && message.startsWith("storefront=")) app.cadence.backend.engine.Provisioning.storefront = message.removePrefix("storefront=").substringBefore(' ')
        runCatching { Log.i(TAG, "$tag: $message") }
        write("I", tag, message)
    }

    fun e(tag: String, rawMessage: String, t: Throwable? = null) {
        val message = LogRedactor.clean(rawMessage)
        runCatching { Log.e(TAG, "$tag: $message", t) }
        write("E", tag, message, t?.let { LogSink.trace(it) })
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

    private const val MAX_BYTES = 3_000_000L
    private const val KEEP_CHARS = 1_500_000

    /** The log is appended forever; past [MAX_BYTES] only the newest [KEEP_CHARS] (from a line start) are kept. */
    internal fun trimToTail(log: File, maxBytes: Long = MAX_BYTES, keep: Int = KEEP_CHARS) {
        runCatching {
            if (!log.isFile || log.length() <= maxBytes) return
            val text = log.readText()
            val tail = text.takeLast(keep)
            val cut = tail.indexOf('\n').let { if (it >= 0) it + 1 else 0 }
            log.writeText("=== earlier lines trimmed (${text.length - tail.length + cut} chars) ===\n" + tail.substring(cut))
        }
    }

    private fun write(level: String, tag: String, message: String, trace: String? = null) = LogSink.append(level, tag, message, trace)
}
