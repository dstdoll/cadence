package app.cadence.backend.engine

import app.cadence.backend.engine.media.Mp4Integrity
import org.junit.Test
import java.io.File

/**
 * Runs [Mp4Integrity] against real files on the PC: an encrypted package staged by
 * HlsPackageProbeTest and a decrypted `.m4a` pulled from a device. Skipped unless both paths are
 * given (env INTEGRITY_PKG / INTEGRITY_OUT).
 */
class Mp4IntegrityHostTest {
    @Test
    fun realFiles() {
        val pkg = System.getenv("INTEGRITY_PKG")?.let(::File)?.takeIf { it.isFile }
        val out = System.getenv("INTEGRITY_OUT")?.let(::File)?.takeIf { it.isFile }
        pkg?.let {
            val e = Mp4Integrity.expected(it)
            println("[integrity] package fragments=${e.fragments} samples=${e.samples} ticks=${e.ticks} timescale=${e.timescale} seconds=${"%.3f".format(e.seconds)}")
        }
        out?.let {
            val a = Mp4Integrity.actual(it)
            println("[integrity] output samples=${a.samples} ticks=${a.ticks} timescale=${a.timescale} seconds=${"%.4f".format(a.seconds)}")
        }
        if (pkg != null && out != null) println("[integrity] verdict=${Mp4Integrity.mismatch(pkg, out) ?: "OK"}")
    }
}
