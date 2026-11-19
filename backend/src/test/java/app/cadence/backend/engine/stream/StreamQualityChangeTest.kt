package app.cadence.backend.engine.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Changing the streaming quality (ALAC/AAC, bit depth, sample rate) while songs are open. Tracks
 * already opened in the process must switch to the new rendition, and the song that is playing in
 * the old one must keep its audio until it ends.
 */
class StreamQualityChangeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One rendition of one track: random bytes behind its own URL; "decrypting" XORs them. */
    private class Rendition(val name: String, val lengths: List<Int>) {
        val url = "https://cdn.example/$name.mp4"
        val remote: ByteArray = Random(name.hashCode()).nextBytes(INIT + lengths.sum())
        val fetches = AtomicInteger()
        val media: HlsMedia
            get() {
                var at = INIT.toLong()
                return HlsMedia(url, 0, INIT.toLong(), lengths.map { HlsSegment(url, at, it.toLong(), 15.0, "skd://k").also { _ -> at += it } })
            }

        fun expectedStream(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(StreamLayout.of(CLEAR_INIT, TIMESCALE, media).head)
            var at = INIT
            for (len in lengths) {
                out.write(ByteArray(len) { (remote[at + it].toInt() xor 0x5A).toByte() })
                at += len
            }
            return out.toByteArray()
        }
    }

    private val renditions = ConcurrentHashMap<String, Rendition>()
    private fun rendition(track: String, variant: String, vararg lengths: Int) =
        Rendition("$track-${variant.replace('/', '_')}", lengths.toList()).also { renditions["$track|$variant"] = it }

    private val resolver = object : StreamResolver {
        @Volatile var quality = ALAC
        override val variant: String get() = quality
        override fun media(trackId: String) = renditions.getValue("$trackId|$quality").media
    }

    private val source = object : SegmentSource {
        override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
            if (offset == 0L && length == INIT.toLong()) return initWithTimescale()
            val r = renditions.values.first { it.url == url }
            r.fetches.incrementAndGet()
            return r.remote.copyOfRange(offset!!.toInt(), (offset + length!!).toInt())
        }
        override fun clearInit(originalInit: ByteArray) = CLEAR_INIT
        override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray) =
            ByteArray(encrypted.size) { (encrypted[it].toInt() xor 0x5A).toByte() }
    }

    private fun readAll(stream: ClearStream): ByteArray {
        val out = ByteArrayOutputStream()
        ClearStreamReader(stream, 0).use { reader ->
            val buffer = ByteArray(1024)
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun theDeclaredQualityIsKeptWithTheSegmentsAndSurvivesARestart() {
        val alac = rendition("t1", ALAC, 3000, 2000)
        val declaring = object : StreamResolver {
            override val variant: String get() = ALAC
            override fun media(trackId: String) = alac.media.copy(declaredBitDepth = 16, declaredSampleRate = 44_100)
        }
        val cache = StreamCache(tmp.root, declaring, source)
        readAll(cache.stream("t1")) // every segment decrypted to disk
        assertEquals(16 to 44_100, cache.declaredQuality("t1"))

        // A new process opens the track from disk without resolving it: the declaration is still known.
        val offline = object : StreamResolver {
            override val variant: String get() = ALAC
            override fun media(trackId: String): HlsMedia = throw AssertionError("resolved again instead of opening from disk")
        }
        val restarted = StreamCache(tmp.root, offline, source)
        assertNull(restarted.declaredQuality("t1"))
        restarted.stream("t1")
        assertEquals(16 to 44_100, restarted.declaredQuality("t1"))
    }

    @Test
    fun aQualityChangeOpensTheNewRenditionAndLeavesThePlayingOneIntact() {
        val alac = rendition("t1", ALAC, 5000, 3000)
        val aac = rendition("t1", AAC, 1200, 900)
        val cache = StreamCache(tmp.root, resolver, source)

        val playing = cache.stream("t1")
        assertArrayEquals(alac.expectedStream(), readAll(playing))

        resolver.quality = AAC
        val next = cache.stream("t1")
        assertTrue("the old rendition was handed back after the quality changed", next !== playing)
        assertEquals(AAC, next.variant)
        assertEquals(listOf(1200L, 900L), next.layout.segmentLengths.toList())
        assertArrayEquals(aac.expectedStream(), readAll(next))
        assertNotEquals(playing.dir, next.dir)

        // The song that was playing in ALAC still reads its own audio, without fetching it again.
        assertArrayEquals(alac.expectedStream(), readAll(playing))
        assertEquals(2, alac.fetches.get())
    }

    @Test
    fun theWindowIsPreparedAgainInTheNewQualityForTheSameSongs() {
        val alac = rendition("t1", ALAC, 4000, 4000)
        val aac = rendition("t1", AAC, 1000, 1000)
        val cache = StreamCache(tmp.root, resolver, source)

        cache.setWindow("t1", null, null, StreamConfig())
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(2, alac.fetches.get())

        resolver.quality = AAC
        cache.setWindow("t1", null, null, StreamConfig()) // the same request, other quality
        assertTrue(cache.awaitIdle(10_000))
        assertEquals("the new rendition was not prepared", 2, aac.fetches.get())
    }

    @Test
    fun trimKeepsThePlayingSongInItsOldRenditionButStillEvictsOldSongs() {
        rendition("t1", ALAC, 4000, 4000)
        rendition("t1", AAC, 1000, 1000)
        rendition("t2", AAC, 1000, 1000)
        val cache = StreamCache(tmp.root, resolver, source)
        cache.setWindow("t1", null, null, StreamConfig())
        assertTrue(cache.awaitIdle(10_000))
        val alacDir = cache.stream("t1").dir

        // Quality changes mid-song with a tiny budget: both renditions of the window song stay.
        resolver.quality = AAC
        cache.setWindow("t1", null, null, StreamConfig(cacheBytes = 100))
        assertTrue(cache.awaitIdle(10_000))
        assertTrue("the playing song's audio was deleted", File(alacDir, "0.seg").isFile && File(alacDir, "1.seg").isFile)
        val aacDir = cache.stream("t1").dir
        assertTrue(File(aacDir, "0.seg").isFile)

        // Once another song plays, the first one is no longer protected in either rendition.
        cache.setWindow("t2", null, null, StreamConfig(cacheBytes = 100))
        assertTrue(cache.awaitIdle(10_000))
        assertTrue("old song kept past the budget", !alacDir.exists() && !aacDir.exists())
    }

    private fun initWithTimescale(): ByteArray {
        fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        val mdhd = ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(TIMESCALE).putInt(0).putInt(0).array()
        return box("moov", box("trak", box("mdia", box("mdhd", mdhd))))
    }

    private companion object {
        const val INIT = 64
        const val TIMESCALE = 44100
        const val ALAC = "alac/24/0"
        const val AAC = "aac/256"
        val CLEAR_INIT = ByteArray(40) { 7 }
    }
}
