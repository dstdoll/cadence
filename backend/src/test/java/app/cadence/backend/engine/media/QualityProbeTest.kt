package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QualityProbeTest {
    private val real = javaClass.getResource("/hls/master-atmos.m3u8")!!.readText()
    private fun item(id: String, ms: Long = 200_000, url: String = "https://cdn/$id.m3u8") = DownloadItem(id, id, "a", "al", url, durationMs = ms)
    private fun probe(items: List<DownloadItem>, masters: Map<String, String?>, defaults: QualityPolicy = QualityPolicy()) =
        runBlocking { QualityProbe.probe(items, defaults) { url -> masters[url.substringAfterLast('/').removeSuffix(".m3u8")] } }
    private fun labels(r: QualityProbeResult) = r.options.map { it.spec?.format() ?: it.kind }

    private val hiRes192 = MasterBuilder().lossless(16, 44100).lossless(24, 44100).lossless(24, 96000).lossless(24, 192000).aac(256).aac(128).he().binaural(256).atmos(768).atmos(448).text()
    private val hiRes96 = MasterBuilder().lossless(24, 44100).lossless(24, 96000).aac(256).aac(128).he().text()
    private val cd = MasterBuilder().lossless(16, 44100).aac(256).aac(128).he().text()

    @Test fun oneSongListsExactlyWhatItsMasterOffersBestFirstWithAtmosAndSpatial() {
        val r = probe(listOf(item("a")), mapOf("a" to real))
        assertEquals(listOf("alac:24:44100", "aac:256", "aac:128", "aac:64", "atmos", "spatial"), labels(r))
        assertFalse("a single song has no 'highest available' row", r.options.any { it.highestAvailable })
        assertEquals(0, r.unchecked)
        // size = mean bit rate x duration (200 s): the lossless rendition's AVERAGE-BANDWIDTH is 1447060
        assertEquals(1_447_060L * 200_000 / 8000, r.options[0].bytes)
        assertEquals(listOf(1, 1, 1, 1, 1, 1), r.options.map { it.tracksWith })
        assertEquals(listOf(0, 0, 0, 0, 0, 0), r.options.map { it.fallbackTracks })
    }

    @Test fun theDefaultDecidesWhichRowIsPreselected() {
        fun pre(d: QualityPolicy) = probe(listOf(item("a")), mapOf("a" to real), d).let { labels(it)[it.preselected] }
        assertEquals("alac:24:44100", pre(QualityPolicy()))
        assertEquals("aac:128", pre(QualityPolicy(RenditionSpec.parse("aac:128")!!)))
        assertEquals("aac:64", pre(QualityPolicy(RenditionSpec.parse("aac:64")!!)))
        assertEquals("atmos", pre(QualityPolicy(atmos = "atmos")))
        assertEquals("spatial", pre(QualityPolicy(atmos = "spatial")))
    }

    @Test fun severalSongsGetAHighestAvailableRowAndHonestCoverageCounts() {
        val r = probe(listOf(item("a"), item("b"), item("c")), mapOf("a" to hiRes192, "b" to hiRes96, "c" to cd))
        assertTrue(r.options[0].highestAvailable)
        assertEquals(3, r.options[0].tracksWith)
        val l = labels(r)
        assertEquals(listOf("alac:24:0", "alac:24:192000", "alac:24:96000", "alac:24:44100", "alac:16:44100", "aac:256", "aac:128", "aac:64", "atmos", "spatial"), l)
        val at = { s: String -> r.options[l.indexOf(s)] }
        // 24/192: only song a has it; b and c fall back to their best (24/96 and 16/44.1)
        assertEquals(listOf(1, 3, 2), listOf(at("alac:24:192000").tracksWith, at("alac:24:192000").tracks, at("alac:24:192000").fallbackTracks))
        // 24/96: a and b have it, c falls back
        assertEquals(listOf(2, 1), listOf(at("alac:24:96000").tracksWith, at("alac:24:96000").fallbackTracks))
        assertEquals(listOf(3, 0), listOf(at("aac:128").tracksWith, at("aac:128").fallbackTracks))
        assertEquals("only song a has Atmos", listOf(1, 2), listOf(at("atmos").tracksWith, at("atmos").fallbackTracks))
        // the estimated size of a row is what each song would really download
        val expected = listOf(hiRes192, hiRes96, cd).sumOf { m ->
            val v = HlsVariants.parse(m, "https://cdn/x.m3u8")
            QualityPlanner.choose(v, QualityPolicy(RenditionSpec.parse("alac:24:96000")!!)).rendition!!.estimatedBytes(200_000)
        }
        assertEquals(expected, at("alac:24:96000").bytes)
    }

    @Test fun anUnreadableMasterIsCountedAndNeverBlocksTheRest() {
        val r = probe(listOf(item("a"), item("b"), item("c", url = ""), item("d")), mapOf("a" to hiRes96, "b" to null, "d" to "not a playlist"))
        assertEquals(3, r.unchecked)
        assertEquals(4, r.tracks)
        assertEquals(listOf("alac:24:44100", "alac:24:96000").sorted(), labels(r).filter { it.startsWith("alac") }.sorted())
        val none = probe(listOf(item("a"), item("b")), mapOf("a" to null, "b" to null))
        assertTrue(none.options.isEmpty()); assertEquals(2, none.unchecked)
    }

    @Test fun aStereoRowTurnsAtmosOffForThatDownloadAndAtmosRowsAskForIt() {
        val r = probe(listOf(item("a")), mapOf("a" to hiRes192))
        for (o in r.options.filter { it.kind == "lossless" || it.kind == "aac" }) {
            assertEquals(HlsVariants.ATMOS_OFF, o.policy().atmos)
            assertEquals(o.spec, o.policy().ceiling)
        }
        assertEquals(HlsVariants.ATMOS_DOLBY, r.options.first { it.kind == "atmos" }.policy().atmos)
        assertEquals(HlsVariants.ATMOS_SPATIAL, r.options.first { it.kind == "spatial" }.policy().atmos)
    }

    @Test fun masterFetchesRunAtMostFourAtATime() {
        val now = AtomicInteger(); val peak = AtomicInteger()
        runBlocking {
            QualityProbe.probe((1..20).map { item("t$it") }, QualityPolicy()) {
                val n = now.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
                delay(20); now.decrementAndGet(); cd
            }
        }
        assertTrue("peak concurrency ${peak.get()}", peak.get() in 2..4)
    }

    @Test fun withoutADurationTheSizeIsUnknownNotWrong() {
        val r = probe(listOf(item("a", ms = 0)), mapOf("a" to cd))
        assertTrue(r.options.all { it.bytes == 0L })
        assertNull(r.options.firstOrNull { it.bytes != 0L })
    }

    // ---- which row is preselected ------------------------------------------------------------------------------------

    private val mixed = listOf(item("a"), item("b"), item("c"), item("d"))
    private val mixedMasters = mapOf("a" to hiRes192, "b" to hiRes96, "c" to cd, "d" to cd)
    private fun pre(d: QualityPolicy, items: List<DownloadItem> = mixed) = probe(items, mixedMasters, d).let { labels(it)[it.preselected] + if (it.options[it.preselected].highestAvailable) "*" else "" }

    @Test fun theDefaultHighestAvailablePreselectsTheHighestAvailableRowNotTheMajorityRendition() {
        // most of these songs only reach 16-bit/44.1, but the default is "highest available": each song's best, not the majority's
        assertEquals("alac:24:0*", pre(QualityPolicy()))
    }

    @Test fun anExactDefaultPreselectsItsOwnRowAndAnAtmosDefaultTheAtmosRow() {
        assertEquals("alac:24:96000", pre(QualityPolicy(RenditionSpec.parse("alac:24:96000")!!)))
        assertEquals("aac:128", pre(QualityPolicy(RenditionSpec.parse("aac:128")!!)))
        assertEquals("atmos", pre(QualityPolicy(atmos = "atmos")))
        assertEquals("spatial", pre(QualityPolicy(atmos = "spatial")))
    }

    @Test fun aDefaultNoRowNamesPreselectsWhatMostSongsWouldGet() {
        // 32 kHz limit: no row of that quality exists; the songs fall back to what they have
        val r = probe(listOf(item("c"), item("d")), mixedMasters, QualityPolicy(RenditionSpec.parse("alac:24:44100")!!))
        assertEquals("alac:16:44100", labels(r)[r.preselected])
    }

    @Test fun oneSongPreselectsWhatTheDefaultPicksForIt() {
        assertEquals("alac:24:192000", pre(QualityPolicy(), listOf(item("a"))))
        assertEquals("alac:24:96000", pre(QualityPolicy(RenditionSpec.parse("alac:24:96000")!!), listOf(item("b"))))
    }

    // ---- what "highest available" will do ---------------------------------------------------------------------------------

    @Test fun theHighestAvailableRowSaysWhichQualityItReachesAndOnHowManySongs() {
        val r = probe(listOf(item("a"), item("b"), item("c"), item("d")), mapOf("a" to hiRes192, "b" to hiRes96, "c" to cd, "d" to cd))
        val row = r.options.first { it.highestAvailable }
        assertEquals("alac:24:192000", row.topSpec!!.format())
        assertEquals(1, row.topTracks)
        assertTrue("only the highest row carries it", r.options.filter { !it.highestAvailable }.all { it.topSpec == null && it.topTracks == 0 })
    }

    @Test fun whenSeveralSongsShareTheTopQualityTheyAreAllCounted() {
        val r = probe(listOf(item("a"), item("b"), item("c")), mapOf("a" to hiRes96, "b" to hiRes96, "c" to hiRes192))
        val row = r.options.first { it.highestAvailable }
        assertEquals(listOf("alac:24:192000", 1), listOf(row.topSpec!!.format(), row.topTracks))
        val r2 = probe(listOf(item("a"), item("b"), item("c")), mapOf("a" to hiRes96, "b" to hiRes96, "c" to cd))
        val row2 = r2.options.first { it.highestAvailable }
        assertEquals(listOf("alac:24:96000", 2), listOf(row2.topSpec!!.format(), row2.topTracks))
    }
}
