package app.cadence.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple-artwork URL rewriting and footer formatting. The cover helpers must rewrite whatever size
 * token a URL actually carries (the bug that made every download embed one fixed size), so these
 * pin the size-agnostic behaviour and the date/runtime formatting.
 */
class ArtworkHelpersTest {

    @Test fun cover_template_restores_the_wh_token_from_any_size() {
        assertEquals("https://i/a/{w}x{h}bb.jpg", coverTemplate("https://i/a/700x700bb.jpg"))
        assertEquals("https://i/a/{w}x{h}bb.jpg", coverTemplate("https://i/a/300x300bb.jpg"))
        assertEquals("https://i/a/{w}x{h}bb.png", coverTemplate("https://i/a/3000x3000bb.png"))
    }

    @Test fun hires_rewrites_to_900_whatever_the_source_size() {
        assertEquals("https://i/a/900x900bb.jpg", hiResCover("https://i/a/300x300bb.jpg"))
        assertEquals("https://i/a/900x900bb.jpg", hiResCover("https://i/a/1400x1400bb.jpg"))
    }

    @Test fun only_the_last_size_token_is_rewritten() {
        // A digit pattern earlier in the path must not be mistaken for the size token.
        assertEquals(
            "https://i/v4/10x10/art/{w}x{h}bb.jpg",
            coverTemplate("https://i/v4/10x10/art/640x640bb.jpg"),
        )
    }

    @Test fun no_token_and_null_pass_through() {
        assertEquals("procedural-cover", hiResCover("procedural-cover"))
        assertNull(hiResCover(null))
        assertNull(coverTemplate(null))
        assertEquals("", coverTemplate(""))
    }

    @Test fun release_date_line_formats_or_falls_back() {
        val s = releaseDateLine("2022-02-04")!!
        assertTrue("has year", s.contains("2022"))
        assertTrue("has day", s.contains("4"))
        assertNotEquals("formatted, not the raw iso", "2022-02-04", s)
        assertNull(releaseDateLine(null))
        assertNull(releaseDateLine(""))
        assertEquals("unparseable falls back to the raw string", "not-a-date", releaseDateLine("not-a-date"))
    }
}
