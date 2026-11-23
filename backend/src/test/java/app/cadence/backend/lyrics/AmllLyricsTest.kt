package app.cadence.backend.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmllLyricsTest {

    @Test
    fun parseResponse_extractsTtmlOrNull() {
        val json = """{"data":{"lyrics":"<tt><body><p begin=\"00:01.00\" end=\"00:02.00\">Hi</p></body></tt>"}}"""
        assertEquals(
            "<tt><body><p begin=\"00:01.00\" end=\"00:02.00\">Hi</p></body></tt>",
            AmllLyrics.parseResponse(json),
        )
        assertNull(AmllLyrics.parseResponse("""{"data":{}}"""))
        assertNull(AmllLyrics.parseResponse("not-json"))
    }
}
