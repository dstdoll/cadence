
package app.cadence.backend.engine

/**
 * Apple returns lyrics as TTML; Cadence's renderer speaks LRC (word timing, voices,
 * background vocals). This converts the subset Apple actually sends:
 *
 *   <p begin="12.345" end="15.0" ttm:agent="v1"><span begin="12.345" end="12.9">Word</span>...</p>
 *
 * into `[00:12.34]v1:<00:12.34>Word <00:12.90>...`, which LrcParser already understands.
 */
object Ttml {
    private const val DOT = java.util.regex.Pattern.DOTALL
    private val paragraph = Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val span = Regex("<span\\b([^>]*)>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val beginAttr = Regex("\\bbegin=\"([^\"]+)\"")
    private val agentAttr = Regex("\\bttm:agent=\"([^\"]+)\"")
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")

    fun toLrc(ttml: String): String {
        val sb = StringBuilder()
        for (m in paragraph.findAll(ttml)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val begin = seconds(attrs) ?: continue
            val voice = agentAttr.find(attrs)?.groupValues?.get(1)?.takeIf { it != "v0" }
            val text = StringBuilder()
            var cursor = 0
            for (s in span.findAll(body)) {
                // any loose text between spans belongs to the line
                text.append(clean(body.substring(cursor, s.range.first)))
                s.groupValues[1].let { sa ->
                    val sb1 = seconds(sa)
                    if (sb1 != null) text.append('<').append(stamp(sb1)).append('>')
                }
                text.append(clean(s.groupValues[2]))
                cursor = s.range.last + 1
            }
            text.append(clean(body.substring(cursor)))
            val line = text.toString().replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty()) continue
            sb.append('[').append(stamp(begin)).append(']')
            if (voice != null) sb.append(voice).append(':')
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun clean(raw: String): String = decode(
        raw.replace(lineBreak, " ").replace(anyTag, "")
    )

    private fun decode(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private fun seconds(attrs: String): Double? =
        beginAttr.find(attrs)?.groupValues?.get(1)?.let { it.split(':').let { p -> parseClock(p) } }

    /** TTML timestamps are `mm:ss.mmm` or `hh:mm:ss.mmm`. */
    private fun parseClock(parts: List<String>): Double? = runCatching {
        when (parts.size) {
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            else -> parts[0].toDouble()
        }
    }.getOrNull()

    private fun stamp(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val m = totalMs / 60_000
        val s = (totalMs % 60_000) / 1000
        val cs = (totalMs % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }
}
