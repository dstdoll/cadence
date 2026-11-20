package app.cadence.backend.engine.media

import android.content.Context
import android.net.Uri
import app.cadence.backend.engine.CadenceLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One downloaded file and the quality it was downloaded in. [spec] is `alac:24:96000`, `aac:128`, `atmos:768`, `spatial`, or "" when unknown. */
@Serializable
data class DownloadRecord(
    val trackId: String,
    val spec: String,
    val uri: String,
    val name: String,
    val atMs: Long,
)

/**
 * What has been downloaded, and in which quality, kept across restarts (`files/downloads_registry.json`).
 * The download window only knows this session's jobs; this is what lets the app say "this song is already downloaded
 * as Lossless 24-bit / 96 kHz" before it creates a second file. A song can have several files (one per quality).
 *
 * An entry is checked against the file system when asked for ([existing]) and dropped if the file is gone, so a
 * song the person deleted is no longer reported as downloaded. Files downloaded before this existed are not listed.
 */
class DownloadRegistryStore(private val file: File, private val exists: (String) -> Boolean, private val limit: Int = 5000) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun load(): MutableList<DownloadRecord> =
        runCatching { json.decodeFromString(ListSerializer(DownloadRecord.serializer()), file.readText()).toMutableList() }.getOrElse { mutableListOf() }

    private fun save(list: List<DownloadRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(DownloadRecord.serializer()), list))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { CadenceLog.e("download", "could not save the downloads registry", it) }
    }

    /** Adds [record]; the same file recorded again (same uri) replaces its entry, and the oldest entries go past [limit]. */
    fun record(record: DownloadRecord) = synchronized(lock) {
        val list = load()
        list.removeAll { it.uri == record.uri }
        list += record
        save(if (list.size > limit) list.takeLast(limit) else list)
    }

    /** The files that exist for [trackId], newest first; entries whose file is gone are removed. */
    fun existing(trackId: String): List<DownloadRecord> = synchronized(lock) {
        val list = load()
        val mine = list.filter { it.trackId == trackId }
        if (mine.isEmpty()) return emptyList()
        val gone = mine.filterNot { exists(it.uri) }
        if (gone.isNotEmpty()) save(list - gone.toSet())
        (mine - gone.toSet()).sortedByDescending { it.atMs }
    }

    /** [existing] for several tracks at once (one read, one write). */
    fun existing(trackIds: Collection<String>): Map<String, List<DownloadRecord>> = synchronized(lock) {
        val list = load()
        val wanted = trackIds.toSet()
        val mine = list.filter { it.trackId in wanted }
        val gone = mine.filterNot { exists(it.uri) }.toSet()
        if (gone.isNotEmpty()) save(list.filterNot { it in gone })
        mine.filterNot { it in gone }.groupBy { it.trackId }.mapValues { (_, v) -> v.sortedByDescending { it.atMs } }
    }
}

/** The app-wide registry, backed by `files/downloads_registry.json`. */
object DownloadRegistry {
    @Volatile private var store: DownloadRegistryStore? = null

    private fun store(context: Context): DownloadRegistryStore = store ?: synchronized(this) {
        store ?: DownloadRegistryStore(File(context.applicationContext.filesDir, "downloads_registry.json"), exists = { exists(context.applicationContext, it) }).also { store = it }
    }

    fun record(context: Context, record: DownloadRecord) = store(context).record(record)
    fun existing(context: Context, trackId: String): List<DownloadRecord> = store(context).existing(trackId)
    fun existing(context: Context, trackIds: Collection<String>): Map<String, List<DownloadRecord>> = store(context).existing(trackIds)

    /** Whether the published file is still there: a content uri (MediaStore or a chosen folder) or a plain file path. */
    internal fun exists(context: Context, uri: String): Boolean = runCatching {
        when {
            uri.startsWith("content:") -> context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
            uri.startsWith("file:") -> File(Uri.parse(uri).path.orEmpty()).exists()
            else -> File(uri).exists()
        }
    }.getOrDefault(false)
}
