package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QualityPlannerTest {
    private val url = "https://cdn.example.com/assets/P1/master.m3u8"
    private val real = HlsVariants.parse(javaClass.getResource("/hls/master-atmos.m3u8")!!.readText(), url)

    private val hiRes = MasterBuilder().lossless(16, 44100).lossless(24, 44100).lossless(24, 48000).lossless(24, 96000).lossless(24, 192000)
        .aac(256).aac(128).he().binaural(256).atmos(768).atmos(448).parse()

    private fun pick(v: List<HlsVariant>, spec: String, atmos: String = "off") =
        QualityPlanner.choose(v, QualityPolicy(RenditionSpec.parse(spec)!!, atmos)).variant.groupId

    // ---- the real master ---------------------------------------------------------------------------------------

    @Test fun theRealMasterOffersFourStereoRenditionsBestFirstAndHidesTheOtherMixes() {
        val r = QualityPlanner.renditions(real)
        assertEquals(listOf("audio-alac-stereo-44100-24", "audio-stereo-256", "audio-stereo-128", "audio-HE-stereo-64"), r.map { it.variant.groupId })
        assertEquals(listOf(QualityFamily.LOSSLESS, QualityFamily.AAC_LC, QualityFamily.AAC_LC, QualityFamily.HE_AAC), r.map { it.family })
        assertEquals(listOf(0, 256, 128, 64), r.map { it.kbps })
        assertTrue("binaural/downmix/atmos are not listed", r.none { it.variant.binaural || it.variant.downmix || it.variant.atmos })
        assertEquals(listOf(768, 448), QualityPlanner.atmosRenditions(real).map { it.kbps })
        assertEquals("only the AAC-LC binaural renders (the HE-AAC one is not offered)", 2, QualityPlanner.spatialRenditions(real).size)
    }

    @Test fun everyCeilingOnTheRealMaster() {
        assertEquals("audio-alac-stereo-44100-24", pick(real, "alac:24:0"))
        assertEquals("audio-alac-stereo-44100-24", pick(real, "alac:24:96000"))
        assertEquals("audio-alac-stereo-44100-24", pick(real, "alac:24:44100"))
        assertEquals("a lossless ceiling below everything stays lossless (closest above)", "audio-alac-stereo-44100-24", pick(real, "alac:16:44100"))
        assertEquals("audio-stereo-256", pick(real, "aac:256"))
        assertEquals("audio-stereo-128", pick(real, "aac:128"))
        assertEquals("audio-HE-stereo-64", pick(real, "aac:64"))
    }

    @Test fun sizesComeFromTheMeanBitRate() {
        val r = QualityPlanner.renditions(real)
        // AVERAGE-BANDWIDTH=1447060 for the lossless rendition: a 200 s song is about 36 MB
        assertEquals(1_447_060L * 200_000 / 8000, r[0].estimatedBytes(200_000))
        assertEquals(0L, r[0].estimatedBytes(0))
        assertEquals(768, QualityPlanner.nominalKbps(real.first { it.groupId == "audio-atmos-2768" }))
        assertEquals(448, QualityPlanner.nominalKbps(real.first { it.groupId == "audio-atmos-2448" }))
    }

    @Test fun theNominalBitRateComesFromTheMeanRateWhenTheGroupNameDoesNotSayIt() {
        fun v(id: String, avg: Long, codec: String = "mp4a.40.2") = HlsVariant("https://x/$id.m3u8", codec, avg + 5_000, id, mapOf("CHANNELS" to "2"), averageBandwidth = avg)
        val renamed = listOf(v("opaque-a", 261_839), v("opaque-b", 133_280), v("opaque-c", 73_922, "mp4a.40.5"))
        assertEquals(listOf(256, 128, 64), QualityPlanner.renditions(renamed).map { it.kbps })
        assertEquals("audio-stereo-128" to 128, QualityPlanner.renditions(MasterBuilder().aac(128).parse()).single().let { it.variant.groupId to it.kbps })
        assertEquals("opaque-b", QualityPlanner.choose(renamed, QualityPolicy(RenditionSpec.parse("aac:128")!!)).variant.groupId)
    }

    // ---- a hi-res ladder ---------------------------------------------------------------------------------------

    @Test fun theHiResLadderPicksTheBestWithinTheLimitAndFallsBackDownward() {
        assertEquals("audio-alac-stereo-192000-24", pick(hiRes, "alac:24:0"))
        assertEquals("audio-alac-stereo-192000-24", pick(hiRes, "alac:24:192000"))
        assertEquals("audio-alac-stereo-96000-24", pick(hiRes, "alac:24:96000"))
        assertEquals("audio-alac-stereo-48000-24", pick(hiRes, "alac:24:48000"))
        assertEquals("audio-alac-stereo-44100-24", pick(hiRes, "alac:24:44100"))
        assertEquals("audio-alac-stereo-44100-16", pick(hiRes, "alac:16:44100"))
        assertEquals("a rate limit between rungs takes the highest below it", "audio-alac-stereo-96000-24", pick(hiRes, "alac:24:100000"))
        assertEquals("audio-stereo-256", pick(hiRes, "aac:256"))
        assertEquals("audio-stereo-128", pick(hiRes, "aac:128"))
        assertEquals("audio-HE-stereo-64", pick(hiRes, "aac:64"))
    }

    @Test fun theReasonSaysWhatWasWantedWhatWasOfferedAndWhy() {
        val c = QualityPlanner.choose(real, QualityPolicy(RenditionSpec.parse("alac:24:192000")!!))
        assertTrue(c.reason, c.reason.startsWith("wanted alac:24:192000; this track offers [lossless 24-bit/44.1 kHz, AAC 256 kbps, AAC 128 kbps, HE-AAC 64 kbps] -> lossless 24-bit/44.1 kHz"))
        assertTrue(c.reason, c.reason.endsWith("(best within the limit)"))
        val up = QualityPlanner.choose(real, QualityPolicy(RenditionSpec.parse("alac:16:44100")!!))
        assertTrue(up.reason, up.reason.contains("closest lossless above it"))
    }

    // ---- fallbacks across families -----------------------------------------------------------------------------

    @Test fun aTrackWithoutAacGetsTheSmallestLosslessUnderAnAacCeiling() {
        val noAac = MasterBuilder().lossless(16, 44100).lossless(24, 96000).parse()
        assertEquals("audio-alac-stereo-44100-16", pick(noAac, "aac:128"))
        assertEquals("audio-alac-stereo-44100-16", pick(noAac, "aac:64"))
    }

    @Test fun aTrackWithOnlyAacNeverFailsUnderALosslessCeiling() {
        val aacOnly = MasterBuilder().aac(256).aac(128).he().parse()
        assertEquals("audio-stereo-256", pick(aacOnly, "alac:24:0"))
        assertEquals("audio-stereo-256", pick(aacOnly, "alac:16:44100"))
    }

    @Test fun anAacCeilingWithOnlyHigherAacTakesTheClosestAbove() {
        val only256 = MasterBuilder().lossless(24, 44100).aac(256).parse()
        assertEquals("audio-stereo-256", pick(only256, "aac:128"))
        assertEquals("audio-stereo-256", pick(only256, "aac:64"))
        val only128He = MasterBuilder().aac(128).he().parse()
        assertEquals("audio-stereo-128", pick(only128He, "aac:256"))
    }

    @Test fun anAacCeilingWithAHeAacOnlyBelowTakesIt() {
        val v = MasterBuilder().lossless(24, 44100).aac(256).he().parse()
        assertEquals("audio-HE-stereo-64", pick(v, "aac:128"))
    }

    @Test fun aMasterWithNothingUsableThrowsAClearError() {
        assertThrows(IOException::class.java) { QualityPlanner.choose(emptyList(), QualityPolicy()) }
        assertThrows(IOException::class.java) { QualityPlanner.choose(MasterBuilder().atmos(768).binaural(256).parse(), QualityPolicy()) }
    }

    // ---- Dolby Atmos -------------------------------------------------------------------------------------------

    @Test fun atmosIsASeparateAxisAndOnlyAppliesWhereTheTrackHasIt() {
        val dolby = QualityPlanner.choose(hiRes, QualityPolicy(RenditionSpec.parse("aac:128")!!, HlsVariants.ATMOS_DOLBY))
        assertEquals(listOf("audio-atmos-2768", "ec-3", "atmos"), listOf(dolby.variant.groupId, dolby.codec, dolby.kind))
        val spatial = QualityPlanner.choose(hiRes, QualityPolicy(RenditionSpec.BEST, HlsVariants.ATMOS_SPATIAL))
        assertEquals(listOf("audio-stereo-256-binaural", "aac", "spatial"), listOf(spatial.variant.groupId, spatial.codec, spatial.kind))
        val stereoOnly = MasterBuilder().lossless(24, 44100).aac(256).parse()
        for (atmos in listOf("atmos", "spatial")) assertEquals("audio-alac-stereo-44100-24", pick(stereoOnly, "alac:24:0", atmos))
    }

    // ---- the spec string and the cache key ---------------------------------------------------------------------

    @Test fun specsRoundTripAndGarbageIsRejectedNotThrown() {
        for (s in RenditionSpec.LADDER) assertEquals(s, RenditionSpec.parse(s.format()))
        assertEquals(RenditionSpec.BEST, RenditionSpec.parse("ALAC:24:0"))
        assertEquals(QualityFamily.AAC_LC, RenditionSpec.parse("aac:256")!!.family)
        assertEquals(QualityFamily.HE_AAC, RenditionSpec.parse("aac:64")!!.family)
        for (bad in listOf(null, "", "best", "flac:16:44100", "alac:24", "alac:x:y", "alac:99:0", "aac:", "aac:9999", "alac:24:-1")) assertNull("'$bad'", RenditionSpec.parse(bad))
    }

    @Test fun cacheKeysKeepTheFormatPlaybackAndOldDownloadsAlreadyUse() {
        assertEquals(DownloadPreferences().downloadVariant(), QualityPolicy().cacheKey())
        assertEquals(DownloadPreferences(codec = "aac").downloadVariant(), QualityPolicy(RenditionSpec.parse("aac:256")!!).cacheKey())
        assertEquals(DownloadPreferences(atmos = "atmos").downloadVariant(), QualityPolicy(atmos = "atmos").cacheKey())
        assertEquals(DownloadPreferences(codec = "aac", atmos = "spatial").downloadVariant(), QualityPolicy(RenditionSpec.parse("aac:256")!!, "spatial").cacheKey())
        assertEquals("alac/24/96000", QualityPolicy(RenditionSpec.parse("alac:24:96000")!!).cacheKey())
        assertEquals("aac/128", QualityPolicy(RenditionSpec.parse("aac:128")!!).cacheKey())
        assertTrue("different qualities never share a cache entry", RenditionSpec.LADDER.map { QualityPolicy(it).cacheKey() }.toSet().size == RenditionSpec.LADDER.size)
    }

    // ---- an upgrade changes nothing ----------------------------------------------------------------------------

    /** Realistic masters: every track has AAC, lossless starts at 44.1 kHz; Atmos tracks have the Atmos set. */
    private val realistic = mapOf(
        "real fixture" to real,
        "hi-res ladder" to hiRes,
        "cd lossless only" to MasterBuilder().lossless(16, 44100).aac(256).aac(128).he().parse(),
        "24/48" to MasterBuilder().lossless(24, 48000).lossless(24, 44100).aac(256).aac(128).he().parse(),
        "hi-res to 96 with atmos" to MasterBuilder().lossless(24, 44100).lossless(24, 96000).aac(256).aac(128).he().binaural(256).atmos(768).atmos(448).parse(),
    )

    @Test fun theNewPlannerPicksWhatTheOldSelectionPickedForEverySettingsCombinationExceptTheDocumentedOnes() {
        var compared = 0
        val different = ArrayList<String>()
        for ((name, v) in realistic) for (codec in listOf("alac", "aac")) for (depth in listOf(24, 16)) for (rate in listOf(0, 44100, 48000, 96000, 192000)) for (atmos in listOf("off", "spatial", "atmos")) {
            val old = HlsVariants.choose(v, codec, atmos, depth, rate)
            val new = QualityPlanner.choose(v, QualityPlanner.legacyPolicy(codec, depth, rate, atmos))
            compared++
            if (old.variant.groupId == new.variant.groupId && old.kind == new.kind) continue
            // The one documented difference: a lossless track with NO variant within the bit-depth limit. The old code took
            // the highest sample rate of what remained, the new one the closest (smallest) rendition above the limit.
            val lossless = QualityPlanner.renditions(v).filter { it.family == QualityFamily.LOSSLESS }
            val explained = codec == "alac" && old.kind == "alac" && new.kind == "alac" && lossless.isNotEmpty() && lossless.all { it.bitDepth > depth } &&
                new.rendition!!.family == QualityFamily.LOSSLESS && new.rendition.sampleRate == lossless.minOf { it.sampleRate }
            if (!explained) org.junit.Assert.fail("unexplained difference: $name codec=$codec depth=$depth rate=$rate atmos=$atmos old=${old.variant.groupId} new=${new.variant.groupId}")
            different += "$name depth=$depth rate=$rate"
        }
        assertEquals(5 * 2 * 2 * 5 * 3, compared)
        assertTrue("every difference is a 16-bit limit on a track without 16-bit lossless: $different", different.isNotEmpty() && different.all { "depth=16" in it })
    }

    @Test fun theOnlyDeliberateDifferenceIsATrackWithoutAacUnderAnAacCeiling() {
        val noAac = MasterBuilder().lossless(16, 44100).lossless(24, 96000).parse()
        val old = HlsVariants.choose(noAac, "aac", "off", 24, 0)
        val new = QualityPlanner.choose(noAac, QualityPlanner.legacyPolicy("aac", 24, 0, "off"))
        assertEquals("old: the best lossless", "audio-alac-stereo-96000-24", old.variant.groupId)
        assertEquals("new: the smallest lossless (a data-saver choice should not fetch a 96 kHz file)", "audio-alac-stereo-44100-16", new.variant.groupId)
    }

    // ---- rates that sit between the ladder's rungs (seen on real masters: 88.2 and 176.4 kHz) --------------------------

    @Test fun aRateLimitBetweenRungsTakesTheBestBelowIt() {
        val petSounds = MasterBuilder().lossless(24, 88200).lossless(24, 48000).aac(256).aac(128).he().parse()      // Pet Sounds: 88.2 + 48
        val thriller = MasterBuilder().lossless(24, 176400).lossless(24, 48000).aac(256).aac(128).he().parse()      // Thriller: 176.4 + 48
        assertEquals("a 96 kHz limit takes 88.2 kHz", "audio-alac-stereo-88200-24", pick(petSounds, "alac:24:96000"))
        assertEquals("a 192 kHz limit takes 176.4 kHz", "audio-alac-stereo-176400-24", pick(thriller, "alac:24:192000"))
        assertEquals("a 96 kHz limit on 176.4/48 takes 48 kHz", "audio-alac-stereo-48000-24", pick(thriller, "alac:24:96000"))
    }

    @Test fun aSavedExactRateCapExcludesHigherRatesOfOtherSongs() {
        // "Use as my default" on the 176.4 kHz row stores that cap: a song that tops out at 192 kHz then gets 48 kHz, not 192.
        // (Found in the survey audit: the default should not silently turn into a ceiling that hides better renditions.)
        val song192 = MasterBuilder().lossless(24, 192000).lossless(24, 48000).aac(256).aac(128).he().parse()
        assertEquals("audio-alac-stereo-48000-24", pick(song192, "alac:24:176400"))
        assertEquals("audio-alac-stereo-192000-24", pick(song192, "alac:24:0"))
    }
}
