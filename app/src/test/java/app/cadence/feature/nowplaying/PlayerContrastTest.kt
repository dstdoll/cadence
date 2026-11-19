package app.cadence.feature.nowplaying

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's title and artist stay readable on every colour its background can show. */
class PlayerContrastTest {
    private fun backdrops(cs: androidx.compose.material3.ColorScheme) =
        playerBackdrops(cs.surfaceContainerLowest, listOf(cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer))

    @Test
    fun contrastRatioMatchesWcag() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(Color.Gray, Color.Gray), 0.0001f)
    }

    @Test
    fun backdropsCoverTheBaseAndEveryBlobOverlap() {
        val cs = darkColorScheme()
        assertEquals(8, backdrops(cs).size) // base + 3 singles + 3 pairs + all three
    }

    @Test
    fun darkTextOnADarkArtworkThemeTurnsLight() {
        // The reported case: black title over a dark, artwork-tinted player.
        val cs = darkColorScheme(
            surfaceContainerLowest = Color(0xFF0B1A12), primaryContainer = Color(0xFF1F5135),
            tertiaryContainer = Color(0xFF2E4B57), secondaryContainer = Color(0xFF3A4A3F),
            onSurface = Color(0xFF1C1B1F), onSurfaceVariant = Color(0xFF2A2A2E),
        )
        val text = playerTextColors(cs)
        assertTrue("title ${worstContrast(text.title, backdrops(cs))}", worstContrast(text.title, backdrops(cs)) >= 4.5f)
        assertTrue("artist ${worstContrast(text.artist, backdrops(cs))}", worstContrast(text.artist, backdrops(cs)) >= 4.5f)
        assertTrue(text.title.red > 0.5f && text.artist.red > 0.5f)
    }

    @Test
    fun lightTextOnALightThemeTurnsDark() {
        val cs = lightColorScheme(onSurface = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFFE0E0E0))
        val text = playerTextColors(cs)
        assertTrue(worstContrast(text.title, backdrops(cs)) >= 4.5f)
        assertTrue(worstContrast(text.artist, backdrops(cs)) >= 4.5f)
        assertTrue(text.title.red < 0.5f)
    }

    @Test
    fun aThemeThatAlreadyReadsIsLeftUntouched() {
        for (cs in listOf(darkColorScheme(), lightColorScheme())) {
            val text = playerTextColors(cs)
            if (worstContrast(cs.onSurface, backdrops(cs)) >= 4.5f) assertEquals(cs.onSurface, text.title)
            assertTrue(worstContrast(text.title, backdrops(cs)) >= 4.5f)
            assertTrue(worstContrast(text.artist, backdrops(cs)) >= 4.5f)
        }
    }

    @Test
    fun theTintIsKeptWhenANudgeIsEnough() {
        // A mid grey on near-black needs only a little lift: the result is not jumped to pure white.
        val dark = listOf(Color(0xFF101010))
        val fixed = readableOn(Color(0xFF6A6A6A), dark)
        assertTrue(worstContrast(fixed, dark) >= 4.5f)
        assertTrue(fixed != Color.White)
    }
}
