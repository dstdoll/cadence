package app.cadence.backend.engine.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val PREFETCH_KEY = "skd://itunes.apple.com/P000000000/s1/e1"
private const val CONTENT_KEY = "skd://itunes.apple.com/p385192628/c23"

/** A real Apple playlist with its URLs replaced: three key lines per key change, byte ranges. */
private fun playlist(lastRange: String = "1177792@7503184") = """
    #EXTM3U
    #EXT-X-TARGETDURATION:15
    #EXT-X-VERSION:7
    #EXT-X-MEDIA-SEQUENCE:0
    #EXT-X-PLAYLIST-TYPE:VOD
    #EXT-X-INDEPENDENT-SEGMENTS
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="$PREFETCH_KEY",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAAOHBzc2g=",KEYFORMAT="com.microsoft.playready",KEYFORMATVERSIONS="1"
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAAOHBzc2h=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
    #EXT-X-MAP:URI="P1_A1_audio_en_gr1411_m.mp4",BYTERANGE="1036@0"
    #EXTINF:14.95365,
    #EXT-X-BYTERANGE:2616851@1036
    P1_A1_audio_en_gr1411_m.mp4
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="$CONTENT_KEY",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,BBBBOHBzc2g=",KEYFORMAT="com.microsoft.playready",KEYFORMATVERSIONS="1"
    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,BBBBOHBzc2h=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
    #EXTINF:14.95365,
    #EXT-X-BYTERANGE:2336869@2617887
    P1_A1_audio_en_gr1411_m.mp4
    #EXTINF:14.95365,
    #EXT-X-BYTERANGE:2548428
    P1_A1_audio_en_gr1411_m.mp4
    #EXTINF:7.41154,
    #EXT-X-BYTERANGE:$lastRange
    P1_A1_audio_en_gr1411_m.mp4
    #EXT-X-ENDLIST
""".trimIndent()

private const val BASE = "https://cdn.example/itunes-assets/HLSMusic/v4/ab/cd/P1_default.m3u8"
private const val MEDIA_URL = "https://cdn.example/itunes-assets/HLSMusic/v4/ab/cd/P1_A1_audio_en_gr1411_m.mp4"

class HlsMediaParserTest {
    @Test
    fun readsSegmentsRangesAndTheFairPlayKeyInForce() {
        val media = HlsMediaParser.parse(playlist(), BASE)
        assertEquals(MEDIA_URL, media.initUrl)
        assertEquals(0L, media.initOffset)
        assertEquals(1036L, media.initLength)
        assertEquals(4, media.segments.size)
        assertTrue(media.segments.all { it.url == MEDIA_URL })
        assertEquals(listOf(2616851L, 2336869L, 2548428L, 1177792L), media.segments.map { it.length })
        // The third range has no offset: it continues where the second one ended.
        assertEquals(listOf(1036L, 2617887L, 4954756L, 7503184L), media.segments.map { it.offset })
        // Only the FairPlay line of each key group counts; the first segment is under the prefetch key.
        assertEquals(listOf(PREFETCH_KEY, CONTENT_KEY, CONTENT_KEY, CONTENT_KEY), media.segments.map { it.keyUri })
        assertEquals(52.27249, media.durationSec, 1e-9)
    }

    @Test
    fun rejectsPlaylistsItCannotStream() {
        assertThrows(IOException::class.java) { HlsMediaParser.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8", BASE) }
        val noRange = playlist().lines().filterNot { it.startsWith("#EXT-X-BYTERANGE") }.joinToString("\n")
        assertThrows(IOException::class.java) { HlsMediaParser.parse(noRange, BASE) }
        val noFairPlay = playlist().lines().filterNot { it.contains("streamingkeydelivery") }.joinToString("\n")
        assertThrows(IOException::class.java) { HlsMediaParser.parse(noFairPlay, BASE) }
    }
}

class StreamLayoutTest {
    private val media = HlsMediaParser.parse(playlist(), BASE)

