package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real masters of "Breathe (In the Air)" from The Dark Side of the Moon (50th Anniversary) [Remastered], read from
 * Apple on 2026-11-20 (ids and links neutralised). Apple publishes TWO master files for this song: `_default` (13
 * renditions, with Atmos and the binaural/downmix mixes) and `_lossless` (5 renditions, no Atmos). Its lossless
 * renditions are 24-bit/48 kHz and 24-bit/192 kHz: there is no 96 kHz rendition, and no 16-bit one.
 */
class RealHiResMasterTest {
    private val url = "https://cdn.example.com/assets/P1/master.m3u8"
    private fun load(name: String) = javaClass.getResource("/hls/$name")!!.readText()
    private val default = HlsVariants.parse(load("master-hires-default.m3u8"), url)
    private val lossless = HlsVariants.parse(load("master-hires-lossless.m3u8"), url)
    private fun item(id: String, ms: Long = 168_000) = DownloadItem(id, "Breathe", "Pink Floyd", "DSOTM", "https://cdn/$id.m3u8", durationMs = ms)

    @Test fun theDefaultMasterHas13RenditionsAndTheLosslessOneHas5() {
        assertEquals(13, default.size)
        assertEquals(5, lossless.size)
    }

    @Test fun bothMastersOfferTheSameLosslessRenditionsAndThe192kHzOneIsNotMissed() {
        for (v in listOf(default, lossless)) {
            val r = QualityPlanner.renditions(v)
            assertEquals(listOf("alac:24:192000", "alac:24:48000", "aac:256", "aac:128", "aac:64"), r.map { it.spec.format() })
        }
        assertEquals(listOf(768, 448), QualityPlanner.atmosRenditions(default).map { it.kbps })
        assertTrue("the lossless master has no Atmos", QualityPlanner.atmosRenditions(lossless).isEmpty())
        assertEquals("the AAC-LC binaural renders (256 and 128); the HE-AAC one is not offered", 2, QualityPlanner.spatialRenditions(default).size)
    }

    @Test fun thePickerListsExactlyWhatAppleOffersForTheSong() {
        val result = runBlocking { QualityProbe.probe(listOf(item("a")), QualityPolicy()) { load("master-hires-default.m3u8") } }
        val labels = result.options.map { it.spec?.format() ?: it.kind }
        assertEquals(listOf("alac:24:192000", "alac:24:48000", "aac:256", "aac:128", "aac:64", "atmos", "spatial"), labels)
        assertEquals("the default (highest available) preselects the 192 kHz rendition", "alac:24:192000", labels[result.preselected])
        // 168 s at 5.32 Mbps is about 112 MB: the hi-res row is the big one
        assertEquals(5_322_331L * 168_000 / 8000, result.options[0].bytes)
    }

    @Test fun aNinetySixKilohertzCeilingTakesTheBestBelowIt() {
        // There is no 96 kHz rendition: a 96 kHz cap gives 24-bit/48 kHz, not the 192 kHz one and not AAC.
        val c = QualityPlanner.choose(default, QualityPolicy(RenditionSpec.parse("alac:24:96000")!!))
        assertEquals("audio-alac-stereo-48000-24", c.variant.groupId)
        assertTrue(c.reason, c.reason.contains("best within the limit"))
        assertEquals("audio-alac-stereo-192000-24", QualityPlanner.choose(default, QualityPolicy()).variant.groupId)
        // a 16-bit CD ceiling has nothing at or below it here: the closest lossless above it is 24-bit/48 kHz
        assertEquals("audio-alac-stereo-48000-24", QualityPlanner.choose(default, QualityPolicy(RenditionSpec.parse("alac:16:44100")!!)).variant.groupId)
    }

    @Test fun aSeveralSongRequestCountsTheFallbacksHonestly() {
        // three songs: Breathe twice (192 kHz) and a CD-only song
        val cd = MasterBuilder().lossless(16, 44100).aac(256).aac(128).he().text()
        val masters = mapOf("a" to load("master-hires-default.m3u8"), "b" to load("master-hires-lossless.m3u8"), "c" to cd)
        val r = runBlocking { QualityProbe.probe(listOf(item("a"), item("b"), item("c")), QualityPolicy()) { u -> masters[u.substringAfterLast('/').removeSuffix(".m3u8")] } }
        val hi = r.options.first { it.spec?.format() == "alac:24:192000" }
        assertEquals(listOf(2, 3, 1), listOf(hi.tracksWith, hi.tracks, hi.fallbackTracks))
    }

    @Test fun theLogLineSaysEverythingTheMasterOffers() {
        val line = QualityProbe.describeMaster("1665303759", "https://aod.itunes.apple.com/x/P1_default.m3u8?sig=1", default)
        assertEquals(
            "master track=1665303759 file=P1_default.m3u8 renditions=13 stereo=[alac 24-bit/192 kHz, alac 24-bit/48 kHz, AAC 256 kbps, AAC 128 kbps, HE-AAC 64 kbps] atmos=768/448 binaural/downmix=6",
            line,
        )
        assertTrue("no query string (it can hold a signature)", !line.contains("sig="))
        assertEquals("master track=1 file=m.m3u8 renditions=5 stereo=[alac 24-bit/192 kHz, alac 24-bit/48 kHz, AAC 256 kbps, AAC 128 kbps, HE-AAC 64 kbps] atmos=none binaural/downmix=0",
            QualityProbe.describeMaster("1", "https://x/m.m3u8", lossless))
    }
}
