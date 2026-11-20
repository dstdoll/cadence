package app.cadence.backend.engine

/**
 * A short, fixed-shape block put at the top of an exported log: the facts a reader needs first
 * (version, device, how many errors, which native error classes, the account/provisioning state and the
 * last errors), computed from the log text so it can never disagree with it.
 */
object LogSummary {
    // Current format: `2026-11-20 01:17:15.245 E/native [thread] message`.
    private val levelLine = Regex("""^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d\.\d+) ([IEA])/([^ ]+) \[([^\]]*)\] (.*)$""")
    // Older builds wrote two formats into the same file; reports from them are still read:
    //   `01:17:15.245 E/native message`   and   `2026-11-20 01:17:14.397 I/<thread> Tag: message`.
    private val legacyShort = Regex("""^(\d\d:\d\d:\d\d\.\d+) ([IE])/([^ ]+) (.*)$""")
    private val legacyLong = Regex("""^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d\.\d+) ([IE])/(\S+) ([^:\s]+): (.*)$""")

    private data class Parsed(val time: String, val level: String, val tag: String, val msg: String)

    private fun parse(l: String): Parsed? {
        levelLine.matchEntire(l)?.let { return Parsed(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[5]) }
        legacyLong.matchEntire(l)?.let { return Parsed(it.groupValues[1], it.groupValues[2], it.groupValues[4], it.groupValues[5]) }
        legacyShort.matchEntire(l)?.let { return Parsed(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }
        return null
    }
    private val nativeClass = Regex("""\[CXX-[A-Z]+\] class=([A-Z_]+)""")
    private val raise = Regex("""\[CXX-RAISE\] type=(\S+) x\d+ at """)

    fun build(log: String, header: List<String>, lastErrors: Int = 5): String {
        val lines = log.lineSequence().toList()
        var errors = 0
        val classes = LinkedHashMap<String, Int>()
        val errorLines = ArrayList<String>()
        var provisioning: String? = null
        var subscription: String? = null
        var storefront: String? = null
        var appleLines = 0
        val raised = LinkedHashMap<String, Int>()
        var hook: String? = null
        var starts = 0
        for (l in lines) {
            if (l.startsWith("=== start")) starts++
            val (time, level, tag, msg) = parse(l) ?: continue
            if (level == "A") appleLines++
            if (level == "E") { errors++; errorLines += "$time $tag $msg".take(200) }
            nativeClass.find(msg)?.let { classes.merge(it.groupValues[1], 1, Int::plus) }
            raise.find(msg)?.let { raised.merge(it.groupValues[1], 1, Int::plus) }
            if (msg.startsWith("[HOOK] logger=")) hook = msg.removePrefix("[HOOK] ")
            if (tag == "provisioning") provisioning = "$time $msg"
            if (tag == "auth" && msg.startsWith("subscription:")) subscription = msg.removePrefix("subscription:").trim()
            if (tag == "amp" && msg.startsWith("storefront=")) storefront = msg.removePrefix("storefront=").substringBefore(' ')
        }
        return buildString {
            appendLine("===== summary =====")
            header.forEach { appendLine(it) }
            appendLine("process starts in this log=$starts  error lines=$errors  apple/native logcat lines=$appleLines")
            appendLine("native error classes: " + if (classes.isEmpty()) "none" else classes.entries.joinToString(" ") { "${it.key}=${it.value}" })
            appendLine("native hooks: ${hook ?: "not reported (older build, or the library did not load)"}")
            appendLine("exceptions thrown inside Apple's libraries (full records, first 3 per site): " +
                if (raised.isEmpty()) "none recorded" else raised.entries.joinToString(" ") { "${it.key}=${it.value}" })
            appendLine("storefront=${storefront ?: "unknown"}")
            appendLine("subscription: ${subscription ?: "not recorded"}")
            appendLine("provisioning: ${provisioning ?: "not recorded"}")
            val hints = hints(classes, subscription, provisioning)
            if (hints.isNotEmpty()) { appendLine("hints:"); hints.forEach { appendLine("  - $it") } }
            appendLine("last errors:")
            errorLines.takeLast(lastErrors).forEach { appendLine("  $it") }
            if (errorLines.isEmpty()) appendLine("  none")
            appendLine("format: date time LEVEL/tag [thread] message; I info, E error (stack trace follows indented), A line from Apple's libraries via logcat (priority letter, tNNN = thread id)")
        }.trimEnd()
    }

    private fun hints(classes: Map<String, Int>, subscription: String?, provisioning: String?): List<String> = buildList {
        if ((classes["KEY_REJECTED"] ?: 0) > 0) {
            if (subscription == null) add("keys were rejected and the subscription state was never recorded: check the account has an active Apple Music subscription")
            else if (Regex("""active=false""").containsMatchIn(subscription)) add("keys were rejected and Apple reports the subscription as NOT active")
            if (provisioning != null && Regex("""fsi\.pdat=missing|adi\.pb=missing""").containsMatchIn(provisioning)) add("keys were rejected and FairPlay/device state files are missing: provisioning did not complete")
        }
        if ((classes["RUST-PANIC"] ?: 0) > 0 || log("CXX-TERMINATE", classes)) add("the native layer panicked or terminated: see the [RUST-PANIC]/[CXX-TERMINATE] line and the A/ lines before it")
    }

    private fun log(key: String, classes: Map<String, Int>) = classes.containsKey(key)
}
