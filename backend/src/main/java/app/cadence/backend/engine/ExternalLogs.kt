package app.cadence.backend.engine

import android.content.Context
import java.io.File

/**
 * The app-specific external log directory (`Android/data/app.cadence/files/logs`), which other
 * apps cannot list but a USB file browser can.
 *
 * Logs now live in private storage. Builds before that wrote their logs here without redaction,
 * so every `.log` file other than the crash marker is deleted. The directory itself is kept: the native crash
 * handler writes `native-crash.log` to this fixed path from a signal handler (signal, pid and
 * tid only) and cannot create directories there.
 */
object ExternalLogs {
    const val NATIVE_CRASH = "native-crash.log"

    /** Creates the directory for the crash marker and removes the pre-redaction logs. Idempotent. */
    fun prepare(context: Context) {
        runCatching {
            // getExternalFilesDir creates the directory if needed.
            val dir = context.getExternalFilesDir("logs") ?: return
            purgeLegacy(dir)
        }
    }

    /** Deletes the legacy logs in [dir]; anything else, the crash marker included, stays. */
    fun purgeLegacy(dir: File): List<String> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".log") && it.name != NATIVE_CRASH }
            .sortedBy { it.name }
            .filter { it.delete() }
            .map { it.name }

    /** Where the native crash handler writes its marker. */
    fun nativeCrashFile(context: Context): File? =
        context.getExternalFilesDir("logs")?.let { File(it, NATIVE_CRASH) }
}
