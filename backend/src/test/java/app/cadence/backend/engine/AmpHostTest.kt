package app.cadence.backend.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Host-side harness for the amp-api backend. Runs on the JVM (no device), so endpoint
 * behaviour, JSON shapes and header/protocol questions are answered here first.
 * Run: ./gradlew :backend:test --tests '*AmpHostTest*' -i
 */
class AmpHostTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    private fun raw(url: String, headers: Map<String, String>): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to s
        } finally { c.disconnect() }
    }

    private fun auth() = mapOf(
        "Authorization" to "Bearer ${cfg.devToken}",
        "media-user-token" to cfg.mediaUserToken,
        "Music-User-Token" to cfg.mediaUserToken,
    )

    @Test
    fun chartsParse() = runBlocking {
        val engine = AmpEngine(cfg)
        val root = engine.charts()
        val chart = root?.optJSONObject("results")?.optJSONObject("albums")?.optJSONArray("data")
            ?: root?.optJSONObject("results")?.optJSONArray("albums")
        println("[charts] albums=${chart?.length() ?: 0}")
        val first = chart?.optJSONObject(0) ?: return@runBlocking
        // charts wrap groups ({data:[albums]}); search returns albums directly
        val albumJson = first.optJSONArray("data")?.optJSONObject(0) ?: first
        val album = Mapping.album(albumJson)
        println("[charts] first=${album?.title} cover=${album?.cover}")
        val tracks = engine.albumTracks(album!!.id)
        val n = tracks?.optJSONArray("data")?.length() ?: 0
        println("[charts] tracks=$n")
        val songId = tracks?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
        println("[charts] songId=$songId")
    }

    @Test
    fun personalParse() = runBlocking {
        val engine = AmpEngine(cfg)
        val recent = engine.me("recent/played?types=albums&limit=10")
        println("[me] recent data=${recent?.optJSONArray("data")?.length() ?: -1}")
        val recs = engine.me("recommendations?types=albums&limit=10")
        val items = recs?.optJSONArray("data")?.length() ?: -1
        var albums = 0
        for (i in 0 until maxOf(items, 0)) {
            albums += recs?.optJSONArray("data")?.optJSONObject(i)
                ?.optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data")?.length() ?: 0
        }
        println("[me] recommendations items=$items albums=$albums")
    }

    /** Disk cache: second instance must render a catalogue with no network at all. */
    @Test
    fun cacheRoundTrip(): Unit = runBlocking {
        val file = java.io.File.createTempFile("cadence-cache", ".json").also { it.delete() }
        val live = app.cadence.backend.real.RealCatalog(AmpEngine(cfg), file)
        live.seedBrowse()
        live.loadPersonal()
        live.saveCache()
        println("[cache] file=" + file.length() + "b albums=" + live.albums.size + " tracks=" + live.tracks.size)

        // a deliberately broken engine: any network use would fail
        val offline = app.cadence.backend.real.RealCatalog(
            AmpEngine(AmpConfig("broken", "", "gb", "en-US")), file,
        )
        val ok = offline.loadCache()
        println("[cache] restored=" + ok + " albums=" + offline.albums.size + " tracks=" + offline.tracks.size +
                " playlists=" + offline.playlists.size)
        println("[cache] first=" + offline.albums.firstOrNull()?.title + " cover=" +
                (offline.albums.firstOrNull()?.cover != null))
        val a = offline.albums.firstOrNull()
        if (a != null) println("[cache] tracksOfAlbum=" + offline.tracksOfAlbum(a.id).size)
        file.delete()
        Unit
    }

    /** Whole repository path on the PC: seed -> personal -> search -> tracks -> lyrics. */
    @Test
    fun repositoryEndToEnd() = runBlocking {
        val engine = AmpEngine(cfg)
        val catalog = app.cadence.backend.real.RealCatalog(engine)
        catalog.seedBrowse()
        println("[repo] after seed albums=" + catalog.albums.size + " tracks=" + catalog.tracks.size)
        catalog.loadPersonal()
        println("[repo] after personal albums=" + catalog.albums.size + " artists=" + catalog.artists.size +
                " playlists=" + catalog.playlists.size)

        val found = catalog.search("taylor swift")
        println("[repo] search top=" + found.top.size + " types=" + found.byType.mapValues { it.value.size })

        val firstAlbum = catalog.albums.firstOrNull()
        val tracks = firstAlbum?.let { catalog.tracksOfAlbum(it.id) }.orEmpty()
        println("[repo] album '" + firstAlbum?.title + "' tracks=" + tracks.size)
        val t = tracks.firstOrNull()
        println("[repo] first track='" + t?.track?.title + "' codec=" + t?.track?.format?.codec +
                " cover=" + (t?.album?.cover != null))

        val lyrTarget = tracks.firstOrNull() ?: catalog.tracks.firstOrNull()
        if (lyrTarget != null) {
            val lyr = catalog.lyrics(lyrTarget)
            val n = when (lyr) {
                is app.cadence.backend.model.Lyrics.Synced -> lyr.lines.size
                is app.cadence.backend.model.Lyrics.Plain -> lyr.text.lines().size
                else -> 0
            }
            println("[repo] lyrics '" + lyrTarget.track.title + "' -> " + lyr::class.simpleName + " lines=" + n)
        }
    }

    /**
     * The app's own lyrics path for songs that have synced lyrics: catalogue search -> track ->
     * RealCatalog.lyrics (what Now Playing calls). Guards against synced lyrics silently turning
     * into "no lyrics" or plain text.
     */
    @Test
    fun syncedLyricsThroughTheCatalogue() = runBlocking {
        val catalog = app.cadence.backend.real.RealCatalog(AmpEngine(cfg))
        var synced = 0
        val queries = listOf("pretty maids all in a row eagles", "karma police radiohead", "creep radiohead")
        for (q in queries) {
            val track = catalog.search(q).byType[app.cadence.backend.search.HitType.Song]
                ?.filterIsInstance<app.cadence.backend.search.SearchHit.SongHit>()?.firstOrNull()?.track
            if (track == null) { println("[lyrics-app] '$q' -> no song"); continue }
            val lyr = catalog.lyrics(track)
            val detail = when (lyr) {
                is app.cadence.backend.model.Lyrics.Synced -> { synced++; "Synced lines=${lyr.lines.size} firstStartMs=${lyr.lines.first().startMs} words=${lyr.lines.sumOf { it.words.size }}" }
                is app.cadence.backend.model.Lyrics.Plain -> "Plain"
                else -> "None"
            }
            println("[lyrics-app] '${track.track.title}' (${track.id}) -> $detail")
        }
        org.junit.Assert.assertTrue("synced lyrics for $synced of ${queries.size} songs", synced >= 2)
    }

    /** Full lyrics path on the PC: storefront -> JSON envelope -> TTML -> LRC -> parser. */
    @Test
    fun lyricsEndToEnd() = runBlocking {
        val engine = AmpEngine(cfg)
        println("[lyrics3] storefront=" + engine.storefront())
        for (id in listOf("6814997425", "1440935808")) {
            val ttml = engine.lyrics(id)
            println("[lyrics3] song=$id ttml=" + (ttml?.length ?: 0))
            if (ttml != null) {
                val lrc = Ttml.toLrc(ttml)
                val parsed = app.cadence.backend.lyrics.LrcParser.parse(lrc)
                val n = when (parsed) {
                    is app.cadence.backend.model.Lyrics.Synced -> parsed.lines.size
                    is app.cadence.backend.model.Lyrics.Plain -> parsed.text.lines().size
                    else -> 0
                }
                println("[lyrics3] lrcLines=" + lrc.lines().count { it.isNotBlank() } + " parsed=" + parsed::class.simpleName + " lines=" + n)
                if (parsed is app.cadence.backend.model.Lyrics.Synced) {
                    println("[lyrics3] first='" + parsed.lines.first().text + "' words=" + parsed.lines.first().words.size)
                }
            }
        }
    }

    /** Positive path: find a track that advertises lyrics, fetch TTML, convert, parse. */
    @Test
    fun lyricsOnTrackThatHasThem() = runBlocking {
        val engine = AmpEngine(cfg)
        val albums = engine.me("recommendations?types=albums&limit=5")
        var songId: String? = null
        var title = ""
        outer@ for (i in 0 until (albums?.optJSONArray("data")?.length() ?: 0)) {
            val contents = albums!!.optJSONArray("data").optJSONObject(i)
                .optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data") ?: continue
            for (j in 0 until contents.length()) {
                val id = Mapping.album(contents.optJSONObject(j) ?: continue)?.id ?: continue
                val tracks = engine.albumTracks(id)?.optJSONArray("data") ?: continue
                for (k in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(k) ?: continue
                    val a = t.optJSONObject("attributes") ?: continue
                    if (a.optBoolean("hasLyrics", false)) {
                        songId = t.optString("id"); title = a.optString("name"); break@outer
                    }
                }
            }
        }
        println("[lyrics2] candidate songId=$songId title=$title")
        val id = songId ?: return@runBlocking
        val ttml = engine.lyrics(id)
        println("[lyrics2] ttml bytes=${ttml?.length ?: 0}")
        if (ttml != null) {
            val lrc = Ttml.toLrc(ttml)
            println("[lyrics2] lrc lines=" + lrc.lines().count { it.isNotBlank() })
            println("[lyrics2] lrc head=" + lrc.lines().take(3).joinToString(" | "))
            val parsed = app.cadence.backend.lyrics.LrcParser.parse(lrc)
            val n = when (parsed) {
                is app.cadence.backend.model.Lyrics.Synced -> parsed.lines.size
                is app.cadence.backend.model.Lyrics.Plain -> parsed.text.lines().size
                else -> 0
            }
            println("[lyrics2] parsed=" + parsed::class.simpleName + " lines=" + n)
            if (parsed is app.cadence.backend.model.Lyrics.Synced) {
                println("[lyrics2] first line='" + parsed.lines.first().text + "' words=" + parsed.lines.first().words.size)
            }
        }
    }

    /** Protocol probe: which header set makes syllable-lyrics return 200? */
    @Test
    fun lyricsProtocol() {
        val songId = "1771970311"
        val base = "https://amp-api.music.apple.com/v1/catalog/${cfg.storefront}/songs/$songId/syllable-lyrics"
        val queries = listOf(
            "?l%5Blyrics%5D=en-US&extend=ttmlLocalizations&l%5Bscript%5D=en-Latn",
            "?l%5Blyrics%5D=en-US",
            "",
        )
        val accepts = listOf(null, "application/ttml+xml", "text/xml", "*/*")
        for (q in queries) for (a in accepts) {
            val h = auth().toMutableMap()
            if (a != null) h["Accept"] = a
            val (code, body) = raw(base + q, h)
            println("[lyrics] q='${q.take(20)}' accept=$a -> $code ${body.take(90).replace('\n', ' ')}")
            if (code == 200) return
        }
    }
}
