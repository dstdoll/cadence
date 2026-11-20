package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogQualityTest {
    @Test fun appleAccountIdentifiersAreRedacted() {
        val raw = "FairPlay: dsid=1234567890 guid: ABCDEF123456 serialNumber=\"C02XYZ12345\" UDID 00008110-001234567890801E ok"
        val out = LogRedactor.clean(raw)
        assertFalse(out, out.contains("1234567890") || out.contains("ABCDEF123456") || out.contains("C02XYZ12345"))
        assertTrue(out, out.contains("dsid=<id>") && out.contains("ok"))
    }

    @Test fun ordinaryWordsAreNotMangled() {
        val raw = "serial port opened; guidance given; personal mix ready"
        assertEquals(raw, LogRedactor.clean(raw))
    }

    @Test fun anOversizedLogIsTrimmedToItsNewestWholeLines() {
        val dir = kotlin.io.path.createTempDirectory("log").toFile()
        try {
            val f = File(dir, "cadence.log")
            f.writeText((1..5000).joinToString("") { "line $it padding padding padding\n" })
            CadenceLog.trimToTail(f, maxBytes = 10_000, keep = 2_000)
            val t = f.readText()
            assertTrue(t.startsWith("=== earlier lines trimmed"))
            assertTrue(t.trimEnd().endsWith("line 5000 padding padding padding"))
            assertTrue(t.lines().drop(1).all { it.isEmpty() || it.startsWith("line ") })
            val small = File(dir, "small.log").apply { writeText("keep me\n") }
            CadenceLog.trimToTail(small, maxBytes = 10_000, keep = 2_000)
            assertEquals("keep me\n", small.readText())
        } finally { dir.deleteRecursively() }
    }
}
