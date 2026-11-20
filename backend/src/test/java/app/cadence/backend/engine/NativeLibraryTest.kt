package app.cadence.backend.engine

import app.cadence.backend.engine.media.NativeLibrary
import app.cadence.backend.engine.media.NativeLibrary.Cause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryTest {
    // Captured from a real Android 10 (API 29) x86_64 run of this app on 2026-11-20.
    private val real = "dalvik.system.PathClassLoader[DexPathList[[zip file \"/data/app/app.cadence-HuQ1oC0RzCxla3xk34uX8A==/base.apk\"],nativeLibraryDirectories=[/data/app/app.cadence-HuQ1oC0RzCxla3xk34uX8A==/lib/x86_64, /system/lib64, /system/product/lib64]]] couldn't find \"libcadence-native.so\""

    @Test fun theRealAndroid10MessageForAMissingAbiIsRecognised() {
        assertEquals(Cause.ABI_MISMATCH, NativeLibrary.classify(real))
    }

    @Test fun theOtherLinkerMessagesAreRecognised() {
        assertEquals(Cause.MISSING_SYSTEM_SYMBOL, NativeLibrary.classify("dlopen failed: cannot locate symbol \"__android_log_set_logger\" referenced by \"/data/app/x/lib/arm64/libcadence-native.so\"..."))
        assertEquals(Cause.WRONG_BITNESS, NativeLibrary.classify("dlopen failed: \"/data/app/x/lib/arm/libcadence-native.so\" is 32-bit instead of 64-bit"))
        assertEquals(Cause.MISSING_DEPENDENCY, NativeLibrary.classify("dlopen failed: library \"libstoreservicescore.so\" not found: needed by /data/app/x/lib/arm64/libcadence-native.so in namespace classloader-namespace"))
        assertEquals(Cause.OTHER, NativeLibrary.classify("something unexpected"))
    }

    @Test fun aFailedLoadNeverThrowsIsRememberedAndOnlyAttemptedOnce() {
        var attempts = 0
        val loader: (String) -> Unit = { attempts++; throw UnsatisfiedLinkError(real) }
        val r1 = NativeLibrary.load("cadence-test-missing", loader)
        val r2 = NativeLibrary.load("cadence-test-missing", loader)
        assertFalse(r1.loaded)
        assertEquals(Cause.ABI_MISMATCH, r1.cause)
        assertEquals(1, attempts)
        assertEquals(r1, r2)
        assertFalse(NativeLibrary.available("cadence-test-missing"))
    }

    @Test fun aSuccessfulLoadIsReported() {
        val r = NativeLibrary.load("cadence-test-ok") { }
        assertTrue(r.loaded && r.cause == null)
    }

    @Test fun loadOrThrowKeepsTheErrorTypeAndNamesTheCause() {
        NativeLibrary.load("cadence-test-throws") { throw UnsatisfiedLinkError(real) }
        val e = runCatching { NativeLibrary.loadOrThrow("cadence-test-throws") }.exceptionOrNull()
        assertTrue(e is UnsatisfiedLinkError)
        assertTrue(e!!.message, e.message!!.contains("ABI_MISMATCH"))
    }
}