    @Test
    fun indexMatchesTheNativeImplementationByteForByte() {
        // Produced by cadence-container's build_sidx for this same track (inspect clear).
        val native = "000000507369647800000000000000010000ac440000000000000000000000040027ee13000a1000900000000023a865" +
            "000a1000900000000026e2cc000a1000900000000011f8c00004fcc190000000"
        val layout = StreamLayout.of(ByteArray(842), 44100, media)
        val sidx = layout.head.copyOfRange(842, layout.head.size)
        assertEquals(native, sidx.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun mapsPositionsToSegments() {
        val layout = StreamLayout.of(ByteArray(842), 44100, media)
        val head = 842 + 80L
        assertEquals(head + 2616851 + 2336869 + 2548428 + 1177792, layout.totalSize)
        assertEquals(listOf(head, head + 2616851, head + 4953720, head + 7502148), layout.segmentOffsets.toList())
        assertEquals(-1, layout.segmentAt(0))
        assertEquals(-1, layout.segmentAt(head - 1))
        assertEquals(0, layout.segmentAt(head))
        assertEquals(0, layout.segmentAt(head + 2616850))
        assertEquals(1, layout.segmentAt(head + 2616851))
        assertEquals(3, layout.segmentAt(layout.totalSize - 1))
        assertEquals(-1, layout.segmentAt(layout.totalSize))
    }
}

/** A CDN and a decryptor in memory: segments are random bytes, "decryption" XORs them. */
private class FakeSource(val segmentLengths: List<Int>, name: String = "t", private val initLength: Int = 64) : SegmentSource {
    val url = "https://cdn.example/$name.mp4"
    private val random = Random(7)
    val remote: ByteArray = random.nextBytes(initLength + segmentLengths.sum())
    val clearInitBytes: ByteArray = random.nextBytes(40)
    val fetches = AtomicInteger()
    val clears = AtomicInteger()
    @Volatile var gate: CountDownLatch? = null
    @Volatile var failFetches = false
    @Volatile var shrinkOnClear = false

    val media: HlsMedia
        get() {
            var at = initLength.toLong()
            val segments = segmentLengths.mapIndexed { i, len ->
                HlsSegment(url, at, len.toLong(), 15.0, if (i == 0) PREFETCH_KEY else CONTENT_KEY).also { at += len }
            }
            return HlsMedia(url, 0, initLength.toLong(), segments)
        }

    /** What the whole clear stream must read as, given [timescale] as in the fake init. */
    fun expectedStream(): ByteArray {
        val layout = StreamLayout.of(clearInitBytes, TIMESCALE, media)
        val out = ByteArrayOutputStream()
        out.write(layout.head)
        var at = initLength
        for (len in segmentLengths) {
            out.write(ByteArray(len) { (remote[at + it].toInt() xor 0x5A).toByte() })
            at += len
        }
        return out.toByteArray()
    }

    override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
        if (offset == 0L && length == initLength.toLong()) return initWithTimescale()
        gate?.await(5, TimeUnit.SECONDS)
        fetches.incrementAndGet()
        if (failFetches) throw IOException("HTTP 403 from cdn.example")
        return remote.copyOfRange(offset!!.toInt(), (offset + length!!).toInt())
    }

    override fun clearInit(originalInit: ByteArray): ByteArray = clearInitBytes

    override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray): ByteArray {
        clears.incrementAndGet()
        val out = ByteArray(encrypted.size) { (encrypted[it].toInt() xor 0x5A).toByte() }
        return if (shrinkOnClear) out.copyOf(out.size - 1) else out
    }

    /** The smallest init the cache can read a timescale from: moov/trak/mdia/mdhd. */
    private fun initWithTimescale(): ByteArray {
        fun box(type: String, body: ByteArray) =
            java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        val mdhd = java.nio.ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(TIMESCALE).putInt(0).putInt(0).array()
        return box("moov", box("trak", box("mdia", box("mdhd", mdhd))))
    }

    companion object {
        const val TIMESCALE = 44100
    }
}

class ClearStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(source: FakeSource, resolves: AtomicInteger = AtomicInteger()) =
        StreamCache(tmp.root, { resolves.incrementAndGet(); source.media }, source)

