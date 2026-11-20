package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogCaptureTest {
    private fun l(prio: String, tag: String, msg: String, tid: Int = 4300) = "11-20 01:17:15.245  4242 $tid $prio $tag: $msg"

    @Test fun parsesThreadtimeLinesAndRejectsOthers() {
        val e = AppleLogcat.parse(l("D", "FairPlay", "status: 0"))!!
        assertEquals(listOf("4300", 'D', "FairPlay", "status: 0"), listOf(e.tid, e.priority, e.tag, e.message))
        assertNull(AppleLogcat.parse("--------- beginning of main"))
        assertNull(AppleLogcat.parse("not a log line"))
    }

    @Test fun keepsAppleTagsAndAllWarningsButNotOurOwnTagOrNoise() {
        fun ok(s: String) = AppleLogcat.accepts(AppleLogcat.parse(s)!!)
        assertTrue(ok(l("D", "FairPlay", "x")))
        assertTrue(ok(l("W", "Whatever", "x")))
        assertTrue(ok(l("I", "Other", "end lease 7 from Lease manager")))
        assertFalse(ok(l("I", "Choreographer", "skipped frames")))
        assertFalse(ok(l("E", "Cadence", "ours")))
    }

    @Test fun pipelineLimitsAFloodReportsItAndCollapsesRepeats() {
        var t = 1_000_000L
        val p = AppleLogcat.Pipeline(maxPerSecond = 3) { t }
        val out = ArrayList<Pair<String, String>>()
        repeat(10) { i -> out += p.process(l("W", "FairPlay", "distinct $i")) }
        assertEquals(3, out.size)                       // only 3 get through in that second
        t += 1_500
        val next = p.process(l("W", "FairPlay", "after"))
        assertTrue(next.first().second.contains("7 Apple/native line(s) dropped"))
        assertEquals("after", next.last().second.substringAfter("t4300 "))
        // repeats: the same line five times is one line plus a count when something else arrives
        t += 1_500
        val rep = ArrayList<Pair<String, String>>()
        repeat(5) { rep += p.process(l("W", "FairPlay", "same")) }
        rep += p.process(l("W", "FairPlay", "different"))
        assertEquals(1, rep.count { it.second.endsWith("same") })
        assertTrue(rep.any { it.second.contains("repeated 4 more time") })
    }

    @Test fun trackedLinesAreFormattedWithPriorityAndThread() {
        val out = AppleLogcat.Pipeline { 0L }.process(l("E", "SVError", "Invalid CKC", tid = 2197))
        assertEquals("SVError" to "E t2197 Invalid CKC", out.single())
    }

    @Test fun oneLineFormatAndStackTracesAreRedactedAndBounded() {
        val line = LogSink.format("E", "native", "msg", "cadence-kd", now = 0L)
        assertTrue(line, Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} E/native \[cadence-kd\] msg$""").matches(line))
        val t = IllegalStateException("fail for me@example.com", RuntimeException("inner"))
        val trace = LogSink.trace(t, frames = 2)
        assertTrue(trace, trace.startsWith("    java.lang.IllegalStateException: fail for m***@example.com"))
        assertTrue(trace, trace.contains("Caused by: java.lang.RuntimeException: inner"))
        val firstBlock = trace.substringBefore("Caused by")
        assertEquals(2, firstBlock.lines().count { it.startsWith("    at ") })
        assertTrue(trace, Regex("""    \.\.\. \d+ more""").containsMatchIn(firstBlock))
        assertFalse(trace.contains("me@example.com"))
    }

    @Test fun concurrentWritersNeverTearOrInterleaveLines() {
        val dir = kotlin.io.path.createTempDirectory("sink").toFile()
        try {
            LogSink.file = File(dir, "cadence.log")
            val threads = (1..6).map { n -> Thread { repeat(200) { LogSink.append("I", "t$n", "message number $it of thread $n ${"x".repeat(80)}") } } }
            threads.forEach { it.start() }; threads.forEach { it.join() }
            val lines = LogSink.file!!.readLines()
            assertEquals(1200, lines.size)
            val shape = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} I/t\d \[[^\]]+\] message number \d+ of thread \d x{80}$""")
            assertTrue(lines.firstOrNull { !shape.matches(it) }.toString(), lines.all { shape.matches(it) })
        } finally { LogSink.file = null; dir.deleteRecursively() }
    }

    @Test fun summaryShowsCountsClassesAccountStateAndHints() {
        val log = listOf(
            "=== start Fri ===",
            "2026-11-20 01:17:09.678 I/amp [main] storefront=mx (from account)",
            "2026-11-20 01:17:10.000 I/auth [io] subscription: active=false storefront=mx",
            "2026-11-20 01:17:10.100 I/provisioning [io] after sign-in: adi.pb=120B/3s-old fsi.pdat=missing mpl_db=missing retired=0",
            "2026-11-20 01:17:15.245 E/native [cadence-kd] [CXX-THROW] class=KEY_REJECTED impact=track entry=kd",
            "2026-11-20 01:17:16.245 E/native [cadence-kd] [CXX-THROW] class=KEY_REJECTED impact=track entry=kd",
            "2026-11-20 01:17:16.300 A/FairPlay [main] D t1 sub status 0",
        ).joinToString("\n")
        val s = LogSummary.build(log, listOf("version=0.1.8"))
        assertTrue(s, s.contains("error lines=2") && s.contains("KEY_REJECTED=2") && s.contains("apple/native logcat lines=1"))
        assertTrue(s, s.contains("storefront=mx") && s.contains("subscription: active=false"))
        assertTrue(s, s.contains("NOT active") && s.contains("provisioning did not complete"))
        assertTrue(s, s.contains("last errors:") && s.contains("version=0.1.8"))
        val empty = LogSummary.build("", emptyList())
        assertTrue(empty, empty.contains("native error classes: none") && empty.contains("last errors:\n  none"))
    }

    @Test fun whatTheWritersProduceIsWhatTheSummaryReads() {
        val dir = kotlin.io.path.createTempDirectory("contract").toFile()
        try {
            LogSink.file = File(dir, "cadence.log")
            CadenceLog.i("amp", "storefront=mx (from account)")
            CadenceLog.i("auth", "subscription: active=false storefront=mx")
            CadenceLog.e("native", "[CXX-THROW] class=KEY_REJECTED impact=track entry=kd", IllegalStateException("boom"))
            app.cadence.backend.engine.media.AppLog.error("download", "failed for 1", RuntimeException("x"))
            LogSink.append("A", "FairPlay", "D t1 hello")
            val s = LogSummary.build(LogSink.file!!.readText(), emptyList())
            assertTrue(s, s.contains("error lines=2") && s.contains("KEY_REJECTED=1") && s.contains("apple/native logcat lines=1"))
            assertTrue(s, s.contains("storefront=mx") && s.contains("subscription: active=false storefront=mx") && s.contains("NOT active"))
            val lines = LogSink.file!!.readLines()
            assertTrue("stack traces follow error lines", lines.count { it.startsWith("    at ") } >= 2)
            assertTrue("a trace names the exception", lines.any { it.trim().startsWith("java.lang.IllegalStateException: boom") })
        } finally { LogSink.file = null; dir.deleteRecursively() }
    }

    @Test fun olderLogFormatsAreStillSummarised() {
        val log = listOf(
            "01:16:51.585 I/cache account/storefront changed",
            "01:17:09.678 I/amp storefront=mx (from account)",
            "2026-11-20 01:17:44.025 E/DefaultDispatcher-worker-8 Cadence: streaming download failed",
            "01:17:15.245 E/native [CXX-THROW] class=KEY_REJECTED impact=track entry=kd (adam=1 key=c6)",
        ).joinToString("\n")
        val s = LogSummary.build(log, emptyList())
        assertTrue(s, s.contains("error lines=2") && s.contains("KEY_REJECTED=1") && s.contains("storefront=mx"))
    }

    @Test fun theLogcatFollowerStandsDownOnlyWhenTheNativeHookReportsItself() {
        AppleLogcat.liveNative = false
        AppleLogcat.noteNativeEvent("[NATIVE-INFO] native layer loaded")
        assertFalse(AppleLogcat.liveNative)
        AppleLogcat.noteNativeEvent("[HOOK] logger=off throw=off (files/hooks.off exists)")
        assertFalse("a disabled hook must not silence the fallback", AppleLogcat.liveNative)
        AppleLogcat.noteNativeEvent("[HOOK] logger=on throw=on slots=6 already=0")
        assertTrue(AppleLogcat.liveNative)
        AppleLogcat.liveNative = false
    }

    @Test fun summaryReportsHookStateAndExceptionsRaisedInsideApplesLibraries() {
        val log = listOf(
            "2026-11-20 01:17:10.000 I/native [main] [HOOK] logger=on throw=on slots=6 already=0",
            "2026-11-20 01:17:15.000 I/native [cadence-hooks] [CXX-RAISE] type=N11storeservices7SVErrorE x1 at libstoreservicescore.so+0x1a2b3c <- libandroidappmusic.so+0x9 (thread cadence-kd)",
            "2026-11-20 01:17:15.100 I/native [cadence-hooks] [CXX-RAISE] type=N11storeservices7SVErrorE x2 at libstoreservicescore.so+0x1a2b3c <- libandroidappmusic.so+0x9 (thread cadence-kd)",
            "2026-11-20 01:17:15.200 I/native [cadence-hooks] [CXX-RAISE] type=St13runtime_error x1 at libmediaplatform.so+0x5 (thread t)",
        ).joinToString("\n")
        val s = LogSummary.build(log, emptyList())
        assertTrue(s, s.contains("native hooks: logger=on throw=on slots=6 already=0"))
        assertTrue(s, s.contains("N11storeservices7SVErrorE=2") && s.contains("St13runtime_error=1"))
        assertTrue(LogSummary.build("", emptyList()).contains("native hooks: not reported"))
    }

    @Test fun writeRedactsAppleLines() {
        val dir = kotlin.io.path.createTempDirectory("hookw").toFile()
        try {
            LogSink.file = File(dir, "cadence.log")
            AppleLogcat.write("FairPlay", "D t1 dsid=123456789 user me@example.com")
            val l = LogSink.file!!.readText()
            assertTrue(l, l.contains(" A/FairPlay [") && l.contains("dsid=<id>") && !l.contains("me@example.com"))
        } finally { LogSink.file = null; dir.deleteRecursively() }
    }

    @Test fun platformNoiseIsFilteredButRealDiagnosesAreKept() {
        fun ok(s: String) = AppleLogcat.accepts(AppleLogcat.parse(s)!!)
        assertFalse(ok(l("W", "app.cadence", "Accessing hidden method Landroid/content/ContextWrapper;->getDisplay()Landroid/view/Display; (greylist, linking, allowed)")))
        assertFalse(ok(l("E", "app.cadence", "Unknown bits set in runtime_flags: 0x8000")))
        assertFalse(ok(l("W", "app.cadence", "Unexpected CPU variant for X86 using defaults: x86_64")))
        assertTrue("an SELinux denial can explain a failure", ok(l("W", "RenderThread", "type=1400 audit(0.0:23): avc: denied { write } for name=\"x\"")))
        assertTrue(ok(l("E", "KeyStore", "generateKeyInternal failed on request -68")))
        assertTrue(ok(l("W", "FairPlay", "Accessing hidden state")))
    }

    @Test fun summaryReportsNativeLibraryLoadAndExplainsAFailure() {
        val failed = listOf(
            "2026-11-20 12:28:08.000 E/native [cadence-native-init] [NATIVE-LOAD] libcadence-native.so FAILED cause=ABI_MISMATCH: this device runs a CPU architecture the app ships no native libraries for | sdk=29 abis=[x86_64, x86] | linker: couldn't find",
        ).joinToString("\n")
        val s = LogSummary.build(failed, emptyList())
        assertTrue(s, s.contains("native libraries: libcadence-native.so=FAILED(ABI_MISMATCH)"))
        assertTrue(s, s.contains("did not load") && s.contains("playback engine"))
        val ok = LogSummary.build("2026-11-20 12:28:08.000 I/native [main] [NATIVE-LOAD] libcadence-native.so ok sdk=34 abis=[arm64-v8a]", emptyList())
        assertTrue(ok, ok.contains("native libraries: libcadence-native.so=ok") && !ok.contains("hints:"))
    }

    @Test fun summaryShowsWhyTheLastSignInFailed() {
        val log = "2026-11-20 12:40:13.811 E/auth [io] sign-in failed: Apple answered with a failure, network=Reachable -> SignInRejected (the [native] auth: lines above say what StoreServices reported)"
        val s = LogSummary.build(log, emptyList())
        assertTrue(s, s.contains("sign-in: 12:40:13.811 sign-in failed: Apple answered with a failure, network=Reachable -> SignInRejected"))
        assertTrue(LogSummary.build("", emptyList()).contains("sign-in: no failure recorded"))
    }
}
