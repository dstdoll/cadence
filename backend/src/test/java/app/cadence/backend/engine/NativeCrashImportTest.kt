package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCrashImportTest {
    private val a = "\n=== NATIVE CRASH SIGABRT (signal 6) fault=null tid=11 entry=kd apple_call=get_persistent_key ===\n"
    private val b = "\n=== NATIVE CRASH SIGSEGV (signal 11) fault=null tid=12 no native entry point of ours was active (Apple-created thread, or outside the guard) ===\n"

    @Test fun onlyWhatWasAppendedSinceLastTimeIsReported() {
        val (first, n1) = NativeCrashImport.newCrashLines(a, 0)
        assertEquals(1, first.size)
        val (second, n2) = NativeCrashImport.newCrashLines(a + b, n1)
        assertEquals(1, second.size)
        assertEquals(true, second[0].contains("SIGSEGV"))
        assertEquals((a + b).length, n2)
        assertEquals(0, NativeCrashImport.newCrashLines(a + b, n2).first.size)
    }

    @Test fun aClearedFileIsReadFromTheStart() {
        val (lines, n) = NativeCrashImport.newCrashLines(b, 10_000)
        assertEquals(1, lines.size)
        assertEquals(b.length, n)
    }

    @Test fun lineNoiseIsIgnored() {
        assertEquals(0, NativeCrashImport.newCrashLines("hello\nworld\n", 0).first.size)
    }
}
