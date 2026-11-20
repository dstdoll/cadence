package app.cadence.backend.engine.media

import android.os.Build
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.NativeNotice
import app.cadence.backend.engine.NativeNotices
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place the app's own native libraries are loaded. A load failure used to be an uncaught
 * `UnsatisfiedLinkError` on whatever thread asked first (the app died at start-up); now it is reported once,
 * precisely, and the callers decide how to degrade.
 *
 * The log line is `[NATIVE-LOAD] <lib> ok ...` or `[NATIVE-LOAD] <lib> FAILED cause=<CAUSE>: <what it means> | linker: <message>`,
 * so a report says which of the usual reasons it was (see [Cause]) without anyone reading a stack trace.
 */
object NativeLibrary {
    enum class Cause(val meaning: String) {
        /** No library for this device's CPU architecture is packaged. */
        ABI_MISMATCH("this device runs a CPU architecture the app ships no native libraries for (Cadence ships arm64-v8a only)"),
        /** The library needs a system function this Android version does not have. */
        MISSING_SYSTEM_SYMBOL("the library needs a system function this Android version does not provide (the app was built for a newer Android)"),
        /** A 32-bit/64-bit mismatch between the library and the process. */
        WRONG_BITNESS("the library's bitness (32/64-bit) does not match this process"),
        /** A library the native library depends on could not be found or loaded. */
        MISSING_DEPENDENCY("a library this one depends on could not be found"),
        OTHER("the system linker refused the library for another reason"),
    }

    data class Report(val name: String, val loaded: Boolean, val cause: Cause?, val linkerMessage: String)

    private val reports = ConcurrentHashMap<String, Report>()

    /** Loads `lib<name>.so` once. Never throws; the outcome is logged the first time and remembered. */
    fun load(name: String, loader: (String) -> Unit = System::loadLibrary): Report =
        reports.getOrPut(name) { attempt(name, loader) }

    /** For `init {}` blocks: loads, and throws the usual `UnsatisfiedLinkError` (now with the cause in its message) if it failed. */
    fun loadOrThrow(name: String) {
        val r = load(name)
        if (!r.loaded) throw UnsatisfiedLinkError("lib$name.so unavailable: ${r.cause?.name} | ${r.linkerMessage}")
    }

    /** True when the library loaded (or has not been asked for yet and loads now). */
    fun available(name: String): Boolean = load(name).loaded

    private fun attempt(name: String, loader: (String) -> Unit): Report {
        val abis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
        return try {
            loader(name)
            CadenceLog.i("native", "[NATIVE-LOAD] lib$name.so ok sdk=${runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)} abis=$abis")
            Report(name, true, null, "")
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            val cause = classify(message)
            CadenceLog.e("native", "[NATIVE-LOAD] lib$name.so FAILED cause=${cause.name}: ${cause.meaning} | sdk=${runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)} abis=$abis | linker: ${message.take(400)}")
            runCatching { NativeNotices.post(NativeNotice("LIBRARY_UNAVAILABLE", "app", "load", "lib$name.so ${cause.name}")) }
            Report(name, false, cause, message.take(400))
        }
    }

    /**
     * Reads the system linker's message. Formats: `couldn't find "libX.so"` with the searched directories
     * (taken from a real Android 10 run), and the `dlopen failed: ...` messages for a missing symbol,
     * missing dependency and bitness mismatch (the platform's documented wording).
     */
    internal fun classify(message: String): Cause {
        val m = message
        return when {
            Regex("""cannot locate symbol""").containsMatchIn(m) -> Cause.MISSING_SYSTEM_SYMBOL
            Regex("""is (32|64)-bit instead of (32|64)-bit""").containsMatchIn(m) -> Cause.WRONG_BITNESS
            Regex("""library "[^"]+" not found|needed by""").containsMatchIn(m) -> Cause.MISSING_DEPENDENCY
            Regex("""couldn't find "[^"]+"""").containsMatchIn(m) -> Cause.ABI_MISMATCH
            else -> Cause.OTHER
        }
    }
}