    private fun readAll(stream: ClearStream, from: Long = 0, chunk: Int = 777): ByteArray {
        val out = ByteArrayOutputStream()
        ClearStreamReader(stream, from).use { reader ->
            val buffer = ByteArray(chunk)
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheWholeStreamAndFetchesEachSegmentOnce() {
        val source = FakeSource(listOf(5000, 3000, 4096, 1))
        val stream = cache(source).stream("t1")
        val expected = source.expectedStream()
        assertEquals(expected.size.toLong(), stream.layout.totalSize)
        assertArrayEquals(expected, readAll(stream))
        assertArrayEquals(expected, readAll(stream, chunk = 1 shl 16))
        assertEquals(4, source.fetches.get())
        assertTrue(stream.isComplete)
    }

    @Test
    fun readsFromAnyPositionFetchingOnlyWhatItTouches() {
        val source = FakeSource(listOf(5000, 3000, 4096, 2000))
        val stream = cache(source).stream("t1")
        val expected = source.expectedStream()
        // Start inside the third segment, as a seek does: earlier segments are never fetched.
        val from = stream.layout.segmentOffsets[2] + 100
        assertArrayEquals(expected.copyOfRange(from.toInt(), expected.size), readAll(stream, from))
        assertEquals(2, source.fetches.get())
        assertEquals(listOf(false, false, true, true), (0 until 4).map(stream::isCached))
        for (position in listOf(0L, 39L, 40L, 119L, 120L, 121L, stream.layout.totalSize - 1)) {
            assertArrayEquals("from $position", expected.copyOfRange(position.toInt(), expected.size), readAll(stream, position))
        }
        assertEquals(0, ClearStreamReader(stream, stream.layout.totalSize).use { it.remaining }.toInt())
    }

    @Test
    fun concurrentRequestsForOneSegmentShareOneDownload() {
        val source = FakeSource(listOf(4000, 4000))
        val stream = cache(source).stream("t1")
        source.gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map { pool.submit<Long> { stream.segment(1).length() } }
        Thread.sleep(150) // let every thread reach the segment lock
        source.gate!!.countDown()
        assertTrue(results.all { it.get(5, TimeUnit.SECONDS) == 4000L })
        pool.shutdown()
        assertEquals(1, source.fetches.get())
        assertEquals(1, source.clears.get())
    }

    @Test
    fun aCompleteTrackReopensFromDiskWithNoNetwork() {
        val source = FakeSource(listOf(5000, 3000))
        readAll(cache(source).stream("t1"))
        assertEquals(2, source.fetches.get())
        // A new process with no connectivity at all: resolving or fetching would throw.
        val offline = StreamCache(tmp.root, { throw IOException("no network") }, object : SegmentSource {
            override fun fetch(url: String, offset: Long?, length: Long?) = throw IOException("no network")
            override fun clearInit(originalInit: ByteArray) = throw IOException("no network")
            override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray) = throw IOException("no key")
        })
        val stream = offline.stream("t1")
        assertTrue(stream.isComplete)
        assertArrayEquals(source.expectedStream(), readAll(stream))
        assertEquals(30.0, stream.media.durationSec, 0.0)
    }

    @Test
    fun aPartlyCachedTrackIsResolvedAgainAndKeepsItsSegments() {
        val source = FakeSource(listOf(5000, 3000, 2000))
        val resolves = AtomicInteger()
        cache(source, resolves).stream("t1").segment(0)
        // Not complete, so a new process must resolve it; the segment already stored is reused.
        val stream = cache(source, resolves).stream("t1")
        assertEquals(2, resolves.get())
        assertEquals(listOf(true, false, false), (0 until 3).map(stream::isCached))
        assertArrayEquals(source.expectedStream(), readAll(stream))
        assertEquals(3, source.fetches.get())
    }

    @Test
    fun cachedAudioOfAnotherQualityIsNotReused() {
        val source = FakeSource(listOf(5000, 3000))
        fun cacheAt(quality: String, from: FakeSource) = StreamCache(tmp.root, object : StreamResolver {
            override fun media(trackId: String) = from.media
            override val variant get() = quality
        }, from)
        readAll(cacheAt("alac/24/0", source).stream("t1"))
        // The user lowered the quality: the track resolves to another rendition, and the complete
        // copy on disk must not be played in its place.
        val other = FakeSource(listOf(4000, 2500))
        val stream = cacheAt("alac/16/48000", other).stream("t1")
        assertEquals(0, stream.cachedSegments())
        assertArrayEquals(other.expectedStream(), readAll(stream))
    }

    @Test
    fun aDownloadNamesItsOwnQualityWithoutTouchingTheCachesDefault() {
        // The cache's own resolver is "alac/24/0"; downloads of the same song ask for other qualities through
        // `stream(id, using)`. Each gets its own rendition, and none reads the default resolver.
        val sources = mapOf("default" to FakeSource(listOf(5000, 3000), "default"), "aac128" to FakeSource(listOf(1200, 800, 700), "aac128"), "aac64" to FakeSource(listOf(600, 400, 300, 200), "aac64"))
        val bySource = object : SegmentSource {
            private fun at(url: String) = sources.values.first { it.url == url }
            override fun fetch(url: String, offset: Long?, length: Long?) = at(url).fetch(url, offset, length)
            override fun clearInit(originalInit: ByteArray) = ByteArray(40)
            override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray) = at(segment.url).clear(trackId, segment, originalInit, encrypted)
        }
        val defaultResolves = AtomicInteger()
        val cache = StreamCache(tmp.root, object : StreamResolver {
            override fun media(trackId: String): HlsMedia { defaultResolves.incrementAndGet(); return sources.getValue("default").media }
            override val variant get() = "alac/24/0"
        }, bySource)
        fun resolverFor(quality: String, key: String) = object : StreamResolver {
            override fun media(trackId: String) = sources.getValue(key).media
            override val variant get() = quality
        }
        val s128 = cache.stream("t1", resolverFor("aac/128", "aac128"))
        val s64 = cache.stream("t1", resolverFor("aac/64", "aac64"))
        assertEquals(listOf(3, 4), listOf(s128.segmentCount, s64.segmentCount))
        assertEquals("the default resolver was never consulted", 0, defaultResolves.get())
        // The same quality again is a hit on its own entry (same instance) and never the other quality's.
        assertTrue(cache.stream("t1", resolverFor("aac/128", "aac128")) === s128)
        assertTrue(cache.stream("t1", resolverFor("aac/64", "aac64")) === s64)
        assertTrue(s128 !== s64)
        // The default, asked for the same song, is a third, separate entry.
        assertEquals(2, cache.stream("t1").segmentCount)
        assertEquals(1, defaultResolves.get())
    }

