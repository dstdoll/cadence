package app.cadence.state.settings

import app.cadence.backend.engine.media.RenditionSpec
import app.cadence.toDownloadPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQualitySettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun downloadsAskWhichQualityByDefaultAndOnlyAnExplicitDefaultSkipsTheQuestion() {
        assertTrue(UserSettings().asksDownloadQuality)
        assertFalse(UserSettings(downloadQualityMode = QUALITY_MODE_DEFAULT).asksDownloadQuality)
        assertTrue("a corrupt value must not skip the question", UserSettings(downloadQualityMode = "whatever").asksDownloadQuality)
    }

    @Test fun theDefaultQualityComesFromTheOlderSettingsUntilOneIsSaved() {
        assertEquals("alac:24:0", UserSettings().downloadPolicy().ceiling.format())
        assertEquals("aac:256", UserSettings(downloadCodec = "aac").downloadPolicy().ceiling.format())
        assertEquals("alac:16:96000", UserSettings(bitDepth = 16, maxSampleRate = 96000).downloadPolicy().ceiling.format())
        assertEquals("spatial", UserSettings(downloadAtmos = "spatial").downloadPolicy().atmos)
    }

    @Test fun aSavedQualityWinsAndAGarbageOneIsIgnored() {
        assertEquals("aac:128", UserSettings(downloadQuality = "aac:128", downloadCodec = "alac").downloadPolicy().ceiling.format())
        assertEquals("alac:24:96000", UserSettings(downloadQuality = "alac:24:96000").downloadPolicy().ceiling.format())
        assertEquals("falls back to the older settings", "alac:24:0", UserSettings(downloadQuality = "nonsense").downloadPolicy().ceiling.format())
        assertEquals("atmos is the separate setting", "atmos", UserSettings(downloadQuality = "aac:128", downloadAtmos = "atmos").downloadPolicy().atmos)
    }

    @Test fun aSettingsFileFromBeforeThePickerLoadsAndKeepsTheOldMeaning() {
        val old = json.decodeFromString<UserSettings>("""{"colorSource":"Artwork","bitDepth":16,"maxSampleRate":48000,"downloadCodec":"alac"}""")
        assertTrue(old.asksDownloadQuality)
        assertEquals(null, old.downloadQuality)
        assertEquals("alac:16:48000", old.downloadPolicy().ceiling.format())
        val newer = json.decodeFromString<UserSettings>(json.encodeToString(UserSettings.serializer(), UserSettings(downloadQualityMode = "default", downloadQuality = "aac:64")))
        assertEquals("default" to "aac:64", newer.downloadQualityMode to newer.downloadQuality)
    }

    @Test fun theEngineGetsTheSamePolicyAndTheCacheKeyPlaybackAlreadyUses() {
        assertEquals("alac/24/0", UserSettings().toDownloadPreferences().downloadVariant())
        assertEquals("aac/256", UserSettings(downloadCodec = "aac").toDownloadPreferences().downloadVariant())
        assertEquals("atmos+alac/24/0", UserSettings(downloadAtmos = "atmos").toDownloadPreferences().downloadVariant())
        assertEquals("aac/128", UserSettings(downloadQuality = "aac:128").toDownloadPreferences().downloadVariant())
        assertEquals(RenditionSpec.parse("aac:64"), UserSettings(downloadQuality = "aac:64").toDownloadPreferences().policy().ceiling)
    }
}
