package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTemplateTest {

    private val v = PathTemplate.SAMPLE

    @Test
    fun `album folder expands artist and album`() {
        assertEquals("Daft Punk/Random Access Memories", PathTemplate.folderPath("{artist}/{album}", v))
    }

    @Test
    fun `year and full date tokens come from the release date`() {
        assertEquals("Daft Punk/Random Access Memories (2013)", PathTemplate.folderPath("{artist}/{album} ({year})", v))
        assertEquals("Daft Punk/2013-05-17/Random Access Memories", PathTemplate.folderPath("{artist}/{date}/{album}", v))
        assertEquals("2013", v.year)
    }

    @Test
    fun `track tokens are zero padded and disc aware`() {
        assertEquals("07 Touch", PathTemplate.fileName("{track:02d} {title}", v))
        assertEquals("7 - 1 - Touch", PathTemplate.fileName("{track} - {disc} - {title}", v))
        assertEquals("01-07 Touch", PathTemplate.fileName("{disc:02d}-{track:02d} {title}", v))
    }

    @Test
    fun `playlist template uses the collection name and artist-title file`() {
        val playlist = "{playlist}"
        val file = "{track:02d} {artist} - {title}"
        assertEquals("Late Night Drives/07 Daft Punk - Touch", PathTemplate.path(playlist, file, v))
    }

    @Test
    fun `path separators are sanitised and traversal is not possible`() {
        val evil = v.copy(title = "../../etc/passwd", album = "AC/DC")
        val path = PathTemplate.path("{artist}/{album}", "{title}", evil)
        val segments = path.split('/')
        assertTrue(segments.none { it == ".." || it == "." })
        assertTrue(segments.none { it.startsWith(".") })
        assertTrue(path.contains("passwd"))
        assertTrue(path.startsWith("Daft Punk/AC_DC/"))
    }

    @Test
    fun `illegal characters and trailing dots are removed per segment`() {
        val odd = v.copy(title = "  What?: A*Song.  ", album = "Vol|1")
        assertEquals("What_ A_Song", PathTemplate.fileName("{title}", odd).trim())
        assertEquals("Vol_1", PathTemplate.folderPath("{album}", odd))
    }

    @Test
    fun `missing values collapse blank segments`() {
        val empty = TemplateValues(title = "Untitled")
        assertEquals("Untitled", PathTemplate.path("", "{title}", empty))
        assertEquals("Untitled", PathTemplate.path("{artist}/{album}", "{title}", empty))
    }

    @Test
    fun `unknown tokens survive so typos are visible`() {
        assertEquals("Daft Punk/{unknown}", PathTemplate.folderPath("{artist}/{unknown}", v))
    }

    @Test
    fun `quality tokens expand to the resolved rendition`() {
        assertEquals("ALAC", PathTemplate.fileName("{codec}", v))
        assertEquals("24B", PathTemplate.fileName("{bitdepth}", v))
        assertEquals("96kHz", PathTemplate.fileName("{samplerate}", v))
        assertEquals("[ALAC] [24B-96kHz]", PathTemplate.fileName("{quality}", v))
        assertEquals(
            "Daft Punk/Random Access Memories (2013) [ALAC] [24B-96kHz]",
            PathTemplate.folderPath("{albumartist}/{album} ({year}) {quality}", v),
        )
    }

    @Test
    fun `atmos renditions are named apart from stereo ones`() {
        assertEquals("[ATMOS] [48kHz]", PathTemplate.fileName("{quality}", v.copy(codec = "atmos", bitDepth = 0, sampleRate = 48_000)))
        assertEquals("[SPATIAL]", PathTemplate.fileName("{quality}", v.copy(codec = "spatial", bitDepth = 0, sampleRate = 0)))
    }

    @Test
    fun `sample rate renders fractional kHz`() {
        assertEquals("44.1kHz", PathTemplate.fileName("{samplerate}", v.copy(sampleRate = 44100)))
        assertEquals("48kHz", PathTemplate.fileName("{samplerate}", v.copy(sampleRate = 48000)))
    }

    @Test
    fun `quality tokens are empty-safe and the combined form drops unknown parts`() {
        val none = TemplateValues(title = "X")
        assertEquals("", PathTemplate.fileName("{quality}", none).trim().removeSuffix("X").trim())
        assertEquals("X", PathTemplate.fileName("{title}{quality}{codec}", none))
        // AAC has no PCM bit depth: the combined form keeps only what is known.
        assertEquals("[AAC] [48kHz]", PathTemplate.fileName("{quality}", none.copy(codec = "aac", sampleRate = 48000)))
        assertEquals("[ALAC]", PathTemplate.fileName("{quality}", none.copy(codec = "alac")))
    }

    @Test
    fun `token categories keep folders and playlists consistent`() {
        assertTrue("{title}" in PathTemplate.TRACK_ONLY_TOKENS)
        assertTrue("{track}" in PathTemplate.TRACK_ONLY_TOKENS)
        assertTrue("{album}" !in PathTemplate.TRACK_ONLY_TOKENS)
        assertEquals(setOf("{playlist}"), PathTemplate.PLAYLIST_ONLY_TOKENS)
        assertTrue(PathTemplate.TOKENS.containsAll(listOf("{codec}", "{bitdepth}", "{samplerate}", "{quality}")))
    }

    @Test
    fun `sample preview used by settings stays readable`() {
        val folder = PathTemplate.folderPath("{artist}/{album} ({year})", v)
        val file = PathTemplate.fileName("{track:02d} {title}", v)
        assertEquals("Daft Punk/Random Access Memories (2013)/07 Touch", PathTemplate.path(
            "{artist}/{album} ({year})", "{track:02d} {title}", v,
        ))
        assertEquals("$folder/$file", PathTemplate.path("{artist}/{album} ({year})", "{track:02d} {title}", v))
    }
}
