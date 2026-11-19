package app.cadence.backend.engine.stream

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decrypted `.m4a` disk cache for AAC-only tracks (those lacking `enhancedHls` in the catalogue).
 *
 * Cold misses stream immediately through [WebPlayback] while this cache fetches and decrypts the
 * progressive `.m4p` into `<trackId>.m4a` on a single background worker; subsequent plays, queue
 * transitions, and "previous" skips read the clear `.m4a` directly from disk.
 */
class AacCache(
    private val root: File,
    private val fetcher: TrackFetcher,
    private val log: (String) -> Unit = {},
) {
    fun interface TrackFetcher {
        /** Writes a complete, clear `.m4a` into [outFile] and returns true on success. */
        fun fetch(trackId: String, outFile: File): Boolean
    }

    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cadence-aac-prefetch").apply { isDaemon = true }
    }
    private val inFlight = HashSet<String>()

    private fun safeId(trackId: String): String =
        trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun fileFor(trackId: String): File = File(root, "${safeId(trackId)}.m4a")

    /** Returns the cached `.m4a` if present and non-empty (touching mtime for LRU), else null. */
    fun cachedFile(trackId: String): File? {
        if (trackId.isBlank()) return null
        val file = fileFor(trackId)
        if (!file.isFile || file.length() <= 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * Schedules background caching for [targets] (ordered by priority: current, next, previous,
     * ahead) and trims older entries beyond [cacheBytes].
     */
    fun setWindow(targets: List<String>, cacheBytes: Long) {
        val gen = generation.incrementAndGet()
        if (targets.isEmpty() || cacheBytes <= 0L) return
        val unique = targets.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        val protect = unique.toSet()
        worker.submit {
            for (id in unique) {
                if (generation.get() != gen) return@submit
                if (cachedFile(id) != null) continue
                val shouldFetch = synchronized(inFlight) { inFlight.add(id) }
                if (!shouldFetch) continue
                try {
                    fetchOne(id)
                } finally {
                    synchronized(inFlight) { inFlight.remove(id) }
                }
            }
            if (generation.get() == gen) {
                trimToBudget(cacheBytes, protect)
            }
        }
    }

    /** Synchronously fetches one track into the cache (also used by tests). */
    internal fun fetchOne(trackId: String): File? {
        cachedFile(trackId)?.let { return it }
        root.mkdirs()
        val target = fileFor(trackId)
        val tmp = File(root, "${safeId(trackId)}.m4a.tmp")
        tmp.delete()
        return try {
            val ok = fetcher.fetch(trackId, tmp)
            if (ok && tmp.isFile && tmp.length() > 0L) {
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) {
                    target.setLastModified(System.currentTimeMillis())
                    log("cached track=$trackId bytes=${target.length()}")
                    target
                } else {
                    tmp.delete()
                    null
                }
            } else {
                tmp.delete()
                null
            }
        } catch (t: Throwable) {
            tmp.delete()
            log("cache failed track=$trackId: ${t.message}")
            null
        }
    }

    internal fun trimToBudget(budgetBytes: Long, protect: Set<String> = emptySet()) {
        if (budgetBytes <= 0L || !root.isDirectory) return
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".m4a") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        val protectedNames = protect.map { "${safeId(it)}.m4a" }.toSet()
        val evictable = files
            .filter { it.name !in protectedNames }
            .sortedBy { it.lastModified() }
        for (f in evictable) {
            if (total <= budgetBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                log("evicted ${f.name} bytes=$len")
            }
        }
    }

    fun sizeOnDisk(): Long {
        if (!root.isDirectory) return 0L
        return root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    fun clear() {
        generation.incrementAndGet()
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}
