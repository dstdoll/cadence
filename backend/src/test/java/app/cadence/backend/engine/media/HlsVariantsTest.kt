package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadPreferences
import app.cadence.backend.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Rendition choice on a real enhancedHls master of a song mixed in Dolby Atmos (URIs and ids neutralised). */
class HlsVariantsTest {
    private val masterUrl = "https://cdn.example.com/assets/P1/master.m3u8"
    private val master = javaClass.getResource("/hls/master-atmos.m3u8")!!.readText()
    private val all = HlsVariants.parse(master, masterUrl)

    /** The same master without the Atmos-derived renditions, like a song that has no Atmos mix. */
    private val stereoOnly = all.filter { !it.atmos && !it.binaural && !it.downmix }

    private fun choose(codec: String, atmos: String, variants: List<HlsVariant> = all) =
        HlsVariants.choose(variants, codec, atmos, preferredBitDepth = 24, maxSampleRate = 0)

    @Test
    fun parsesEveryRenditionWithItsGroup() {
        assertEquals(12, all.size)
        val atmos = all.first { it.groupId == "audio-atmos-2768" }
        assertEquals("ec-3", atmos.codecs)
        assertEquals(770_601L, atmos.bandwidth)
        assertEquals("16/JOC", atmos.group["CHANNELS"])
        assertEquals("https://cdn.example.com/assets/P1/audio-atmos-2768.m3u8", atmos.url)
        assertEquals(2, all.count { it.atmos })
        assertEquals(3, all.count { it.binaural })
        assertEquals(3, all.count { it.downmix })
    }

    @Test
    fun aacTakesTheStereoMixNotTheAtmosDownmix() {
        // The downmix has the highest AAC bandwidth on this track; it is a different mix.
        assertTrue(all.first { it.groupId == "audio-stereo-256-downmix" }.bandwidth > all.first { it.groupId == "audio-stereo-256" }.bandwidth)
        val pick = choose("aac", HlsVariants.ATMOS_OFF)
        assertEquals("audio-stereo-256", pick.variant.groupId)
        assertEquals("aac", pick.codec)
    }

    @Test
    fun losslessIsUnchanged() {
        val pick = choose("alac", HlsVariants.ATMOS_OFF)
        assertEquals("audio-alac-stereo-44100-24", pick.variant.groupId)
        assertEquals("alac", pick.codec)
    }

    @Test
    fun dolbyAtmosTakesTheHighestBitrateEac3Joc() {
        for (codec in listOf("alac", "aac")) {
            val pick = choose(codec, HlsVariants.ATMOS_DOLBY)
            assertEquals("audio-atmos-2768", pick.variant.groupId)
            assertEquals("ec-3", pick.codec)
            assertEquals("atmos", pick.kind)
        }
    }

    @Test
    fun spatialTakesTheBinauralAacRender() {
        val pick = choose("alac", HlsVariants.ATMOS_SPATIAL)
        assertEquals("audio-stereo-256-binaural", pick.variant.groupId)
        assertEquals("aac", pick.codec)
        assertEquals("spatial", pick.kind)
    }

    @Test
    fun songsWithoutAtmosKeepTheChosenQuality() {
        assertEquals("audio-alac-stereo-44100-24", choose("alac", HlsVariants.ATMOS_DOLBY, stereoOnly).variant.groupId)
        assertEquals("audio-stereo-256", choose("aac", HlsVariants.ATMOS_DOLBY, stereoOnly).variant.groupId)
        assertEquals("audio-alac-stereo-44100-24", choose("alac", HlsVariants.ATMOS_SPATIAL, stereoOnly).variant.groupId)
        assertEquals("audio-stereo-256", choose("aac", HlsVariants.ATMOS_SPATIAL, stereoOnly).variant.groupId)
    }

    @Test
    fun aacFallsBackToLosslessAndLosslessNeedsAlac() {
        val noAac = all.filter { !it.aacLc }
        assertEquals("alac", choose("aac", HlsVariants.ATMOS_OFF, noAac).codec)
        assertThrows(IOException::class.java) { choose("alac", HlsVariants.ATMOS_OFF, all.filter { !it.alac }) }
    }

    @Test
    fun atmosTracksGetTheirOwnCacheKeyAndOffKeepsTheOldOnes() {
        assertEquals("alac/24/0", DownloadPreferences().streamVariant())
        assertEquals("aac/256", DownloadPreferences(streamCodec = "aac").streamVariant())
        assertEquals("atmos+alac/24/0", DownloadPreferences(streamAtmos = "atmos").streamVariant())
        assertEquals("spatial+aac/256", DownloadPreferences(streamCodec = "aac", streamAtmos = "spatial").streamVariant())
    }

    @Test
    fun downloadsHaveTheirOwnAtmosChoiceAndShareTheCacheOnlyWhenBothMatch() {
        assertEquals("alac/24/0", DownloadPreferences().downloadVariant())
        assertEquals("atmos+alac/24/0", DownloadPreferences(atmos = "atmos").downloadVariant())
        assertEquals("spatial+aac/256", DownloadPreferences(codec = "aac", atmos = "spatial").downloadVariant())
        // The download reuses playback's cache only when both keys are equal.
        val both = DownloadPreferences(atmos = "atmos", streamAtmos = "atmos")
        assertEquals(both.streamVariant(), both.downloadVariant())
        val onlyDownload = DownloadPreferences(atmos = "atmos")
        assertNotEquals(onlyDownload.streamVariant(), onlyDownload.downloadVariant())
    }

    @Test
    fun containerErrorsDoNotRenewTheKey() {
        assertEquals(false, app.cadence.backend.engine.stream.keyCanFix("parse init: UnsupportedCodec([101, 99, 45, 51])"))
        assertEquals(false, app.cadence.backend.engine.stream.keyCanFix("parse fragment: SampleOutOfRange { sample: 3 }"))
        assertEquals(true, app.cadence.backend.engine.stream.keyCanFix("decrypt: sample 0 failed"))
        assertEquals(true, app.cadence.backend.engine.stream.keyCanFix(null))
    }

    @Test
    fun theBadgeNamesDolbyAtmos() {
        assertEquals("Dolby Atmos", AudioQuality.codecForMime("audio/eac3-joc"))
        assertEquals("Dolby Digital Plus", AudioQuality.codecForMime("audio/eac3"))
    }
}
