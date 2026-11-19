package app.cadence.backend.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The session file moves an account between installs exactly, and a bad file changes nothing. */
class SessionBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val amp = """{"devToken":"dev","mediaUserToken":"user","storefront":"us","language":"en-US"}"""

    /** A files/ directory as the app leaves it: native provisioning, the account, and unrelated app files. */
    private fun install(dir: File, seed: Int): File = dir.apply {
        mkdirs()
        File(this, "adi.pb").writeBytes(ByteArray(300) { (it * seed).toByte() })
        File(this, "ANDROID_ID").writeText("id$seed")
        File(this, "531EA4BB8D4B206263577F83ADB3948181F99DA$seed").writeBytes(ByteArray(64) { seed.toByte() })
        File(this, "mpl_db").mkdirs()
        File(this, "mpl_db/accounts.sqlitedb").writeBytes(ByteArray(4096) { (it xor seed).toByte() })
        File(this, "mpl_db/accounts.sqlitedb-wal").writeBytes(ByteArray(10) { seed.toByte() })
        File(this, "sessions").mkdirs()
        File(this, "sessions/active.json").writeText("""{"accessToken":"user$seed","storefront":"us"}""")
        File(this, "logs").mkdirs()
        File(this, "logs/cadence.log").writeText("not part of the session")
        File(this, "datastore").mkdirs()
        File(this, "datastore/settings.pb").writeText("settings $seed")
    }

    private fun export(dir: File) = ByteArrayOutputStream().also { SessionBundle.export(dir, amp, it) }.toByteArray()

    /** Each file's hash, of its plain contents when it is sealed (sealing changes the bytes, not the secret). */
    private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { f ->
            val raw = f.readBytes()
            f.relativeTo(dir).path to SessionBundle.sha256(if (SecretFiles.isSealed(raw)) SecretFiles.readBytes(f)!! else raw)
        }

    @Before
    fun useSoftwareKey() {
        SecretFiles.cipher = SoftwareCipher.fresh()
    }

    @After
    fun restoreKeystore() {
        SecretFiles.cipher = KeystoreCipher
    }

    @Test
    fun aSessionMovesToAnotherInstallExactly() {
        val source = install(tmp.newFolder("a"), 3)
        val bytes = export(source)
        val target = tmp.newFolder("b")
        val s = SessionBundle.import(target, bytes.inputStream())
        assertEquals(7, s.entries) // amp.json, active.json, 5 native files

        val sessionPart = { m: Map<String, String> -> m.filterKeys { !it.startsWith("logs") && !it.startsWith("datastore") && it != "engine/amp.json" } }
        assertEquals(sessionPart(tree(source)), sessionPart(tree(target)))
        assertEquals(amp, SecretFiles.read(SessionBundle.importedConfig(target)))
        // The secrets arrive in plain form and are sealed on import.
        assertTrue(SecretFiles.isSealed(File(target, "sessions/active.json").readBytes()))
        assertTrue(SecretFiles.isSealed(SessionBundle.importedConfig(target).readBytes()))
        assertFalse("the log is not part of a session", File(target, "logs/cadence.log").exists())
        assertFalse("the scratch directory is left behind", File(target, ".session-import").exists())
    }

    @Test
    fun importReplacesTheWholeNativeStateButLeavesOtherAppFilesAlone() {
        val old = install(tmp.newFolder("old"), 5)
        File(old, "7EC02DDE2BAD722606836D97F5E79E24B7192B58").writeText("a key of the previous account")
        val bytes = export(install(tmp.newFolder("new"), 9))
        SessionBundle.import(old, bytes.inputStream())
        assertFalse("a key of the previous account survived", File(old, "7EC02DDE2BAD722606836D97F5E79E24B7192B58").exists())
        assertEquals("id9", File(old, "ANDROID_ID").readText())
        assertEquals("settings 5", File(old, "datastore/settings.pb").readText())
        assertEquals("not part of the session", File(old, "logs/cadence.log").readText())
    }

    private fun rezip(bytes: ByteArray, edit: (String, ByteArray) -> Pair<String, ByteArray>?, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    val (name, data) = edit(e.name, input.readBytes()) ?: continue
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                }
            }
            extra.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun assertRejectedAndUntouched(bad: ByteArray, reason: String) {
        val target = install(tmp.newFolder(), 1)
        val before = tree(target)
        val e = assertThrows(IOException::class.java) { SessionBundle.import(target, bad.inputStream()) }
        assertTrue("expected '$reason' in '${e.message}'", e.message!!.contains(reason))
        assertEquals("a rejected file changed the install", before, tree(target))
    }

    @Test
    fun aDamagedOrForeignFileIsRejectedAndChangesNothing() {
        val good = export(install(tmp.newFolder("src"), 2))
        assertRejectedAndUntouched(rezip(good, { n, d -> if (n == "native/adi.pb") n to d.copyOf().also { it[0] = (it[0] + 1).toByte() } else n to d }), "damaged")
        assertRejectedAndUntouched(rezip(good, { n, d -> n to d }, mapOf("native/../../shared_prefs/x.xml" to ByteArray(1))), "unexpected entry")
        assertRejectedAndUntouched(rezip(good, { n, d -> n to d }, mapOf("/etc/passwd" to ByteArray(1))), "unexpected entry")
        assertRejectedAndUntouched(rezip(good, { n, d -> if (n == "manifest.json") null else n to d }), "no manifest")
        assertRejectedAndUntouched(rezip(good, { n, d -> if (n == "native/ANDROID_ID") null else n to d }), "does not match")
        assertRejectedAndUntouched("not a zip at all".toByteArray(), "no manifest")
    }

    @Test
    fun onlyKnownNamesAreAccepted() {
        for (ok in listOf("amp.json", "sessions/active.json", "native/adi.pb", "native/mpl_db/kvs.sqlitedb-wal", "native/531EA4BB8D4B206263577F83ADB3948181F99DA3"))
            assertTrue(ok, SessionBundle.isAllowed(ok))
        for (bad in listOf("native/../x", "native/mpl_db/../../x", "native/mpl_db/..", "native/evil.so", "sessions/other.json", "/abs", "native/mpl_db/a/b"))
            assertFalse(bad, SessionBundle.isAllowed(bad))
    }

    @Test
    fun nothingToExportIsAnError() {
        val empty = tmp.newFolder("empty")
        assertThrows(IOException::class.java) { SessionBundle.export(empty, amp, ByteArrayOutputStream()) }
        val bytes = export(install(tmp.newFolder("x"), 4))
        assertArrayEquals("export is deterministic for the same files", bytes, export(File(tmp.root, "x")))
    }
}
