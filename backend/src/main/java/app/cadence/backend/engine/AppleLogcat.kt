package app.cadence.backend.engine

/**
 * Apple's libraries log to Android's logcat only (`FairPlay*`, `SVError`, ...), and Android's log buffer
 * is small and rolls over, so by the time a user exports logs the lines that explain a failure are gone.
 * This follows the app's OWN process in logcat (allowed without any permission) from start-up and
 * writes the relevant lines into `cadence.log` as they happen, as `A/<tag>` lines, redacted.
 *
 * What is kept ([accepts]): every warning/error/fatal of any tag, and the Apple/native tags at any
 * priority. Our own `Cadence` tag is dropped (cadence.log already has it). A flood is bounded: at most
 * [Pipeline.maxPerSecond] lines a second, and an identical repeated line is collapsed to a count.
 */
object AppleLogcat {
    private val THREADTIME = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s?(.*)$""")
    private val APPLE_TAGS = listOf(
        "FairPlay", "SVError", "SVFootHill", "StoreServices", "cadence-native", "CKC", "CoreADI",
        "mediaplatform", "androidappmusic", "SVPlayback", "Lease",
    )

    /** A parsed `-v threadtime` line. */
    data class Entry(val tid: String, val priority: Char, val tag: String, val message: String)

    fun parse(line: String): Entry? =
        THREADTIME.matchEntire(line)?.let { Entry(it.groupValues[1], it.groupValues[2][0], it.groupValues[3].trim(), it.groupValues[4]) }

    // Warnings the platform prints about every app: they bury the lines that explain a failure.
    private val NOISE = listOf(
        "Accessing hidden ", "Unknown bits set in runtime_flags", "Unexpected CPU variant for",
        "Unable to set property \"qemu.", "mapper 3.x is not supported",
    )

    fun accepts(e: Entry): Boolean {
        if (e.tag == "Cadence") return false
        val apple = APPLE_TAGS.any { e.tag.contains(it, ignoreCase = true) }
        if (!apple && NOISE.any { e.message.startsWith(it) }) return false
        return e.priority in "WEF" || APPLE_TAGS.any { e.tag.contains(it, ignoreCase = true) || e.message.contains(it, ignoreCase = true) }
    }

    /** Filter + rate limit + repeat collapsing. Pure: the clock is injected. Returns the messages to write. */
    class Pipeline(val maxPerSecond: Int = 25, private val now: () -> Long = System::currentTimeMillis) {
        private var windowStart = 0L
        private var inWindow = 0
        private var dropped = 0
        private var lastKey: String? = null
        private var repeats = 0

        /** `A/<tag>` payloads (tag to message) produced by [line]; empty when it is filtered, a repeat or over the limit. */
        fun process(line: String): List<Pair<String, String>> {
            val e = parse(line)?.takeIf { accepts(it) } ?: return emptyList()
            val out = ArrayList<Pair<String, String>>(2)
            val t = now()
            if (t - windowStart >= 1_000L) {
                if (dropped > 0) out += "logcat" to "$dropped Apple/native line(s) dropped in the last second (rate limit ${maxPerSecond}/s)"
                windowStart = t; inWindow = 0; dropped = 0
            }
            val key = "${e.priority}|${e.tag}|${e.message}"
            if (key == lastKey) { repeats++; return out }
            if (repeats > 0) out += (lastKey!!.split('|', limit = 3)[1]) to "(previous line repeated $repeats more time(s))"
            lastKey = key; repeats = 0
            if (inWindow >= maxPerSecond) { dropped++; return out }
            inWindow++
            out += e.tag to "${e.priority} t${e.tid} ${e.message}"
            return out
        }
    }

    /** True once the native liblog hook is delivering these lines: the logcat follower then stands down. */
    @Volatile internal var liveNative = false

    /** The native layer announces its hook with `[HOOK] logger=on ...`; from then on the follower stands down. */
    internal fun noteNativeEvent(message: String) {
        if (message.startsWith("[HOOK] logger=on")) liveNative = true
    }

    /** The one place an `A/` line is written; redacted; dropped while the native hook is the source of such lines (unless it is that source). */
    internal fun write(tag: String, line: String) = LogSink.append("A", tag, LogRedactor.clean(line))

    @Volatile private var started = false

    /** Starts the follower once per process. Never throws; a logcat that cannot run just means no live capture. */
    fun start() {
        if (started) return
        started = true
        Thread({ follow() }, "cadence-logcat").apply { isDaemon = true; start() }
    }

    private fun follow() {
        var attempt = 0
        while (attempt < 3) {
            val started = System.currentTimeMillis()
            val ok = runCatching {
                val proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", "500", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true).start()
                val pipe = Pipeline()
                proc.inputStream.bufferedReader().forEachLine { l ->
                    if (!liveNative) pipe.process(l).forEach { (tag, msg) -> write(tag, msg) }
                }
            }.isSuccess
            // A follower that ran for a while and then ended (process teardown) is not a failure to retry hard.
            if (ok && System.currentTimeMillis() - started > 60_000L) return
            attempt++
            Thread.sleep(2_000L * attempt)
        }
        CadenceLog.i("logcat", "live Apple log capture stopped after 3 attempts (logcat unavailable here); the export falls back to a logcat snapshot")
    }
}
