package app.cadence.backend.engine.stream

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Where a track's bytes come from and how they are made playable. The real implementation talks
 * to the CDN and the native FairPlay bridge; tests substitute their own.
 */
interface SegmentSource {
    /** The bytes of [url], or of the given byte range of it. */
    @Throws(IOException::class)
    fun fetch(url: String, offset: Long?, length: Long?): ByteArray

    /** The init segment with its protected sample entries rewritten as clear ones. */
    @Throws(IOException::class)
    fun clearInit(originalInit: ByteArray): ByteArray

    /** [encrypted] decrypted in place: same length, readable as plain fragmented MP4. */
    @Throws(IOException::class)
    fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray): ByteArray

    /**
     * Hint that [segment]'s key will be needed soon. An implementation whose keys take a network
     * round trip starts it here, without blocking, so it overlaps with the audio download
     * instead of following it.
     */
    fun prepare(trackId: String, segment: HlsSegment) {}
}

/**
 * One track's clear stream, backed by a directory holding one file per decrypted segment.
 *
 * Segments are fetched and decrypted on first use and then served from disk. Any thread may ask
 * for any segment: the player's loader reads what it is about to play while the prefetcher fills
 * in the rest, and two requests for the same segment share one download.
 */
class ClearStream internal constructor(
    val trackId: String,
    val media: HlsMedia,
    private val originalInit: ByteArray,
    val layout: StreamLayout,
    internal val dir: File,
    private val source: SegmentSource,
    /** Told when a segment could not be fetched, so a stale playlist can be resolved again. */
    private val onFetchFailed: (ClearStream) -> Unit = {},
    /** The rendition this stream holds ([StreamResolver.variant] when it was opened). */
    val variant: String = "",
) {
    val segmentCount: Int get() = media.segments.size
    private val locks = Array(segmentCount) { Any() }

    private fun file(index: Int) = File(dir, "$index.seg")

    fun isCached(index: Int): Boolean = file(index).length() == layout.segmentLengths[index]

    fun cachedSegments(): Int = (0 until segmentCount).count(::isCached)

    val isComplete: Boolean get() = cachedSegments() == segmentCount

    /**
     * How many leading segments cover [seconds] of audio: 0 for none, a negative value for the
     * whole track, otherwise enough whole segments to reach that much. Segments run a hair
     * under round numbers (14.95 s), so one is only added while more than half a second is
     * still uncovered: "30 s" is two segments, not three.
     */
    fun segmentsFor(seconds: Int): Int {
        if (seconds == 0) return 0
        if (seconds < 0) return segmentCount
        var covered = 0.0
        var count = 0
        while (count < segmentCount && covered + 0.5 < seconds) covered += media.segments[count++].durationSec
        return count
    }

    /**
     * Writes the whole clear package - the clear init head, then every segment in order - to
     * [out]: the single fragmented MP4 that the clear muxer turns into a finished `.m4a`. Missing
     * segments are fetched and decrypted the same way playback does, and segments an earlier play
     * (or download) already decrypted are reused straight from disk, which is what makes
     * downloading a track you just listened to near-instant. [onProgress] is called with
     * `(segmentsDone, total)` as each segment lands.
     */
    @Throws(IOException::class)
    fun writePackage(out: File, parallel: Int = DOWNLOAD_PARALLEL, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        out.outputStream().buffered().use { os ->
            // head = clear init (ftyp/moov) + sidx. The clear muxer reads boxes up to moov as the
            // init and skips the trailing sidx, then muxes each moof/mdat that follows.
            os.write(layout.head)
            onProgress(0, segmentCount)
            pipelined(0 until segmentCount, parallel) { index, file ->
                file.inputStream().use { it.copyTo(os) }
                onProgress(index + 1, segmentCount)
            }
        }
    }

    /**
     * Brings [indices] onto disk in order, keeping up to [parallel] downloads in flight: each
     * segment is decrypted as soon as its bytes arrive, while the next ones are still downloading,
     * instead of the network waiting for the decrypt (or the decrypt for the whole download).
     * [onEach] gets every segment in index order, cached ones included.
     */
    @Throws(IOException::class)
    fun pipelined(indices: IntRange, parallel: Int = DOWNLOAD_PARALLEL, onEach: (index: Int, file: File) -> Unit) {
        if (indices.isEmpty()) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel.coerceAtLeast(1)) { r ->
            Thread(r, "cadence-segment-fetch").apply { isDaemon = true }
        }
        try {
            val inFlight = ArrayDeque<Pair<Int, java.util.concurrent.Future<ByteArray?>>>()
            var next = indices.first
            fun schedule() {
                while (inFlight.size < parallel.coerceAtLeast(1) && next <= indices.last) {
                    val index = next++
                    // Null means it was already on disk when the download would have started.
                    inFlight += index to pool.submit<ByteArray?> { if (isCached(index)) null else fetchEncrypted(index) }
                }
            }
            schedule()
            while (inFlight.isNotEmpty()) {
                val (index, pending) = inFlight.removeFirst()
                val encrypted = try {
                    pending.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause as? IOException) ?: IOException("Segment ${index + 1} of $trackId failed", e.cause)
                }
                // Keep the network busy while this one is decrypted.
                schedule()
                val file = synchronized(locks[index]) {
                    if (isCached(index)) file(index) else store(index, encrypted ?: fetchEncrypted(index))
                }
                onEach(index, file)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Download of $trackId interrupted")
        } finally {
            pool.shutdownNow()
        }
    }

    /** The clear bytes of segment [index] on disk, downloading and decrypting them if needed. */
    @Throws(IOException::class)
    fun segment(index: Int): File = synchronized(locks[index]) {
        val file = file(index)
        if (file.length() == layout.segmentLengths[index]) return file
        store(index, fetchEncrypted(index))
    }

    /** The encrypted bytes of segment [index], checked against the size the playlist promised. */
    private fun fetchEncrypted(index: Int): ByteArray {
        val segment = media.segments[index]
        if (segment.url.isEmpty()) {
            // Opened from disk as a complete track, yet this file is gone: resolve it afresh.
            onFetchFailed(this)
            throw IOException("Segment ${index + 1} of $trackId is no longer cached")
        }
        val encrypted = try {
            source.fetch(segment.url, segment.offset, segment.length)
        } catch (e: IOException) {
            onFetchFailed(this)
            throw e
        }
        if (encrypted.size.toLong() != segment.length) {
            onFetchFailed(this)
            throw IOException("Segment ${index + 1} of $trackId is ${encrypted.size} bytes; the playlist says ${segment.length}")
        }
        return encrypted
    }

    /** Decrypts [encrypted] (segment [index]) and stores it atomically. Caller holds its lock. */
    private fun store(index: Int, encrypted: ByteArray): File {
        val segment = media.segments[index]
        val file = file(index)
        val clear = source.clear(trackId, segment, originalInit, encrypted)
        // The layout was published to the player on the promise that sizes do not change.
        if (clear.size != encrypted.size) throw IOException("Segment ${index + 1} of $trackId changed size when decrypted")
        dir.mkdirs()
        val part = File(dir, "$index.part")
        part.writeBytes(clear)
        if (!part.renameTo(file)) {
            part.delete()
            throw IOException("Cannot store segment ${index + 1} of $trackId")
        }
        return file
    }

    companion object {
        /** Segment downloads kept in flight per track while earlier ones are decrypted. */
        const val DOWNLOAD_PARALLEL = 2
    }
}

