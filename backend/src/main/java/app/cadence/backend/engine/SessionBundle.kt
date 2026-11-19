package app.cadence.backend.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The account session as one file that can move between installs (Settings, Session): the
 * MusicKit config, the signed-in account and the native engine's provisioning files. Anyone holding
 * the file can use the account, so the UI says so before writing it.
 *
 * Layout: `manifest.json` (format, version and the SHA-256 of every entry), `amp.json`,
 * `sessions/active.json` and `native/<name>`. Import only accepts known names, checks every hash,
 * extracts into a scratch directory and swaps the files in only when everything checked out, so a
 * bad file changes nothing.
 */
object SessionBundle {
    const val FORMAT = "cadence-session"
    const val VERSION = 1
    private const val MANIFEST = "manifest.json"
    private const val AMP = "amp.json"
    private const val ACCOUNT = "sessions/active.json"
    private const val NATIVE = "native/"
    private const val NATIVE_DB = "mpl_db"
    /** Native files Cadence seals at rest (read by Kotlin only). */
    private val SEALED_NATIVE = setOf("native/MUSIC_TOKEN")
    private val NATIVE_NAMES = setOf(
        "ANDROID_ID", "DEV_TOKEN", "IC-Info.sids", "MUSIC_TOKEN", "STOREFRONT_ID", "adi.pb", "fsi.pdat", "token_cache.json",
    )
    private val KEY_FILE = Regex("[0-9A-F]{40}")
    private val DB_FILE = Regex("[A-Za-z0-9_.-]+")
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    data class Summary(val entries: Int, val bytes: Long)

    /** Where an imported config is kept; [AmpConfig.load] reads it before any bundled asset. */
    fun importedConfig(filesDir: File) = File(filesDir, "engine/amp.json")

    /** The native engine's provisioning files under [filesDir], as (name in the bundle, file). */
    internal fun nativeFiles(filesDir: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        filesDir.listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
            when {
                f.isFile && (f.name in NATIVE_NAMES || KEY_FILE.matches(f.name)) -> out += NATIVE + f.name to f
                f.isDirectory && f.name == NATIVE_DB -> f.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                    .forEach { out += "$NATIVE$NATIVE_DB/${it.name}" to it }
            }
        }
        return out
    }

    /** Whether [name] may appear in a bundle (no paths outside the known layout). */
    internal fun isAllowed(name: String): Boolean {
        if (name == AMP || name == ACCOUNT) return true
        if (!name.startsWith(NATIVE)) return false
        val rest = name.removePrefix(NATIVE)
        if (rest in NATIVE_NAMES || KEY_FILE.matches(rest)) return true
        return rest.startsWith("$NATIVE_DB/") && DB_FILE.matches(rest.removePrefix("$NATIVE_DB/")) &&
            rest.removePrefix("$NATIVE_DB/").let { it != "." && it != ".." }
    }

    /** Writes the bundle: [ampJson] is the effective config; the rest is read from [filesDir]. */
    fun export(filesDir: File, ampJson: String, out: OutputStream): Summary {
        val entries = LinkedHashMap<String, ByteArray>()
        entries[AMP] = ampJson.toByteArray()
        // The bundle carries secrets in plain form: another install's keystore could not open them.
        SecretFiles.readBytes(File(filesDir, ACCOUNT))?.let { entries[ACCOUNT] = it }
        nativeFiles(filesDir).forEach { (name, file) ->
            entries[name] = if (name in SEALED_NATIVE) SecretFiles.readBytes(file) ?: return@forEach else file.readBytes()
        }
        if (entries.keys.none { it.startsWith(NATIVE) }) throw IOException("no native session files to export")
        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("files", JSONObject().also { files -> entries.forEach { (name, bytes) -> files.put(name, sha256(bytes)) } })
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return Summary(entries.size, entries.values.sumOf { it.size.toLong() })
    }

    /** Reads, verifies and installs a bundle into [filesDir]. Throws [IOException] and changes nothing on any problem. */
    fun import(filesDir: File, input: InputStream): Summary {
        val read = LinkedHashMap<String, ByteArray>()
        var manifest: JSONObject? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = readCapped(zip, e.name)
                when {
                    e.name == MANIFEST -> manifest = runCatching { JSONObject(String(bytes)) }.getOrNull()
                        ?: throw IOException("unreadable manifest")
                    !isAllowed(e.name) -> throw IOException("unexpected entry ${e.name}")
                    read.put(e.name, bytes) != null -> throw IOException("duplicate entry ${e.name}")
                }
            }
        }
        val m = manifest ?: throw IOException("not a session file (no manifest)")
        if (m.optString("format") != FORMAT) throw IOException("not a session file")
        if (m.optInt("version") != VERSION) throw IOException("unsupported session file version ${m.optInt("version")}")
        val listed = m.optJSONObject("files") ?: throw IOException("manifest lists no files")
        val names = listed.keys().asSequence().toSet()
        if (names != read.keys) throw IOException("manifest does not match the files")
        for ((name, bytes) in read) if (listed.getString(name) != sha256(bytes)) throw IOException("$name is damaged")
        if (AMP !in read) throw IOException("session file has no config")
        if (read.keys.none { it.startsWith(NATIVE) }) throw IOException("session file has no native files")

        // Stage everything first; only then replace the current files.
        val stage = File(filesDir, ".session-import").apply { deleteRecursively(); mkdirs() }
        try {
            read.forEach { (name, bytes) -> File(stage, name).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
            nativeFiles(filesDir).forEach { (_, f) -> f.delete() }
            File(filesDir, NATIVE_DB).takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
            read.keys.forEach { name ->
                val target = when {
                    name == AMP -> importedConfig(filesDir)
                    name == ACCOUNT -> File(filesDir, ACCOUNT)
                    else -> File(filesDir, name.removePrefix(NATIVE))
                }
                target.parentFile?.mkdirs()
                val staged = File(stage, name)
                if (!staged.renameTo(target)) { staged.copyTo(target, overwrite = true); staged.delete() }
            }
        } finally {
            stage.deleteRecursively()
        }
        // Secrets arrive in plain form; seal them now rather than at their first read.
        listOf(importedConfig(filesDir), File(filesDir, ACCOUNT), File(filesDir, "MUSIC_TOKEN")).forEach(SecretFiles::sealInPlace)
        return Summary(read.size, read.values.sumOf { it.size.toLong() })
    }

    private fun readCapped(input: InputStream, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("$name is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
