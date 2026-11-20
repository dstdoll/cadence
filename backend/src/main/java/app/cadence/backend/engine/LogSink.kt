package app.cadence.backend.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one writer of `cadence.log`. [CadenceLog], `AppLog` and the live Apple-logcat capture all append
 * through here, so there is one line format, one lock (no torn or interleaved lines) and one place that
 * decides how a stack trace is written.
 *
 * Line: `2026-11-20 01:17:15.245 E/native [cadence-kd] message`  (date time level/tag [thread] message).
 * Levels: `I` info, `E` error, `A` a line captured from Apple's libraries via logcat (the message starts
 * with the original logcat priority letter). A stack trace follows an error line, indented with `    at`.
 */
internal object LogSink {
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile var file: File? = null

    fun format(level: String, tag: String, message: String, thread: String, now: Long = System.currentTimeMillis()): String =
        synchronized(lock) { "${stamp.format(Date(now))} $level/$tag [$thread] $message" }

    fun append(level: String, tag: String, message: String, trace: String? = null) {
        val f = file ?: return
        val line = format(level, tag, message, Thread.currentThread().name)
        synchronized(lock) {
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText(if (trace.isNullOrEmpty()) "$line\n" else "$line\n$trace\n")
            }
        }
    }

    fun clear() = synchronized(lock) { runCatching { file?.writeText("") } }

    /**
     * Class and message of the throwable and each cause (up to 3), with their first [frames] frames,
     * redacted like every other line. The shape of a Java stack trace, so it reads as one.
     */
    fun trace(t: Throwable, frames: Int = 12): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 3) {
            append(if (depth == 0) "    " else "    Caused by: ")
            append(LogRedactor.clean("${cur.javaClass.name}: ${cur.message.orEmpty()}")).append('\n')
            cur.stackTrace.take(frames).forEach { append("    at ").append(it).append('\n') }
            if (cur.stackTrace.size > frames) append("    ... ${cur.stackTrace.size - frames} more\n")
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
    }.trimEnd()
}