/**
 * Sequential reader over a [ClearStream] from any starting byte, as the player's data source
 * needs. Reading into a segment that is not on disk yet blocks until it has been fetched.
 */
class ClearStreamReader(private val stream: ClearStream, private var position: Long) : Closeable {
    private var open: RandomAccessFile? = null
    private var openIndex = -1

    /** Bytes left from the current position to the end of the stream. */
    val remaining: Long get() = (stream.layout.totalSize - position).coerceAtLeast(0)

    /** Reads up to [length] bytes; returns how many were read, or -1 at the end of the stream. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val layout = stream.layout
        if (position >= layout.totalSize) return -1
        if (position < layout.head.size) {
            val count = minOf(length.toLong(), layout.head.size - position).toInt()
            System.arraycopy(layout.head, position.toInt(), buffer, offset, count)
            position += count
            return count
        }
        val index = layout.segmentAt(position)
        val within = position - layout.segmentOffsets[index]
        if (index != openIndex) {
            open?.close()
            open = null
            openIndex = -1
            open = RandomAccessFile(stream.segment(index), "r")
            openIndex = index
        }
        val file = open!!
        file.seek(within)
        val count = file.read(buffer, offset, minOf(length.toLong(), layout.segmentLengths[index] - within).toInt())
        if (count <= 0) throw IOException("Segment ${index + 1} of ${stream.trackId} ended early")
        position += count
        return count
    }

    override fun close() {
        open?.close()
        open = null
        openIndex = -1
    }
}
