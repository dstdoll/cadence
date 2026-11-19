package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic, host-side tests for the cover -> theme-seed colour selection. */
class ArtworkPaletteTest {

    @Test fun normalize_accepts_common_forms() {
        assertEquals("#ffffff", ArtworkPalette.normalize("#FFFFFF"))
        assertEquals("#ffffff", ArtworkPalette.normalize("ffffff"))
        assertEquals("#ffffff", ArtworkPalette.normalize("#fff"))
        assertEquals("#aabbcc", ArtworkPalette.normalize("aabbccdd")) // alpha dropped
        assertEquals("#e02200", ArtworkPalette.normalize("  E02200 "))
    }

    @Test fun normalize_rejects_non_colours() {
        assertEquals(null, ArtworkPalette.normalize(null))
        assertEquals(null, ArtworkPalette.normalize(""))
        assertEquals(null, ArtworkPalette.normalize("12345"))   // wrong length
        assertEquals(null, ArtworkPalette.normalize("zzzzzz"))  // non-hex
    }

    @Test fun vibrancy_ranks_vivid_over_neutral() {
        val red = ArtworkPalette.vibrancy("e02200")
        val cyan = ArtworkPalette.vibrancy("2ec8ff")
        // Greys/black/white carry no hue, so they must score zero.
        assertEquals(0.0, ArtworkPalette.vibrancy("ffffff"), 1e-9)
        assertEquals(0.0, ArtworkPalette.vibrancy("000000"), 1e-9)
        assertEquals(0.0, ArtworkPalette.vibrancy("808080"), 1e-9)
        assertTrue("vivid colours must beat neutrals", red > 0.2 && cyan > 0.2)
    }

    @Test fun vibrancy_penalises_very_dark_and_very_light() {
        // Same hue, different lightness: a mid tone should beat a near-black and a near-white tint.
        val mid = ArtworkPalette.vibrancy("2e9bd6")
        val nearBlack = ArtworkPalette.vibrancy("071722")
        val nearWhite = ArtworkPalette.vibrancy("dcecf6")
        assertTrue(mid > nearBlack)
        assertTrue(mid > nearWhite)
    }

    @Test fun themeColors_seeds_with_the_vivid_cover_colour() {
        // Realistic Apple palette: vivid red background, white + near-black legibility text colours.
        val seed = ArtworkPalette.themeColors(
            listOf("e02200", "ffffff", "1a0a05", null, "")
        ).first()
        assertEquals("#e02200", seed)
    }

    @Test fun themeColors_seeds_from_text_colour_when_background_is_neutral() {
        // Muted grey background but a vivid accent among the text colours -> accent wins the seed.
        val seed = ArtworkPalette.themeColors(
            listOf("303030", "2ec8ff", "f2f2f2")
        ).first()
        assertEquals("#2ec8ff", seed)
    }

    @Test fun themeColors_dedupes_and_caps_at_three() {
        val out = ArtworkPalette.themeColors(
            listOf("e02200", "#E02200", "2ec8ff", "22bb44", "ffcc00")
        )
        assertEquals(out.distinct(), out)
        assertTrue(out.size <= 3)
        // Gold at a perfect mid-lightness and full saturation outscores the darker red here.
        assertEquals("#ffcc00", out.first())
    }

    @Test fun themeColors_falls_back_to_default_when_no_usable_colour() {
        assertEquals(ArtworkPalette.DEFAULT, ArtworkPalette.themeColors(listOf(null, "", "xyz")))
        assertEquals(ArtworkPalette.DEFAULT, ArtworkPalette.themeColors(emptyList()))
    }
}
