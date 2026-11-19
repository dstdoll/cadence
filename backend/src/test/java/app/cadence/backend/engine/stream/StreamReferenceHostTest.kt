package app.cadence.backend.engine.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Assembles a real track's clear stream through the Kotlin path (playlist parser, layout, cache,
 * reader) and compares it with the reference built by the native tool (`inspect clear`), which is
 * itself checked against ffmpeg. Skipped unless the files are given:
 *
 *   STREAM_PKG        encrypted package staged by HlsPackageProbeTest (init + segments)
 *   STREAM_PLAYLIST   the media playlist saved next to it (`<pkg>.m3u8`), with its `<pkg>.url`
 *   STREAM_REFERENCE  the clear stream written by `inspect clear <pkg> <out> replay <m4a>`
 */
class StreamReferenceHostTest {
    @Test
    fun kotlinStreamEqualsTheNativeReference() {
        val pkg = System.getenv("STREAM_PKG")?.let(::File)?.takeIf { it.isFile } ?: return
        val playlist = System.getenv("STREAM_PLAYLIST")?.let(::File)?.takeIf { it.isFile } ?: return
        val reference = System.getenv("STREAM_REFERENCE")?.let(::File)?.takeIf { it.isFile }?.readBytes() ?: return
        val baseUrl = File(pkg.path + ".url").readText().trim()
        val remote = pkg.readBytes()

        val media = HlsMediaParser.parse(playlist.readText(), baseUrl)
        println("[ref] segments=${media.segments.size} lengths=${media.segments.map { it.length }} keys=${media.segments.map { it.keyUri.substringAfterLast('/') }} duration=${media.durationSec}")
        // The staged package is the remote media file from byte 0, so playlist ranges index it.
        assertEquals(remote.size.toLong(), media.segments.last().let { it.offset + it.length })

        val segmentBytes = media.segments.sumOf { it.length }
        val headLength = (reference.size - segmentBytes).toInt()
        val sidxLength = 32 + 12 * media.segments.size
        var fetches = 0
        val source = object : SegmentSource {
            override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
                fetches++
                return remote.copyOfRange(offset!!.toInt(), (offset + length!!).toInt())
            }
            // The native results, sliced out of the reference: init rewrite and segment decrypt.
            override fun clearInit(originalInit: ByteArray) = reference.copyOfRange(0, headLength - sidxLength)
            override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray): ByteArray {
                val index = media.segments.indexOf(segment)
                val at = headLength + media.segments.take(index).sumOf { it.length }.toInt()
                return reference.copyOfRange(at, at + encrypted.size)
            }
        }

        val tmp = TemporaryFolder().apply { create() }
        try {
            val stream = StreamCache(tmp.root, { media }, source).stream("reference")
            println("[ref] head=${stream.layout.head.size} total=${stream.layout.totalSize} reference=${reference.size}")
            assertEquals(reference.size.toLong(), stream.layout.totalSize)
            // The Kotlin-built head (clear init + sidx) must equal the native one, sidx included.
            assertArrayEquals(reference.copyOfRange(0, headLength), stream.layout.head)

            fun read(from: Long, count: Int): ByteArray {
                val out = ByteArrayOutputStream()
                ClearStreamReader(stream, from).use { reader ->
                    val buffer = ByteArray(64 * 1024)
                    while (out.size() < count) {
                        val n = reader.read(buffer, 0, minOf(buffer.size, count - out.size()))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                return out.toByteArray()
            }
            assertArrayEquals(reference, read(0, reference.size))
            val random = Random(1)
            repeat(200) {
                val from = random.nextLong(reference.size.toLong())
                val count = random.nextInt(1, 200_000).coerceAtMost((reference.size - from).toInt())
                assertArrayEquals("from $from", reference.copyOfRange(from.toInt(), from.toInt() + count), read(from, count))
            }
            // init + one fetch per segment, however many times and from wherever it was read.
            assertEquals(1 + media.segments.size, fetches)
            println("[ref] whole stream and 200 random ranges equal the reference; fetches=$fetches")
        } finally {
            tmp.delete()
        }
    }
}