    @Test
    fun aFailedFetchSurfacesAndTheNextRequestResolvesAgain() {
        val source = FakeSource(listOf(5000, 3000))
        val resolves = AtomicInteger()
        val cache = cache(source, resolves)
        val stream = cache.stream("t1")
        source.failFetches = true
        assertThrows(IOException::class.java) { stream.segment(0) }
        assertFalse(stream.isCached(0))
        // The playlist may have expired: the cache drops the stream and resolves a fresh one.
        source.failFetches = false
        val fresh = cache.stream("t1")
        assertTrue(fresh !== stream)
        assertEquals(2, resolves.get())
        assertArrayEquals(source.expectedStream(), readAll(fresh))
    }

    @Test
    fun roundSecondsMapToWholeSegmentsOfARealPlaylist() {
        // Apple's segments last 14.95365 s: "30 s" must mean two of them, not three.
        val media = HlsMediaParser.parse(playlist(), BASE)
        val stream = ClearStream("t", media, ByteArray(0), StreamLayout.of(ByteArray(8), 44100, media), tmp.root, FakeSource(listOf(1)))
        assertEquals(listOf(0, 1, 2, 4, 4, 4), listOf(0, 15, 30, 60, 600, StreamConfig.WHOLE).map(stream::segmentsFor))
    }

    @Test
    fun openingATrackAsksForEachOfItsKeysOnce() {
        val prepared = java.util.Collections.synchronizedList(ArrayList<String>())
        val source = FakeSource(listOf(100, 100, 100, 100))
        val recording = object : SegmentSource by source {
            override fun prepare(trackId: String, segment: HlsSegment) { prepared += segment.keyUri }
        }
        StreamCache(tmp.root, { source.media }, recording).stream("t1")
        assertEquals(listOf(PREFETCH_KEY, CONTENT_KEY), prepared.toList())
        assertEquals("opening must not download audio", 0, source.fetches.get())
    }

    @Test
    fun aSegmentThatChangesSizeIsRejectedNotStored() {
        val source = FakeSource(listOf(5000)).apply { shrinkOnClear = true }
        val stream = cache(source).stream("t1")
        assertThrows(IOException::class.java) { stream.segment(0) }
        assertFalse(stream.isCached(0))
    }

    @Test
    fun writePackageAssemblesTheWholeClearStreamForDownloading() {
        // The streaming download writes init + every clear segment; that file is what the native
        // clear-mux turns into a .m4a. It must be byte-for-byte the stream the player reads.
        val source = FakeSource(listOf(5000, 3000, 4096, 1))
        val stream = cache(source).stream("t1")
        val out = tmp.newFile("package.mp4")
        val progress = ArrayList<Pair<Int, Int>>()
        stream.writePackage(out) { done, total -> progress += done to total }
        assertArrayEquals(source.expectedStream(), out.readBytes())
        assertEquals(4, source.fetches.get())
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
    }

