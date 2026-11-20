package app.cadence.backend.engine.stream

import app.cadence.backend.engine.media.Mp4Integrity
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Resolves a track to its current media playlist. */
fun interface StreamResolver {
    @Throws(IOException::class)
    fun media(trackId: String): HlsMedia

    /**
     * Identifies which rendition of a track [media] would pick right now (the user's quality
     * preferences). Audio cached under another value is not reused without asking again.
     */
    val variant: String get() = ""
}

/** How much audio to prepare around the playing track, and how much disk it may use. */
data class StreamConfig(
    /** Seconds of the next track decrypted ahead of time; 0 = none, [WHOLE] = all of it. */
    val nextSeconds: Int = 30,
    /** Seconds of the previous track kept ready, so "back" starts at once; same encoding. */
    val previousSeconds: Int = 15,
    /** Disk budget for decrypted audio; older tracks are dropped beyond it. */
    val cacheBytes: Long = 1L shl 30,
    /**
     * Whole songs kept decrypted after the playing one, in queue order, until they add up to
     * this many bytes; 0 = only the opening above. The song that crosses the mark is finished.
     */
    val aheadBytes: Long = 0,
    /** The same for the songs before the playing one. */
    val behindBytes: Long = 0,
) {
    companion object {
        const val WHOLE = -1
    }
}

/**
 * The decrypted streaming cache: one [ClearStream] per track, plus the prefetcher that keeps the
 * playing track complete and its neighbours ready.
 *
 * Gapless transitions need the first bytes of the next track to be on disk before the current
 * one ends, and an instant "previous" needs the same for the track before. [setWindow] is told
 * which tracks those are whenever the queue position changes.
 */
