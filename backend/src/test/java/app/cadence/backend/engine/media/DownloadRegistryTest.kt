package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRegistryTest {
    private val dir = Files.createTempDirectory("registry").toFile()
    private val file = File(dir, "downloads_registry.json")
    private val present = HashSet<String>()
    private fun store(limit: Int = 5000) = DownloadRegistryStore(file, exists = { it in present }, limit = limit)
    private fun rec(track: String, spec: String, uri: String, at: Long = 1L) = DownloadRecord(track, spec, uri, "name", at).also { present += uri }

    @Test fun whatWasDownloadedAndInWhichQualityIsRememberedAcrossRestarts() {
        store().record(rec("t1", "alac:24:96000", "content://m/1", 10))
        val reopened = store()                      // a new process reads the same file
        val e = reopened.existing("t1")
        assertEquals(listOf("alac:24:96000"), e.map { it.spec })
        assertEquals("content://m/1", e.single().uri)
        assertTrue(reopened.existing("nobody").isEmpty())
    }

    @Test fun aSongCanHaveOneFilePerQualityNewestFirst() {
        val s = store()
        s.record(rec("t1", "alac:24:44100", "content://m/1", 10))
        s.record(rec("t1", "aac:128", "content://m/2", 20))
        assertEquals(listOf("aac:128", "alac:24:44100"), s.existing("t1").map { it.spec })
    }

    @Test fun theSameFileRecordedAgainReplacesItsEntry() {
        val s = store()
        s.record(rec("t1", "", "content://m/1", 10))
        s.record(rec("t1", "alac:24:44100", "content://m/1", 20))
        assertEquals(listOf("alac:24:44100"), s.existing("t1").map { it.spec })
    }

    @Test fun aFileThatIsGoneIsNoLongerReportedAndIsForgotten() {
        val s = store()
        s.record(rec("t1", "aac:256", "content://m/1")); s.record(rec("t1", "aac:128", "content://m/2"))
        present -= "content://m/1"
        assertEquals(listOf("aac:128"), s.existing("t1").map { it.spec })
        present -= "content://m/2"
        assertTrue(s.existing("t1").isEmpty())
        assertEquals("[]", file.readText().trim().replace(" ", ""))   // forgotten for good
        present += "content://m/1"
        assertTrue("a file that comes back is not resurrected", s.existing("t1").isEmpty())
    }

    @Test fun manyTracksAtOnceAndTheOldestEntriesGoPastTheLimit() {
        val s = store(limit = 3)
        for (i in 1..5) s.record(rec("t$i", "aac:128", "content://m/$i", i.toLong()))
        assertEquals(setOf("t3", "t4", "t5"), s.existing(listOf("t1", "t2", "t3", "t4", "t5")).keys)
    }

    @Test fun aCorruptOrMissingFileIsAnEmptyRegistryNotACrash() {
        assertTrue(store().existing("t1").isEmpty())
        file.writeText("{ this is not json")
        assertTrue(store().existing("t1").isEmpty())
        store().record(rec("t1", "aac:128", "content://m/1"))
        assertEquals(1, store().existing("t1").size)
    }

    @Test fun concurrentWritersDoNotLoseEntries() {
        val s = store()
        val threads = (1..8).map { n -> Thread { for (i in 1..25) s.record(rec("t$n", "aac:128", "content://m/$n-$i", i.toLong())) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(8 * 25, (1..8).sumOf { s.existing("t$it").size })
    }
}
