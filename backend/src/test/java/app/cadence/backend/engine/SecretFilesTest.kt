package app.cadence.backend.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Secrets at rest: sealed on disk, plain files migrated, tampering and file swaps refused. */
class SecretFilesTest {
    private lateinit var dir: File
    private val token = """{"accessToken":"secret-token-value-1234567890","email":"someone@example.org"}"""

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("secrets").toFile()
        SecretFiles.cipher = SoftwareCipher.fresh()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        SecretFiles.cipher = KeystoreCipher
    }

    private fun containsPlain(bytes: ByteArray, text: String) = String(bytes, Charsets.ISO_8859_1).contains(text)

    @Test
    fun aWrittenSecretIsSealedOnDiskAndReadsBack() {
        val f = File(dir, "active.json")
        SecretFiles.write(f, token)
        val raw = f.readBytes()
        assertTrue(SecretFiles.isSealed(raw))
        assertFalse("the token must not be readable on disk", containsPlain(raw, "secret-token-value"))
        assertFalse(containsPlain(raw, "someone@example.org"))
        assertEquals(token, SecretFiles.read(f))
        assertFalse("no temporary file left", File(dir, "active.json.tmp").exists())
    }

    @Test
    fun aPlainFileIsReadAsIsAndSealedInPlace() {
        val f = File(dir, "MUSIC_TOKEN").apply { writeText("plain-music-token") }
        assertEquals("plain-music-token", SecretFiles.read(f))
        assertTrue("migrated on first read", SecretFiles.isSealed(f.readBytes()))
        assertEquals("plain-music-token", SecretFiles.read(f))
    }

    @Test
    fun aTamperedFileReadsAsAbsent() {
        val f = File(dir, "amp.json")
        SecretFiles.write(f, token)
        val raw = f.readBytes()
        raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 1).toByte()
        f.writeBytes(raw)
        assertNull(SecretFiles.read(f))
    }

    @Test
    fun aSecretCopiedOverAnotherFileDoesNotOpen() {
        val account = File(dir, "active.json").also { SecretFiles.write(it, token) }
        val other = File(dir, "amp.json").apply { writeBytes(account.readBytes()) }
        assertNull("sealed for active.json, not for amp.json", SecretFiles.read(other))
    }

    @Test
    fun eachWriteUsesAFreshIv() {
        val f = File(dir, "active.json")
        SecretFiles.write(f, token)
        val first = f.readBytes()
        SecretFiles.write(f, token)
        assertFalse(first.contentEquals(f.readBytes()))
        assertEquals(token, SecretFiles.read(f))
    }

    @Test
    fun sealInPlaceLeavesSealedFilesAndMissingOnesAlone() {
        val f = File(dir, "active.json").also { SecretFiles.write(it, token) }
        val before = f.readBytes()
        SecretFiles.sealInPlace(f)
        assertArrayEquals(before, f.readBytes())
        SecretFiles.sealInPlace(File(dir, "missing"))
        assertFalse(File(dir, "missing").exists())
        assertNull(SecretFiles.read(File(dir, "missing")))
    }
}
