package app.cadence.backend.engine

import android.content.Context
import java.io.File

/**
 * A fatal native signal ends the process, so nothing can be shown or written to `cadence.log` while
 * it happens: the handler only appends a line to `native-crash.log`. On the next start this reads
 * what was appended since last time, copies those lines into `cadence.log` (so the shared log tells
 * the whole story) and posts one `PREVIOUS_CRASH` notice. The first run after install only records
 * the current length, so old entries never alarm anyone.
 */
object NativeCrashImport {
    private const val SEEN = "native-crash.seen"
    private const val MARKER = "=== NATIVE CRASH"

    /** The crash lines in [text] after the first [seen] characters, and the new length to remember. */
    internal fun newCrashLines(text: String, seen: Int): Pair<List<String>, Int> {
        val from = if (seen in 0..text.length) seen else 0 // the file shrank: it was cleared, read it all
        val lines = text.substring(from).lineSequence().map { it.trim() }.filter { it.startsWith(MARKER) }.toList()
        return lines to text.length
    }

    fun run(context: Context) {
        runCatching {
            val crash = ExternalLogs.nativeCrashFile(context)?.takeIf { it.isFile } ?: return
            val seenFile = File(context.filesDir, "logs/$SEEN").also { it.parentFile?.mkdirs() }
            val text = crash.readText()
            val firstRun = !seenFile.isFile
            val (lines, length) = newCrashLines(text, seenFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0)
            seenFile.writeText(length.toString())
            if (firstRun || lines.isEmpty()) return
            lines.take(5).forEach { CadenceLog.e("native", "previous run ended with $it") }
            if (lines.size > 5) CadenceLog.e("native", "... and ${lines.size - 5} more native crash line(s) in native-crash.log")
            NativeNotices.post(NativeNotice("PREVIOUS_CRASH", "app", "startup", "${lines.size} native crash line(s)"))
        }
    }
}
