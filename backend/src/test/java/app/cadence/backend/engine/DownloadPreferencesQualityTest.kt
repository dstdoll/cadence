package app.cadence.backend.engine

import app.cadence.backend.engine.media.QualityPolicy
import app.cadence.backend.engine.media.RenditionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DownloadPreferencesQualityTest {
    @Test fun withoutAPickedQualityTheOlderFieldsDecideEverythingAsBefore() {
        val p = DownloadPreferences()
        assertEquals(QualityPolicy(RenditionSpec.BEST), p.policy())
        assertEquals("alac/24/0", p.downloadVariant())
        assertEquals("spatial+aac/256", DownloadPreferences(codec = "aac", atmos = "spatial").downloadVariant())
        assertEquals("alac/16/96000", DownloadPreferences(bitDepth = 16, maxSampleRate = 96000).downloadVariant())
    }

    @Test fun aPickedQualityOverridesThemForThatDownloadOnly() {
        val base = DownloadPreferences(codec = "aac")
        val picked = QualityPolicy(RenditionSpec.parse("alac:24:96000")!!)
        val forThis = base.forQuality(picked)
        assertEquals(picked, forThis.policy())
        assertEquals("alac/24/96000", forThis.downloadVariant())
        assertEquals("the shared default is untouched", "aac/256", base.downloadVariant())
        assertSame("no choice keeps the very same preferences", base, base.forQuality(null))
    }

    @Test fun theQualityOfADownloadItemRidesOnTheItem() {
        val item = DownloadEngine.DownloadItem("1", "t", "a", "al", "https://x/m.m3u8", quality = QualityPolicy(RenditionSpec.parse("aac:128")!!))
        assertEquals("aac/128", DownloadPreferences().forQuality(item.quality).downloadVariant())
        assertEquals("alac/24/0", DownloadPreferences().forQuality(DownloadEngine.DownloadItem("1", "t", "a", "al", "").quality).downloadVariant())
    }
}
