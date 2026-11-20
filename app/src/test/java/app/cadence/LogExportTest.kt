package app.cadence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExportTest {
    private fun l(prio: String, tag: String, msg: String) = "11-20 01:17:15.245  4242  4300 $prio $tag: $msg"

    @Test fun keepsAppleTagsAtAnyPriorityAndAllWarningsAndErrorsButNotOurOwnTag() {
        val out = LogExport.appleLibLines(listOf(
            l("D", "FairPlay", "subscription status 0"),
            l("I", "Choreographer", "skipped frames"),
            l("W", "SomethingElse", "odd state"),
            l("E", "Cadence", "dup of cadence.log"),
            l("I", "cadence-native", "end lease 7"),
            "garbage line without a format",
        ))
        assertEquals(listOf("FairPlay", "SomethingElse", "cadence-native"), out.map { Regex("[VDIWEF] (\\S+):").find(it)!!.groupValues[1] })
    }

    @Test fun linesAreRedacted() {
        val out = LogExport.appleLibLines(listOf(
            l("D", "FairPlay", "token=eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.abcdefghijk.lmnopqrstuv mail me@example.com"),
        ))
        assertEquals(1, out.size)
        assertFalse(out[0].contains("eyJ0eXAi") || out[0].contains("me@example.com"))
    }

    @Test fun keepsOnlyTheNewestLinesWithTheirTimestamps() {
        val out = LogExport.appleLibLines((1..1000).map { l("W", "X", "line $it") })
        assertEquals(400, out.size)
        assertTrue(out.last().endsWith("line 1000"))
        assertTrue(out.first().startsWith("11-20 01:17:15.245"))
    }
}