    @Test
    fun writePackageReusesSegmentsAlreadyDecryptedByPlayback() {
        // The point of sharing playback's cache: a track you just played downloads without
        // re-fetching or re-decrypting the segments that are already on disk.
        val source = FakeSource(listOf(5000, 3000, 4096, 2000))
        val cache = cache(source)
        cache.stream("t1").segment(2) // as if playback had reached the third segment
        assertEquals(1, source.fetches.get())
        assertEquals(1, source.clears.get())

        val out = tmp.newFile("package.mp4")
        cache.stream("t1").writePackage(out)
        assertArrayEquals(source.expectedStream(), out.readBytes())
        // Only the three missing segments were fetched and decrypted; segment 2 was reused.
        assertEquals(4, source.fetches.get())
        assertEquals(4, source.clears.get())
    }
}

class StreamCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Four tracks of six 15-second segments each, every one behind its own URL. */
    private val tracks = listOf("current", "next", "previous", "old", "next2", "next3", "previous2").associateWith { FakeSource(List(6) { 1000 }, name = it) }

    private fun cache(root: java.io.File) = StreamCache(
        root,
        { id -> tracks.getValue(id).media },
        object : SegmentSource {
            private fun at(url: String) = tracks.values.first { it.url == url }
            override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
                // Segment downloads only (the init is 64 bytes at offset 0), in the order made.
                if (offset != 0L) downloads += tracks.entries.first { it.value.url == url }.key
                return at(url).fetch(url, offset, length)
            }
            override fun clearInit(originalInit: ByteArray) = ByteArray(40)
            override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray) =
                tracks.getValue(trackId).clear(trackId, segment, originalInit, encrypted)
        },
    )

    private val downloads = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun cached(root: java.io.File, id: String) =
        java.io.File(root, id).listFiles { f -> f.name.endsWith(".seg") }?.map { it.name.removeSuffix(".seg").toInt() }?.sorted().orEmpty()

    @Test
    fun completesTheCurrentTrackAndPreparesTheOpeningOfItsNeighbours() {
        val root = tmp.newFolder()
        val cache = cache(root)
        cache.setWindow("current", "next", "previous", StreamConfig(nextSeconds = 30, previousSeconds = 15))
        assertTrue(cache.awaitIdle(10_000))
        assertEquals((0..5).toList(), cached(root, "current"))
        assertEquals(listOf(0, 1), cached(root, "next")) // 30 s = two 15-second segments
        assertEquals(listOf(0), cached(root, "previous"))
        assertEquals(0, tracks.getValue("old").fetches.get())
    }

    @Test
    fun theCurrentTracksFirstSegmentIsDownloadedBeforeAnyNeighbour() {
        val cache = cache(tmp.newFolder())
        cache.setWindow("current", "next", "previous", StreamConfig(nextSeconds = 30, previousSeconds = 15))
        assertTrue(cache.awaitIdle(10_000))
        val expected = listOf("current") + List(2) { "next" } + List(5) { "current" } + listOf("previous")
        assertEquals(expected, downloads.toList())
    }

    @Test
    fun honoursOffAndWholeSong() {
        val root = tmp.newFolder()
        val cache = cache(root)
        cache.setWindow("current", "next", "previous", StreamConfig(nextSeconds = StreamConfig.WHOLE, previousSeconds = 0))
        assertTrue(cache.awaitIdle(10_000))
        assertEquals((0..5).toList(), cached(root, "next"))
        assertEquals(emptyList<Int>(), cached(root, "previous"))
        assertEquals(0, tracks.getValue("previous").fetches.get())
        val next = cache.stream("next")
        assertEquals(1, next.segmentsFor(15))
        assertEquals(2, next.segmentsFor(20)) // one segment covers only 15 s
        assertEquals(6, next.segmentsFor(600))
        assertEquals(6, next.segmentsFor(StreamConfig.WHOLE))
        assertEquals(0, next.segmentsFor(0))
    }

    @Test
    fun repeatingTheSameWindowDoesNoExtraWork() {
        val root = tmp.newFolder()
        val cache = cache(root)
        val config = StreamConfig(nextSeconds = 15, previousSeconds = 0)
        repeat(5) { cache.setWindow("current", "next", null, config) }
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(6, tracks.getValue("current").fetches.get())
        assertEquals(1, tracks.getValue("next").fetches.get())
    }

    @Test
    fun keepsWholeSongsAheadAndBehindUpToTheirBudgets() {
        val root = tmp.newFolder()
        val cache = cache(root)
        // Each song is 6 kB of audio. Ahead: the first reaches 6 kB < 10 kB, so a second one is
        // taken and finished, and the third is left alone. Behind: one song fills its 5 kB.
        val config = StreamConfig(nextSeconds = 15, previousSeconds = 15, cacheBytes = 1L shl 20, aheadBytes = 10_000, behindBytes = 5_000)
        cache.setWindow("current", "next", "previous", config, ahead = listOf("next", "next2", "next3"), behind = listOf("previous", "previous2"))
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(6, cached(root, "current").size)
        assertEquals(6, cached(root, "next").size)
        assertEquals(6, cached(root, "next2").size)
        assertEquals(0, tracks.getValue("next3").fetches.get())
        assertEquals(6, cached(root, "previous").size)
        assertEquals(0, tracks.getValue("previous2").fetches.get())
        // What the listener waits for still comes first: the playing song's opening, then the next one's.
        assertEquals(listOf("current", "next"), downloads.toList().take(2))
    }

    @Test
    fun theWindowNeverGrowsPastTheCacheBudget() {
        val root = tmp.newFolder()
        val cache = cache(root)
        // Room for the playing song and little else: the window must stop instead of evicting it.
        val config = StreamConfig(nextSeconds = 15, previousSeconds = 0, cacheBytes = 9_000, aheadBytes = 1L shl 20, behindBytes = 1L shl 20)
        cache.setWindow("current", "next", null, config, ahead = listOf("next", "next2", "next3"), behind = listOf("previous"))
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(6, cached(root, "current").size)
        assertTrue("stopped at the limit, got ${cache.sizeOnDisk()}", cache.sizeOnDisk() < 9_000 + 1_100)
        assertEquals(0, tracks.getValue("next3").fetches.get())
        assertEquals(0, tracks.getValue("previous").fetches.get())
    }

    @Test
    fun songsInsideTheWindowSurviveATrimThatEvictsOlderOnes() {
        val root = tmp.newFolder()
        val cache = cache(root)
        for (id in listOf("old", "previous")) {
            cache.setWindow(id, null, null, StreamConfig())
            assertTrue(cache.awaitIdle(10_000))
            java.io.File(root, id).setLastModified(System.currentTimeMillis() - 60_000)
        }
        // Budget for two songs: "previous" is behind the playing one and stays; "old" goes.
        val config = StreamConfig(nextSeconds = 0, previousSeconds = 0, cacheBytes = 13_000, behindBytes = 5_000)
        cache.setWindow("current", null, null, config, behind = listOf("previous"))
        assertTrue(cache.awaitIdle(10_000))
        assertFalse(java.io.File(root, "old").exists())
        assertEquals(6, cached(root, "previous").size)
        assertEquals(6, cached(root, "current").size)
    }

    @Test
    fun trimsLeastRecentlyUsedTracksButNeverTheWindow() {
        val root = tmp.newFolder()
        val cache = cache(root)
        // "old" was played earlier and is fully cached.
        cache.setWindow("old", null, null, StreamConfig())
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(6, cached(root, "old").size)
        java.io.File(root, "old").setLastModified(System.currentTimeMillis() - 60_000)
        // The budget fits the two window tracks (6 kB each) but not a third.
        cache.setWindow("current", "next", null, StreamConfig(nextSeconds = StreamConfig.WHOLE, previousSeconds = 0, cacheBytes = 13_000))
        assertTrue(cache.awaitIdle(10_000))
        assertFalse("the old track should have been evicted", java.io.File(root, "old").exists())
        assertEquals(6, cached(root, "current").size)
        assertEquals(6, cached(root, "next").size)
        assertTrue(cache.sizeOnDisk() <= 13_000)
    }

    @Test
    fun aBudgetSmallerThanTheWindowStillKeepsTheWindow() {
        val root = tmp.newFolder()
        val cache = cache(root)
        cache.setWindow("current", "next", null, StreamConfig(nextSeconds = 15, previousSeconds = 0, cacheBytes = 10))
        assertTrue(cache.awaitIdle(10_000))
        assertEquals(6, cached(root, "current").size)
        assertEquals(listOf(0), cached(root, "next"))
    }

    @Test
    fun clearRemovesEverything() {
        val root = tmp.newFolder()
        val cache = cache(root)
        cache.setWindow("current", null, null, StreamConfig())
        assertTrue(cache.awaitIdle(10_000))
        assertTrue(cache.sizeOnDisk() > 0)
        cache.clear()
        assertEquals(0L, cache.sizeOnDisk())
    }
}
