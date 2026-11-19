package app.cadence.backend.lyrics

import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    private fun synced(src: String) = LrcParser.parse(src) as Lyrics.Synced

    @Test fun standardLines() {
        val l = synced("[ti:x]\n[00:01.50]Hello\n[00:04.00]World\n")
        assertEquals(2, l.lines.size)
        assertEquals(1_500, l.lines[0].startMs)
        assertEquals(4_000, l.lines[0].endMs)
        assertEquals(4_000 + LrcParser.TAIL_MS, l.lines[1].endMs)
    }

    @Test fun repeatedStampsAreExpandedAndSorted() {
        val l = synced("[00:10.00][00:02.00]Chorus\n[00:05.00]Verse")
        assertEquals(listOf(2_000L, 5_000L, 10_000L), l.lines.map { it.startMs })
    }

    @Test fun enhancedWordsAndTrailingEnd() {
        val l = synced("[00:01.00]v2:<00:01.00>Two <00:01.50>words<00:02.25>")
        val line = l.lines.single()
        assertEquals(Voice.Secondary, line.voice)
        assertEquals("Two words", line.text)
        assertEquals(2_250, line.endMs)
        assertEquals(1_500, line.words[0].endMs)
        assertEquals(2_250, line.words[1].endMs)
    }

    @Test fun backgroundAttachesToPreviousLine() {
        val l = synced("[00:01.00]<00:01.00>Lead<00:02.00>\n[bg:<00:01.50>(ooh)<00:02.50>]")
        assertEquals("(ooh)", l.lines.single().background.single().text)
        assertEquals(2_500, l.lines.single().background.single().endMs)
    }

    @Test fun offsetShiftsEarlier() {
        val l = synced("[offset:500]\n[00:02.00]Hi")
        assertEquals(1_500, l.lines.single().startMs)
    }

    @Test fun threeDigitAndOneDigitFractions() {
        val l = synced("[00:01.5]a\n[00:02.250]b")
        assertEquals(listOf(1_500L, 2_250L), l.lines.map { it.startMs })
    }

    @Test fun plainAndEmpty() {
        assertTrue(LrcParser.parse("just\nwords") is Lyrics.Plain)
        assertEquals(Lyrics.None, LrcParser.parse("  "))
    }

    @Test fun malformedLinesAreIgnored() {
        val l = synced("[xx:yy]junk\n[00:03.00]ok\n[00:04.00]")
        assertEquals(listOf("ok"), l.lines.map { it.text })
    }

    @Test fun cursorFindsLinesAndGaps() {
        val l = synced(
            "[00:05.00]<00:05.00>one<00:06.00>\n" +
                "[00:20.00]<00:20.00>two<00:21.00>\n" +
                "[00:22.00]<00:22.00>three<00:23.00>",
        ).lines
        assertEquals(LyricCursor.Gap(0, 0.5f), LyricTiming.cursor(l, 2_500))
        assertEquals(LyricCursor.Line(0), LyricTiming.cursor(l, 5_500))
        assertTrue(LyricTiming.cursor(l, 10_000) is LyricCursor.Gap)
        assertEquals(LyricCursor.Line(1), LyricTiming.cursor(l, 21_500)) // short gap: stays on line
        assertEquals(2, LyricTiming.lineAt(l, 99_000))
        assertEquals(-1, LyricTiming.lineAt(l, 0))
    }

    @Test fun wordProgressClamps() {
        val w = synced("[00:01.00]<00:01.00>a<00:02.00>").lines.single().words.single()
        assertEquals(0f, LyricTiming.wordProgress(w, 0), 0f)
        assertEquals(0.5f, LyricTiming.wordProgress(w, 1_500), 0.001f)
        assertEquals(1f, LyricTiming.wordProgress(w, 9_000), 0f)
    }
}
