package app.cadence.backend.engine

import app.cadence.backend.api.SearchScope
import app.cadence.backend.real.RealCatalog
import app.cadence.backend.real.RealSearchService
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchHit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end search against the live amp-api, through the same service the UI calls. Each check
 * follows a hit the way a tap would: a song must open a real album that contains it, an artist
 * must load a discography, a playlist must load its tracks.
 * Run: ./gradlew :backend:testDebugUnitTest --tests '*SearchEndToEndHostTest*' -i
 */
class SearchEndToEndHostTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    private val all = SearchScope(libraryTrackIds = null)

    @Test
    fun catalogSearchFollowsEveryHitType() = runBlocking {
        val engine = AmpEngine(cfg)
        val catalog = RealCatalog(engine)
        val service = RealSearchService(catalog)
        val railBefore = catalog.playlists.size

        val res = service.search("radiohead", all)
        println("[e2e] top=" + res.top.map { it.type.name + ":" + label(it) })
        println("[e2e] byType=" + res.byType.mapValues { it.value.size })
        for (type in listOf(HitType.Artist, HitType.Album, HitType.Song, HitType.Playlist)) {
            assertTrue("no $type hits", res.byType[type].orEmpty().isNotEmpty())
        }
        // Apple's ranking, not ours: the artist is the top result for their own name.
        val first = res.top.first()
        assertTrue("top result is ${label(first)}", first is SearchHit.ArtistHit && first.artist.name == "Radiohead")
        assertEquals("duplicate hits in top", res.top.size, res.top.map { it.key }.distinct().size)

        // Songs: real album id, and that album's tracklist contains the song.
        val songs = res.byType.getValue(HitType.Song).filterIsInstance<SearchHit.SongHit>()
        for (hit in songs) {
            assertTrue("fabricated album id '${hit.track.album.id}'", hit.track.album.id.all(Char::isDigit))
            assertTrue("song without duration", hit.track.track.durationMs > 0)
            assertNotNull("song without cover", hit.track.album.cover)
        }
        for (hit in songs.take(3)) {
            val tracklist = catalog.prepareAlbum(hit.track.album.id)
            println("[e2e] song '${hit.track.track.title}' -> album '${hit.track.album.title}' (${hit.track.album.id}) tracks=${tracklist.size}")
            assertTrue("album does not contain its own song", tracklist.any { it.id == hit.track.id })
            val apple = engine.albumTracks(hit.track.album.id)!!.getJSONArray("data")
            val appleIds = (0 until apple.length()).map { apple.getJSONObject(it).getString("id") }
            assertEquals("album tracklist differs from Apple's", appleIds, tracklist.map { it.id })
        }

        // Artist: tapping it loads a discography, all under the same display id.
        val artist = (res.byType.getValue(HitType.Artist).first() as SearchHit.ArtistHit).artist
        assertTrue("artist has no photo", artist.cover.orEmpty().startsWith("https://"))
        val cached = catalog.albumsOfArtist(artist.id).size
        val discography = catalog.prepareArtist(artist.id)
        println("[e2e] artist '${artist.name}' albums cached=$cached -> loaded=${discography.size} color=${artist.color}")
        assertTrue("discography not loaded", discography.size >= 10 && discography.size >= cached)
        assertTrue(discography.all { it.artistId == artist.id })

        // Playlist: resolvable by id without being on the Home rail, and it loads tracks.
        val playlist = (res.byType.getValue(HitType.Playlist).first() as SearchHit.PlaylistHit).playlist
        assertNotNull("search playlist not resolvable", catalog.playlist(playlist.id))
        println("[e2e] playlist curator='${playlist.curator}' cover=${playlist.cover != null} artistPhoto=${artist.cover != null}")
        assertTrue("playlist has no curator", playlist.curator.isNotBlank())
        assertNotNull("playlist has no cover", playlist.cover)
        val tracks = catalog.preparePlaylist(playlist.id)
        println("[e2e] playlist '${playlist.title}' (${playlist.description.take(40)}) tracks=${tracks.size}")
        assertTrue("playlist loaded no tracks", tracks.isNotEmpty())
        assertEquals(tracks.size, catalog.playlist(playlist.id)!!.trackIds.size)
        assertEquals("search leaked playlists into the Home rail", railBefore, catalog.playlists.size)
    }

    @Test
    fun suggestionsComeFromApple() = runBlocking {
        val service = RealSearchService(RealCatalog(AmpEngine(cfg)))
        val s = service.suggest("radioh", all)
        println("[e2e] suggest completions=${s.completions} matches=${s.matches.map { it.type.name + ":" + label(it) }}")
        assertTrue("no completion mentions the artist", s.completions.any { it.contains("radiohead", ignoreCase = true) })
        assertTrue("no direct matches", s.matches.isNotEmpty())
        assertTrue(service.suggest("  ", all).let { it.completions.isEmpty() && it.matches.isEmpty() })
    }

    @Test
    fun edgeQueries() = runBlocking {
        val service = RealSearchService(RealCatalog(AmpEngine(cfg)))
        val none = service.search("zzqqxxyyzzqq1234567", all)
        println("[e2e] nonsense -> top=${none.top.size}")
        assertTrue(none.isEmpty)
        for (q in listOf("ロストエデンへのパス", "corazón", "AC/DC", "beyoncé & jay-z")) {
            val r = service.search(q, all)
            println("[e2e] '$q' -> top=${r.top.size} first=${r.top.firstOrNull()?.let { it.type.name + ":" + label(it) }}")
            assertTrue("no results for '$q'", r.top.isNotEmpty())
        }
    }

    @Test
    fun libraryScopeSearchesOnlySavedItems() = runBlocking {
        val catalog = RealCatalog(AmpEngine(cfg))
        val service = RealSearchService(catalog)
        val songs = service.search("radiohead", all).byType.getValue(HitType.Song).filterIsInstance<SearchHit.SongHit>()
        val saved = songs.take(2).map { it.track }
        val scope = SearchScope(libraryTrackIds = saved.map { it.id }.toSet())

        val hit = service.search(saved[0].track.title, scope)
        println("[e2e] library '${saved[0].track.title}' -> " + hit.top.map { it.type.name + ":" + label(it) })
        val found = hit.byType[HitType.Song].orEmpty().filterIsInstance<SearchHit.SongHit>()
        assertTrue(found.any { it.track.id == saved[0].id })
        assertTrue("library search returned unsaved songs", found.all { s -> saved.any { it.id == s.track.id } })
        // A catalogue-wide term that is not in the library finds nothing there.
        assertTrue(service.search("taylor swift", scope).isEmpty)
    }

    private fun label(hit: SearchHit) = when (hit) {
        is SearchHit.ArtistHit -> hit.artist.name
        is SearchHit.AlbumHit -> hit.album.title
        is SearchHit.SongHit -> hit.track.track.title
        is SearchHit.PlaylistHit -> hit.playlist.title
        is SearchHit.LyricHit -> hit.line
    }
}
