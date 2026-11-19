package app.cadence.backend.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Host-side replica of HlsDownloader's staging step: resolves a track's ALAC media playlist and
 * writes the still-encrypted package (init + every segment) to disk, so its box layout can be
 * inspected on the PC. Nothing is decrypted here.
 *
 * Run: ./gradlew :backend:testDebugUnitTest --tests '*HlsPackageProbeTest*' -i \
 *        (env PROBE_TERM="<search term>" PROBE_OUT=/path/package.enc)
 */
class HlsPackageProbeTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    private fun bytes(url: URL, offset: Long?, length: Long?): ByteArray {
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 60000
            if (length != null) setRequestProperty("Range", "bytes=${offset ?: 0}-${(offset ?: 0) + length - 1}")
        }
        return try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    private fun attr(line: String, name: String) = Regex("$name=\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    @Test
    fun stagePackage() = runBlocking {
        // Either an exact catalogue id (PROBE_TRACK_ID) or a search term (PROBE_TERM).
        val trackId = System.getenv("PROBE_TRACK_ID")
        val term = System.getenv("PROBE_TERM")
        if (trackId == null && term == null) return@runBlocking
        val out = File(System.getenv("PROBE_OUT") ?: return@runBlocking)
        val engine = AmpEngine(cfg)
        val song = if (trackId != null) engine.song(trackId)?.optJSONArray("data")?.optJSONObject(0)
        else engine.get("search?term=${java.net.URLEncoder.encode(term, "UTF-8")}&types=songs&limit=1&extend=extendedAssetUrls")
            ?.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")?.optJSONObject(0)
        val a = song?.optJSONObject("attributes")
        println("[pkg] song id=${song?.optString("id")} name='${a?.optString("name")}' album='${a?.optString("albumName")}' durationMs=${a?.optLong("durationInMillis")}")
        val hls = a?.optJSONObject("extendedAssetUrls")?.optString("enhancedHls")?.takeIf { it.isNotBlank() }
        if (hls == null) { println("[pkg] no enhancedHls"); return@runBlocking }

        val masterUrl = URL(hls)
        val master = bytes(masterUrl, null, null).toString(Charsets.UTF_8).lines()
        val infs = master.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") }
        infs.forEach { println("[pkg] variant ${it.value.substringAfter(':').take(170)}") }
        // PROBE_CODEC=aac stages the 256 kbps AAC variant instead of the lossless one.
        val wanted = if (System.getenv("PROBE_CODEC") == "aac") "mp4a.40.2" else "alac"
        val matching = infs.filter { it.value.contains("CODECS=\"$wanted\"", true) }
        val pick = matching.maxByOrNull { Regex("[^-]BANDWIDTH=(\\d+)").find(it.value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L } ?: infs.first()
        val mediaUrl = URL(masterUrl, master.drop(pick.index + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim())
        val lines = bytes(mediaUrl, null, null).toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
        println("[pkg] media tags=" + lines.filter { it.startsWith("#") }.map { it.substringBefore(':') }.groupingBy { it }.eachCount())
        File(out.path + ".m3u8").writeText(lines.joinToString("\n"))
        File(out.path + ".url").writeText(mediaUrl.toString())

        val map = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
        println("[pkg] map=${map?.replace(Regex("URI=\"[^\"]+\""), "URI=...")}")
        var key = "none"; var range: Pair<Long, Long?>? = null
        data class Seg(val url: String, val off: Long?, val len: Long?, val key: String, val inf: String)
        val segs = ArrayList<Seg>(); var inf = ""
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-KEY:") -> key = attr(l, "URI")?.substringAfterLast('/')?.let { (if (l.contains("P000000000")) "prefetch/" else "content/") + it } ?: key
                l.startsWith("#EXTINF:") -> inf = l.substringAfter(':').substringBefore(',')
                l.startsWith("#EXT-X-BYTERANGE:") -> l.substringAfter(':').split("@").let { range = it[0].toLong() to it.getOrNull(1)?.toLongOrNull() }
                l.startsWith("#") -> {}
                else -> { segs += Seg(URL(mediaUrl, l).toString(), range?.second, range?.first, key, inf); range = null }
            }
        }
        println("[pkg] segments=${segs.size} keys=${segs.groupingBy { it.key }.eachCount()} extinfTotal=${segs.sumOf { it.inf.toDoubleOrNull() ?: 0.0 }}")
        segs.take(3).forEachIndexed { i, s -> println("[pkg] seg$i inf=${s.inf} off=${s.off} len=${s.len} key=${s.key}") }

        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { o ->
            if (map != null) {
                val r = Regex("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"").find(map)
                o.write(bytes(URL(mediaUrl, attr(map, "URI")!!), r?.groupValues?.get(2)?.toLongOrNull(), r?.groupValues?.get(1)?.toLongOrNull()))
            }
            val next = HashMap<String, Long>()
            for (s in segs) {
                val start = s.off ?: next[s.url] ?: if (s.len != null) 0L else null
                o.write(bytes(URL(s.url), start, s.len))
                if (s.len != null) next[s.url] = (start ?: 0L) + s.len
            }
        }
        println("[pkg] wrote ${out.length()} bytes")
    }
}