class StreamCache(
    private val root: File,
    private val resolver: StreamResolver,
    private val source: SegmentSource,
    private val log: (String) -> Unit = {},
) {
    /** Open streams by [key]: a track can be open in two renditions while the quality changes. */
    private val streams = HashMap<String, ClearStream>()
    private val openLocks = ConcurrentHashMap<String, Any>()
    private val generation = AtomicInteger()
    @Volatile private var pinned: Set<String> = emptySet()
    @Volatile private var window: List<Any?> = emptyList()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cadence-stream-prefetch").apply { isDaemon = true }
    }

    private fun key(trackId: String, variant: String) = "$trackId\u0000$variant"

    /**
     * Each rendition of a track has its own directory, so a stream opened after a quality change
     * never deletes or overwrites the segments of the one still playing.
     */
    private fun dirFor(trackId: String, variant: String = resolver.variant): File {
        val safe = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        return File(root, if (variant.isEmpty()) safe(trackId) else safe(trackId) + "@" + safe(variant))
    }

    private companion object {
        /** One second of audio: resolves to exactly the first segment. */
        const val FIRST_SEGMENT = 1
        const val HEAD = "head"
        const val INDEX = "index"
        const val DECLARED = "declared"
    }

    private val declared = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val spatial = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Bit depth and sample rate Apple's master declares for [trackId] in the current quality, once
     * the track has been opened in this process, from the network or from disk.
     */
    fun declaredQuality(trackId: String): Pair<Int, Int>? = declared[key(trackId, resolver.variant)]

    /** Whether [trackId] is streaming a spatial/Dolby Atmos rendition in the current variant. */
    fun isSpatial(trackId: String): Boolean = spatial[key(trackId, resolver.variant)] == true

    /** The stream for [trackId], resolving its playlist and init segment on first use. Blocks. */
    @Throws(IOException::class)
    fun stream(trackId: String): ClearStream {
        // Read once: the quality setting can change while this runs.
        val variant = resolver.variant
        val key = key(trackId, variant)
        synchronized(streams) { streams[key] }?.let { return it }
        return synchronized(openLocks.computeIfAbsent(trackId) { Any() }) {
            synchronized(streams) { streams[key] }
                ?: open(trackId, variant).also { synchronized(streams) { streams[key] = it } }
        }
    }

    private fun open(trackId: String, variant: String): ClearStream {
        openFromDisk(trackId, variant)?.let { return it }
        val media = resolver.media(trackId)
        val originalInit = source.fetch(media.initUrl, media.initOffset, media.initLength)
        val timescale = Mp4Integrity.mediaTimescale(originalInit)
        if (timescale <= 0) throw IOException("Init segment of $trackId declares no timescale")
        val layout = StreamLayout.of(source.clearInit(originalInit), timescale, media)

        // Segments cached by an earlier run are only valid for the same layout of the track.
        val dir = dirFor(trackId, variant)
        val marker = File(dir, "layout")
        val key = layoutKey(layout)
        if (dir.exists() && runCatching { marker.readText() }.getOrNull() != key) dir.deleteRecursively()
        dir.mkdirs()
        marker.writeText(key)
        // Enough to open the track again without the network once all its segments are here.
        File(dir, HEAD).writeBytes(layout.head)
        File(dir, INDEX).writeText(
            variant + "\n" + media.segments.joinToString("\n") { "${it.length} ${it.durationSec}" },
        )
        if (media.declaredBitDepth > 0 || media.declaredSampleRate > 0 || media.declaredSpatial) {
            File(dir, DECLARED).writeText("${media.declaredBitDepth} ${media.declaredSampleRate} ${if (media.declaredSpatial) 1 else 0}")
            declared[key(trackId, variant)] = media.declaredBitDepth to media.declaredSampleRate
            if (media.declaredSpatial) spatial[key(trackId, variant)] = true
        }
        dir.setLastModified(System.currentTimeMillis())

        // Each key costs a round trip to the licence server; ask for them now, while the audio
        // itself is still to be downloaded. The first segment and the rest use different keys.
        media.segments.distinctBy { it.keyUri }.forEach { source.prepare(trackId, it) }

        val stream = ClearStream(trackId, media, originalInit, layout, dir, source, ::forget, variant)
        log("open $trackId segments=${stream.segmentCount} cached=${stream.cachedSegments()} bytes=${layout.totalSize} duration=${"%.1f".format(media.durationSec)}s")
        return stream
    }

    /**
     * A track whose every segment is already decrypted on disk needs neither the network nor a
     * key: it opens from what an earlier play stored. Returns null when anything is missing or
     * the quality preferences changed since, in which case the track is resolved normally.
     */
    private fun openFromDisk(trackId: String, variant: String): ClearStream? {
        val dir = dirFor(trackId, variant)
        val head = File(dir, HEAD).takeIf { it.isFile }?.readBytes() ?: return null
        val lines = runCatching { File(dir, INDEX).readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != variant) return null
        val segments = runCatching {
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val (length, duration) = line.split(' ')
                HlsSegment(url = "", offset = 0, length = length.toLong(), durationSec = duration.toDouble(), keyUri = "")
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val layout = StreamLayout(head, LongArray(segments.size) { segments[it].length })
        val stream = ClearStream(trackId, HlsMedia("", null, null, segments), ByteArray(0), layout, dir, source, ::forget, variant)
        if (!stream.isComplete) return null
        runCatching { File(dir, DECLARED).readText().trim().split(' ').map(String::toInt) }.getOrNull()
            ?.takeIf { it.size >= 2 }?.let { parts ->
                declared[key(trackId, variant)] = parts[0] to parts[1]
                if (parts.getOrNull(2) == 1) spatial[key(trackId, variant)] = true
            }
        dir.setLastModified(System.currentTimeMillis())
        log("open $trackId from disk segments=${stream.segmentCount} bytes=${layout.totalSize}")
        return stream
    }

    /** Drops a stream whose playlist stopped working; the next request resolves it again. */
    private fun forget(stream: ClearStream) {
        val key = key(stream.trackId, stream.variant)
        synchronized(streams) { if (streams[key] === stream) streams.remove(key) }
        log("forget ${stream.trackId} (fetch failed; will resolve again)")
    }

    private fun layoutKey(layout: StreamLayout): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(layout.head)
        layout.segmentLengths.forEach { digest.update(it.toString().toByteArray()); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Declares the tracks around the playback position. In the background this completes
     * [current], prepares the opening of [next] and [previous] as [config] asks, and then trims
     * the cache. A newer call supersedes one still in progress.
     *
     * [ahead] and [behind] are the queue beyond the playing track, nearest first (so they start
     * with [next] and [previous]). Once the above is done they are downloaded whole, ahead
     * first, up to [StreamConfig.aheadBytes] and [StreamConfig.behindBytes], and never past the
     * cache budget: skipping several songs either way then finds them already on this device.
     */
    fun setWindow(
        current: String?, next: String?, previous: String?, config: StreamConfig,
        ahead: List<String> = emptyList(), behind: List<String> = emptyList(),
    ) {
        // The rendition is part of the request: after a quality change the same tracks must be
        // prepared again in the new one, not skipped as already requested.
        val requested = listOf(current, next, previous, config, ahead, behind, resolver.variant)
        if (requested == window) return
        window = requested
        pinned = setOfNotNull(current, next, previous)
        val mine = generation.incrementAndGet()
        // Nothing may compete with the first segment of the current track: it is what the
        // listener is waiting for, and on a slow link a neighbour's download would delay it.
        // After that the next track's opening comes first (a skip can happen at any moment),
        // then the rest of the current track, which is only needed as playback reaches it.
        val steps = listOfNotNull(
            current?.let { it to FIRST_SEGMENT },
            next?.let { it to config.nextSeconds },
            current?.let { it to StreamConfig.WHOLE },
            previous?.let { it to config.previousSeconds },
        )
        worker.execute {
            for ((trackId, seconds) in steps) {
                if (seconds == 0) continue
                try {
                    val stream = stream(trackId)
                    val wanted = stream.segmentsFor(seconds)
                    for (index in 0 until wanted) {
                        if (generation.get() != mine) return@execute
                        stream.segment(index)
                    }
                    log("ready $trackId segments=$wanted/${stream.segmentCount}")
                } catch (e: Exception) {
                    log("prefetch $trackId failed: ${e.message}")
                }
            }
            if (generation.get() != mine) return@execute
            // Songs already held inside the window stay through the trim that makes room for it.
            pinned = pinned + held(ahead, config.aheadBytes) + held(behind, config.behindBytes)
            trim(config.cacheBytes)
            if (fill(ahead, config.aheadBytes, config.cacheBytes, mine)) fill(behind, config.behindBytes, config.cacheBytes, mine)
            if (generation.get() == mine) trim(config.cacheBytes)
        }
    }

    /** The leading tracks of [ids] that the window of [budget] bytes covers, by what is on disk. */
    private fun held(ids: List<String>, budget: Long): Set<String> {
        val kept = HashSet<String>()
        var bytes = 0L
        for (id in ids) {
            if (bytes >= budget) break
            kept += id
            bytes += sizeOf(dirFor(id))
        }
        return kept
    }

    /**
     * Downloads [ids] whole, in order, until they hold [budget] bytes or the cache reaches
     * [cap]. False when a newer window took over or the cache is full, so nothing more should
     * be fetched for this one.
     */
    private fun fill(ids: List<String>, budget: Long, cap: Long, mine: Int): Boolean {
        var bytes = 0L
        for (id in ids) {
            if (bytes >= budget) break
            try {
                val stream = stream(id)
                for (index in 0 until stream.segmentCount) {
                    if (generation.get() != mine) return false
                    if (!stream.isCached(index) && sizeOnDisk() >= cap) {
                        trim(cap)
                        if (sizeOnDisk() >= cap) {
                            log("window stopped at the cache limit ($id)")
                            return false
                        }
                    }
                    bytes += stream.segment(index).length()
                }
                log("window $id whole, direction holds $bytes of $budget bytes")
            } catch (e: Exception) {
                // One unavailable song must not leave the ones after it out of the window.
                log("window $id failed: ${e.message}")
            }
        }
        return true
    }

    /** Blocks until the prefetch work queued so far has run; false if [timeoutMs] passes first. */
    fun awaitIdle(timeoutMs: Long): Boolean = runCatching {
        worker.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.isSuccess

    /** Deletes least-recently-used tracks until the cache fits [budget]; window tracks stay. */
    fun trim(budget: Long) {
        val dirs = root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        var total = dirs.sumOf(::sizeOf)
        if (total <= budget) return
        val window = pinned
        // A window track stays in every rendition it is open in: after a quality change the song
        // that is playing is still read from its old directory until it ends.
        val keep = window.map { dirFor(it).name }.toSet() +
            synchronized(streams) { streams.values.filter { it.trackId in window }.map { it.dir.name } }
        for (dir in dirs.filter { it.name !in keep }.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            val size = sizeOf(dir)
            synchronized(streams) { streams.values.removeAll { it.dir.name == dir.name } }
            dir.deleteRecursively()
            total -= size
            log("evict ${dir.name} freed=$size")
        }
    }

    private fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Bytes of decrypted audio currently on disk. */
    fun sizeOnDisk(): Long = root.listFiles { f -> f.isDirectory }?.sumOf(::sizeOf) ?: 0L

    /** Removes every cached track. */
    fun clear() {
        generation.incrementAndGet()
        window = emptyList()
        synchronized(streams) { streams.clear() }
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}
