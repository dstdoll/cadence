package app.cadence.core.theme

import app.cadence.state.settings.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCompatTest {
    @Test fun wallpaperColoursFallBackToAlbumArtBeforeAndroid12() {
        for (sdk in listOf(29, 30)) assertEquals(ColorSource.Artwork, ApiCompat.effectiveColorSource(ColorSource.Wallpaper, sdk))
        for (sdk in listOf(31, 34, 36)) assertEquals(ColorSource.Wallpaper, ApiCompat.effectiveColorSource(ColorSource.Wallpaper, sdk))
    }

    @Test fun otherSourcesAreNeverChanged() {
        for (sdk in listOf(29, 31, 36)) for (s in ColorSource.entries.filter { it != ColorSource.Wallpaper }) assertEquals(s, ApiCompat.effectiveColorSource(s, sdk))
    }

    @Test fun theSettingsScreenOffersWallpaperOnlyWhereItWorks() {
        assertFalse(ColorSource.Wallpaper in ApiCompat.availableColorSources(29))
        assertTrue(ColorSource.Wallpaper in ApiCompat.availableColorSources(31))
        assertEquals(ColorSource.entries.size - 1, ApiCompat.availableColorSources(29).size)
        assertTrue("whatever is effective must be offered", ColorSource.entries.all { ApiCompat.effectiveColorSource(it, 29) in ApiCompat.availableColorSources(29) })
    }

    @Test fun theVersionBoundariesAreExact() {
        assertFalse(ApiCompat.supportsRenderEffect(30)); assertTrue(ApiCompat.supportsRenderEffect(31))
        assertFalse(ApiCompat.needsNotificationPermission(32)); assertTrue(ApiCompat.needsNotificationPermission(33))
        assertFalse(ApiCompat.hasPublicContextDisplay(29)); assertTrue(ApiCompat.hasPublicContextDisplay(30))
    }
}
