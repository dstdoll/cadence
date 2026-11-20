package app.cadence.core.design

import app.cadence.backend.links.AppleLink
import app.cadence.backend.links.AppleLinks
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongShareTextTest {
    private val ar = Artist("ar1", "Pink Floyd")
    private val al = Album("1665303755", "The Dark Side of the Moon", "ar1", 1973, "Rock", listOf("#fff"))
    private fun info(id: String) = TrackInfo(Track(id, "Breathe", "ar1", al.id, 1, 1000, "u"), al, ar)

    @Test fun theSharedTextEndsWithALinkCadenceCanOpenAgain() {
        val text = songShareText(info("1665303759"), "gb")
        assertTrue(text.startsWith("“Breathe” by Pink Floyd, from The Dark Side of the Moon\n"))
        assertEquals(AppleLink.Song("1665303759", "gb"), AppleLinks.parse(text))
    }

    @Test fun aSongWithoutACatalogueIdSharesTheLineOnly() {
        assertEquals("“Breathe” by Pink Floyd, from The Dark Side of the Moon", songShareText(info("lib-abc"), "us"))
    }
}
