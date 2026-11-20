package app.cadence.backend.engine.media

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class QualityIndexTest {
    private val dir = Files.createTempDirectory("qindex").toFile()
    private val file = File(dir, "quality_index.json")
    private var clock = 1_000_000L
    private fun store(ttl: Long = 1_000, limit: Int = 100) = QualityIndexStore(file, { clock }, ttl, limit)
    @After fun detach() { QualityIndex.attachForTest(null); dir.deleteRecursively() }

    private val hiRes96 = MasterBuilder().lossless(24, 44100).lossless(24, 96000).aac(256).aac(128).he().text()
    private val hiRes192 = MasterBuilder().lossless(24, 48000).lossless(24, 192000).aac(256).aac(128).he().text()
    private val cd = MasterBuilder().lossless(16, 44100).aac(256).aac(128).he().text()
    private val noLossless = MasterBuilder().aac(256).aac(128).he().text()
    private fun songs(vararg ids: String) = ids.map { it to "https://cdn/$it.m3u8" }

    // ---- the store ---------------------------------------------------------------------------------------------------

    @Test fun entriesSurviveARestartAndExpire() {
        store().put(mapOf("1" to "alac:24:96000", "2" to ""))
        assertEquals(mapOf("1" to "alac:24:96000", "2" to ""), store().get(listOf("1", "2", "3")))
        clock += 5_000
        assertTrue("a stale entry is not returned", store().get(listOf("1")).isEmpty())
    }

    @Test fun theOldestEntriesGoPastTheLimitAndACorruptFileIsEmpty() {
        val s = store(ttl = 10_000_000, limit = 3)
        for (i in 1..5) { clock += 10; s.put(mapOf("t$i" to "alac:16:44100")) }
        assertEquals(setOf("t3", "t4", "t5"), store(10_000_000, 3).get((1..5).map { "t$it" }).keys)
        file.writeText("{ not json"); assertTrue(store().get(listOf("t3")).isEmpty())
    }

    // ---- the album summary -------------------------------------------------------------------------------------------

    @Test fun anAlbumIsHiResOnlyWhenEverySongIsCheckedAndHasAHiResRendition() {
        val masters = mapOf("a" to hiRes96, "b" to hiRes192, "c" to hiRes96)
        val q = runBlocking { QualityIndex.albumQuality(songs("a", "b", "c")) { masters[it.substringAfterLast('/').removeSuffix(".m3u8")] } }
        assertEquals(listOf(3, 3, 3, 96_000, 192_000), listOf(q.total, q.known, q.hiRes, q.minRate, q.maxRate))
        assertTrue(q.allHiRes)
    }

    @Test fun oneCdSongOrOneUnreadableMasterMeansNotAllHiRes() {
        val withCd = runBlocking { QualityIndex.albumQuality(songs("a", "c")) { if (it.contains("/a.")) hiRes96 else cd } }
        assertEquals(listOf(2, 2, 1), listOf(withCd.total, withCd.known, withCd.hiRes)); assertFalse(withCd.allHiRes); assertTrue(withCd.anyHiRes)
        val partly = runBlocking { QualityIndex.albumQuality(songs("a", "b")) { if (it.contains("/a.")) hiRes96 else null } }
        assertEquals(listOf(2, 1, 1), listOf(partly.total, partly.known, partly.hiRes))
        assertFalse("a song nobody could check must not let the album claim hi-res", partly.allHiRes)
        assertFalse(runBlocking { QualityIndex.albumQuality(songs("n")) { noLossless } }.anyHiRes)
        assertFalse(runBlocking { QualityIndex.albumQuality(emptyList()) { null } }.allHiRes)
    }

    @Test fun cachedSongsAreNotFetchedAgainAndFetchedOnesAreRemembered() {
        QualityIndex.attachForTest(store(ttl = 10_000_000))
        val fetches = AtomicInteger()
        val fetch: suspend (String) -> String? = { fetches.incrementAndGet(); hiRes96 }
        runBlocking { QualityIndex.albumQuality(songs("a", "b", "c"), fetch = fetch) }
        assertEquals(3, fetches.get())
        val again = runBlocking { QualityIndex.albumQuality(songs("a", "b", "c"), fetch = fetch) }
        assertEquals("the second look costs nothing", 3, fetches.get())
        assertTrue(again.allHiRes)
        runBlocking { QualityIndex.albumQuality(songs("a", "b", "c", "d"), fetch = fetch) }
        assertEquals("only the new song is fetched", 4, fetches.get())
    }

    @Test fun aSongWithNoLinkIsUnknownNotFetched() {
        val q = runBlocking { QualityIndex.albumQuality(listOf("a" to "", "b" to "https://cdn/b.m3u8")) { hiRes96 } }
        assertEquals(listOf(2, 1), listOf(q.total, q.known)); assertFalse(q.allHiRes)
    }

    @Test fun theBestLosslessOfTheRealBreatheMastersIs192kHz() {
        val real = HlsVariants.parse(javaClass.getResource("/hls/master-hires-default.m3u8")!!.readText(), "https://cdn/x.m3u8")
        assertEquals("alac:24:192000", QualityIndex.bestLossless(real))
        assertEquals("", QualityIndex.bestLossless(HlsVariants.parse(noLossless, "https://cdn/x.m3u8")))
        val q = QualityIndex.summarize(1, mapOf("a" to "alac:24:192000"))
        assertTrue(q.allHiRes); assertEquals(192_000, q.minRate)
    }

    @Test fun onAMeteredConnectionOnlyCachedSongsAreUsedAndNothingIsFetched() {
        QualityIndex.attachForTest(store(ttl = 10_000_000))
        runBlocking { QualityIndex.albumQuality(songs("a"), fetch = { hiRes96 }) }          // "a" becomes known
        val fetches = AtomicInteger()
        val q = runBlocking { QualityIndex.albumQuality(songs("a", "b"), allowFetch = false, fetch = { fetches.incrementAndGet(); hiRes96 }) }
        assertEquals(0, fetches.get())
        assertEquals(listOf(2, 1, 1), listOf(q.total, q.known, q.hiRes))
        assertFalse("an unknown song never lets the album claim hi-res", q.allHiRes)
    }
}
