package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLyricsTest {

    private val sampleTtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body>
            <div>
              <p begin="00:01.500" end="00:04.250"><span>Hel</span><span>lo </span><span>world</span></p>
              <p begin="65.25s" end="70.00s">Second &amp; final line</p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun parseLines_handlesSyllableSpansAndTimestamps() {
        val lines = Lyrics.parseLines(sampleTtml)
        assertEquals(2, lines.size)
        assertEquals(1.5, lines[0].beginSec, 0.001)
        assertEquals("Hello world", lines[0].text)
        assertEquals(65.25, lines[1].beginSec, 0.001)
        assertEquals("Second & final line", lines[1].text)
    }

    @Test
    fun format_lrcProducesLineTimedCues() {
        val out = Lyrics.format(sampleTtml, "lrc")
        assertEquals("[00:01.50]Hello world\n[01:05.25]Second & final line", out)
    }

    @Test
    fun format_srtProducesValidSubRipCues() {
        val out = Lyrics.format(sampleTtml, "srt")
        val expected = """
            1
            00:00:01,500 --> 00:01:05,250
            Hello world

            2
            00:01:05,250 --> 00:01:09,250
            Second & final line
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun format_txtStripsTimestamps() {
        val out = Lyrics.format(sampleTtml, "txt")
        assertEquals("Hello world\nSecond & final line", out)
    }

    @Test
    fun format_ttmlPreservesRawXml() {
        val out = Lyrics.format(sampleTtml, "ttml")
        assertEquals(sampleTtml, out)
    }

    @Test
    fun toTtml_escapesXmlAndFormatsBeginEnd() {
        val lines = Lyrics.parseLines(sampleTtml)
        val rebuilt = Lyrics.toTtml(lines)
        assertTrue(rebuilt.contains("<p begin=\"1.500\" end=\"65.250\">Hello world</p>"))
        assertTrue(rebuilt.contains("Second &amp; final line"))
    }
}
