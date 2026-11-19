package app.cadence.backend.lyrics

import app.cadence.backend.model.LyricLine
import app.cadence.backend.model.LyricWord
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Voice

/**
 * Parses standard LRC, word-timed "enhanced" LRC, and the small Cadence dialect:
 *
 *  [mm:ss.xx]text                          plain synced line (several leading stamps allowed)
 *  [mm:ss.xx]v2:<mm:ss.xx>a <mm:ss.xx>b<mm:ss.xx>   word-timed line, voice v1/v2/v3
 *  [bg:<mm:ss.xx>(ooh)<mm:ss.xx>]          background vocals for the previous line
 *  [offset:+250]                           global offset in ms (positive = lyrics earlier)
 *
 * Text with no timestamps at all becomes [Lyrics.Plain].
 */
object LrcParser {
    private val lineStamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metaTag = Regex("""^\[([a-zA-Z]+):(.*)]$""")
    private val voiceTag = Regex("""^v([123]):""")

    /** Default length given to the last line when nothing else bounds it. */
    const val TAIL_MS = 5_000L

    /**
     * How long a word-timed line's final word is held when it has no closing stamp, instead of
     * stretching it all the way to the next line. Keeping the content end honest lets the view show
     * the waiting dots across a long instrumental (and after the last line) rather than freezing the
     * last word lit. Chosen so only genuinely long waits (content end + [LyricTiming.GAP_MS]) qualify.
     */
    const val WORD_TAIL_MS = 2_500L

    fun parse(source: String): Lyrics {
        if (source.isBlank()) return Lyrics.None
        var offset = 0L
        val raw = mutableListOf<RawLine>()

        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[bg:")) {
                val body = line.removePrefix("[bg:").removeSuffix("]")
                raw.lastOrNull()?.let { it.background = body }
                continue
            }

            val stamps = mutableListOf<Long>()
            var rest = line
            while (true) {
                val m = lineStamp.find(rest) ?: break
                stamps += m.toMillis()
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                metaTag.matchEntire(line)?.let { m ->
                    if (m.groupValues[1].equals("offset", ignoreCase = true)) {
                        offset = m.groupValues[2].trim().toLongOrNull() ?: 0L
                    }
                }
                continue
            }

            var voice = Voice.Primary
            voiceTag.find(rest)?.let { m ->
                voice = when (m.groupValues[1]) { "2" -> Voice.Secondary; "3" -> Voice.Both; else -> Voice.Primary }
                rest = rest.substring(m.range.last + 1)
            }
            stamps.forEach { raw += RawLine(it - offset, rest, voice) }
        }

        if (raw.isEmpty()) {
            val text = source.lineSequence().filterNot { metaTag.matches(it.trim()) }.joinToString("\n").trim()
            return if (text.isEmpty()) Lyrics.None else Lyrics.Plain(text)
        }

        raw.sortBy { it.startMs }
        val lines = raw.mapIndexed { i, r ->
            val nextStart = raw.getOrNull(i + 1)?.startMs
            val (words, trailing) = parseWords(r.body, offset)
            val text = if (words.isNotEmpty()) words.joinToString(" ") { it.text } else r.body.trim()
            // For a word-timed line with no closing stamp, bound the content end to the last word +
            // a short tail instead of the next line's start, so a long wait surfaces as a gap rather
            // than a held last word. Plain (word-less) lines keep running to the next line.
            val end = trailing
                ?: words.lastOrNull()?.let { last ->
                    val capped = last.startMs + WORD_TAIL_MS
                    if (nextStart != null) minOf(capped, nextStart) else capped
                }
                ?: nextStart ?: (r.startMs + TAIL_MS)
            val bounded = words.closeEnds(end)
            val bg = r.background?.let { b -> parseWords(b, offset).let { (w, t) -> w.closeEnds(t ?: end) } }.orEmpty()
            LyricLine(r.startMs.coerceAtLeast(0), end, text, bounded, r.voice, bg)
        }.filter { it.text.isNotBlank() }
        return Lyrics.Synced(lines)
    }

    /** Words carry only their start; the trailing stamp (if any) is the end of the last word. */
    private fun parseWords(body: String, offset: Long): Pair<List<LyricWord>, Long?> {
        val matches = wordStamp.findAll(body).toList()
        if (matches.isEmpty()) return emptyList<LyricWord>() to null
        val words = mutableListOf<LyricWord>()
        var trailing: Long? = null
        matches.forEachIndexed { i, m ->
            val start = m.toMillis() - offset
            val textEnd = matches.getOrNull(i + 1)?.range?.first ?: body.length
            val word = body.substring(m.range.last + 1, textEnd).trim()
            if (word.isEmpty()) trailing = start else words += LyricWord(start, start, word)
        }
        return words to trailing
    }

    private fun List<LyricWord>.closeEnds(lineEnd: Long) = mapIndexed { i, w ->
        w.copy(endMs = getOrNull(i + 1)?.startMs ?: lineEnd)
    }

    private fun MatchResult.toMillis(): Long {
        val min = groupValues[1].toLong()
        val sec = groupValues[2].toLong()
        val frac = groupValues[3]
        val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return min * 60_000 + sec * 1_000 + ms
    }

    private class RawLine(val startMs: Long, val body: String, val voice: Voice) {
        var background: String? = null
    }
}

/** What the lyrics view should highlight at a given playback position. */
sealed interface LyricCursor {
    /** Before the first line or inside a long instrumental gap; [progress] fills the waiting dots. */
    data class Gap(val nextIndex: Int, val progress: Float) : LyricCursor
    data class Line(val index: Int) : LyricCursor
}

object LyricTiming {
    /** Gaps longer than this between two lines show the instrumental indicator. */
    const val GAP_MS = 4_000L

    /** Index of the last line whose start <= [positionMs], or -1. Binary search. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    fun cursor(lines: List<LyricLine>, positionMs: Long): LyricCursor {
        if (lines.isEmpty()) return LyricCursor.Gap(0, 0f)
        val i = lineAt(lines, positionMs)
        if (i < 0) {
            val first = lines.first().startMs
            return if (first >= GAP_MS) LyricCursor.Gap(0, (positionMs.toFloat() / first).coerceIn(0f, 1f))
            else LyricCursor.Line(0)
        }
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        if (next != null && positionMs > line.endMs && next.startMs - line.endMs >= GAP_MS) {
            val p = (positionMs - line.endMs).toFloat() / (next.startMs - line.endMs)
            return LyricCursor.Gap(i + 1, p.coerceIn(0f, 1f))
        }
        // After the final sung line, a long outro shows the waiting dots too (nextIndex past the end).
        if (next == null && positionMs - line.endMs >= GAP_MS) {
            val p = (positionMs - line.endMs).toFloat() / 8_000f
            return LyricCursor.Gap(lines.size, p.coerceIn(0f, 0.9f))
        }
        return LyricCursor.Line(i)
    }

    /** 0..1 fill of [word] at [positionMs]. */
    fun wordProgress(word: LyricWord, positionMs: Long): Float = when {
        positionMs <= word.startMs -> 0f
        positionMs >= word.endMs -> 1f
        else -> (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs)
    }
}
