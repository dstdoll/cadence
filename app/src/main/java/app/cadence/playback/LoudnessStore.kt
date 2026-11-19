package app.cadence.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/** A song's measured loudness: integrated LUFS, sample peak (full scale 1.0) and its album key. */
data class Loudness(val lufs: Double, val peak: Double, val album: String?)

/** Where measured loudness is kept between plays. */
interface LoudnessStore {
    fun get(trackId: String): Loudness?
    fun put(trackId: String, loudness: Loudness)

    /** The album's loudness from the songs measured so far (energy average), or null. */
    fun albumLufs(album: String): Double?
}

/** [LoudnessStore] in a small JSON file in the app's private storage, written atomically. */
class FileLoudnessStore(private val file: File) : LoudnessStore {
    private val entries: MutableMap<String, Loudness> by lazy { load() }

    @Synchronized
    override fun get(trackId: String): Loudness? = entries[trackId]

    @Synchronized
    override fun put(trackId: String, loudness: Loudness) {
        entries[trackId] = loudness
        save()
    }

    @Synchronized
    override fun albumLufs(album: String): Double? {
        val songs = entries.values.filter { it.album == album }
        if (songs.isEmpty()) return null
        return 10.0 * log10(songs.sumOf { 10.0.pow(it.lufs / 10.0) } / songs.size)
    }

    /** On-disk form: short keys, since a library can hold thousands of songs. */
    @Serializable
    private data class Entry(val l: Double, val p: Double = 1.0, val a: String? = null)

    private fun load(): MutableMap<String, Loudness> = runCatching {
        json.decodeFromString<Map<String, Entry>>(file.readText())
            .mapValuesTo(HashMap()) { (_, e) -> Loudness(e.l, e.p, e.a) }
    }.getOrDefault(HashMap())

    private fun save() {
        runCatching {
            val text = json.encodeToString(entries.mapValues { (_, l) -> Entry(l.lufs, l.peak, l.album) })
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
