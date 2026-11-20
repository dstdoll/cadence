package app.cadence.backend.engine.media

import android.content.Context
import app.cadence.backend.engine.CadenceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the master playlists say about a whole album: [total] songs, [known] of them checked, how many have a hi-res
 * lossless rendition (above 48 kHz) and the lowest/highest best-lossless sample rate among the checked ones.
 * The catalogue does not know sample rates (only the masters do), so this is what lets the album page say "Hi-Res".
 */
data class AlbumQuality(val total: Int, val known: Int, val hiRes: Int, val minRate: Int, val maxRate: Int) {
    /** True only when every song was checked and every one has a hi-res lossless rendition. */
    val allHiRes: Boolean get() = total > 0 && known == total && hiRes == total
    /** Whether any checked song has a lossless rendition at all. */
    val anyHiRes: Boolean get() = hiRes > 0
}

/** One song's best lossless rendition as a spec string (`alac:24:192000`), "" = the song has no lossless rendition. */
@Serializable
internal data class IndexEntry(val best: String, val atMs: Long)

/**
 * A small persistent cache of each song's best lossless rendition (`files/quality_index.json`), filled when the quality
 * picker probes songs and when an album page asks. A song's masters rarely change, so entries live [ttlMs].
 */
class QualityIndexStore(private val file: File, private val now: () -> Long = System::currentTimeMillis, private val ttlMs: Long = 30L * 24 * 3600 * 1000, private val limit: Int = 20_000) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), IndexEntry.serializer())
    private val lock = Any()
    private var memory: MutableMap<String, IndexEntry>? = null

    private fun map(): MutableMap<String, IndexEntry> = memory ?: (runCatching { json.decodeFromString(ser, file.readText()).toMutableMap() }.getOrElse { mutableMapOf() }).also { memory = it }

    /** The fresh entries for [trackIds] (spec string, "" for none); stale or unknown songs are absent. */
    fun get(trackIds: Collection<String>): Map<String, String> = synchronized(lock) {
        val m = map(); val t = now()
        trackIds.mapNotNull { id -> m[id]?.takeIf { t - it.atMs < ttlMs }?.let { id to it.best } }.toMap()
    }

    fun put(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(lock) { write(entries) }
    }

    private fun write(entries: Map<String, String>) {
        val m = map(); val t = now()
        entries.forEach { (id, best) -> m[id] = IndexEntry(best, t) }
        if (m.size > limit) m.entries.sortedBy { it.value.atMs }.take(m.size - limit).forEach { m.remove(it.key) }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ser, m)); if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { CadenceLog.e("QUALITY", "could not save the quality index", it) }
    }
}

/** The app-wide index and the album summary built on it. */
object QualityIndex {
    @Volatile private var store: QualityIndexStore? = null
    private const val CONCURRENCY = 4
    private const val MASTER_TIMEOUT_MS = 8_000L

    fun attach(context: Context) { store = QualityIndexStore(File(context.applicationContext.filesDir, "quality_index.json")) }
    internal fun attachForTest(s: QualityIndexStore?) { store = s }

    /** The best lossless rendition of one song's master as a spec string, "" if it has none. */
    internal fun bestLossless(variants: List<HlsVariant>): String =
        QualityPlanner.renditions(variants).firstOrNull { it.family == QualityFamily.LOSSLESS }?.spec?.format().orEmpty()

    /** Remembers what a probe learned about a song's masters. */
    internal fun record(trackId: String, variants: List<HlsVariant>) { store?.put(mapOf(trackId to bestLossless(variants))) }

    /**
     * Summarises an album's songs ([id] to master link): cached songs cost nothing; the others have their masters read (a few at a
     * time, cancellable) and are remembered. Songs whose master cannot be read count as unknown, so a partly known album never
     * claims "all hi-res".
     */
    suspend fun albumQuality(songs: List<Pair<String, String>>, allowFetch: Boolean = true, fetch: suspend (String) -> String? = ::fetchMaster): AlbumQuality {
        val s = store
        val cached = s?.get(songs.map { it.first }).orEmpty()
        // [allowFetch] false (a metered connection): only what is already known; the rest stays unknown, so no false "all hi-res".
        val missing = if (allowFetch) songs.filter { it.first !in cached && it.second.isNotBlank() } else emptyList()
        val fresh = HashMap<String, String>()
        if (missing.isNotEmpty()) {
            val gate = Semaphore(CONCURRENCY)
            coroutineScope {
                missing.map { (id, url) -> async { gate.withPermit {
                    fetch(url)?.let { text -> runCatching { HlsVariants.parse(text, url) }.getOrNull()?.takeIf { QualityPlanner.renditions(it).isNotEmpty() }?.let { id to bestLossless(it) } }
                } } }.awaitAll().filterNotNull().forEach { fresh[it.first] = it.second }
            }
            s?.put(fresh)
        }
        val q = summarize(songs.size, cached + fresh)
        // One line per album look, so a report shows why the pill says what it says.
        CadenceLog.i("QUALITY", "album songs=${q.total} known=${q.known} (cached=${cached.size} fetched=${fresh.size}${if (allowFetch) "" else ", metered: no fetch"}) hiRes=${q.hiRes} rates=${q.minRate}-${q.maxRate} allHiRes=${q.allHiRes}")
        return q
    }

    internal fun summarize(total: Int, best: Map<String, String>): AlbumQuality {
        val rates = best.values.mapNotNull { RenditionSpec.parse(it.ifBlank { null })?.takeIf { sp -> sp.family == QualityFamily.LOSSLESS }?.sampleRate }
        val hi = rates.filter { it > 48_000 }
        return AlbumQuality(total, best.size, hi.size, hi.minOrNull() ?: 0, rates.maxOrNull() ?: 0)
    }

    private suspend fun fetchMaster(url: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(MASTER_TIMEOUT_MS) { runCatching { Http.text(url) }.getOrNull() }
    }
}
