package app.cadence.backend.engine

import app.cadence.backend.real.RealCatalog
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchHit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live checks of the artist page, the account's playlists/favourites and the request budget.
 * Run: ./gradlew :backend:testDebugUnitTest --tests '*ProfileArtistHostTest*' -i
 */
class ProfileArtistHostTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    @Test
    fun artistPageIsComplete() = runBlocking {
        val engine = AmpEngine(cfg)
        val catalog = RealCatalog(engine)
        val hit = catalog.search("radiohead").byType.getValue(HitType.Artist).first() as SearchHit.ArtistHit
        val before = engine.networkCalls
        catalog.prepareArtist(hit.artist.id)
        val calls = engine.networkCalls - before
        val artist = catalog.artist(hit.artist.id)!!
        val top = catalog.topSongs(artist.id)
        println("[artist] '${artist.name}' bio=${artist.bio.length}ch origin='${artist.origin}' formed='${artist.formed}' genres=${artist.genres}")
        println("[artist] top=${top.map { it.track.title }}")
        println("[artist] albums=${catalog.albumsOfArtist(artist.id).size} similar=${catalog.similarArtists(artist.id).map { it.name }} playlists=${catalog.artistPlaylists(artist.id).map { it.title }.take(3)} calls=$calls")
        assertTrue("biography missing", artist.bio.length > 200)
        assertFalse("biography still carries markup", artist.bio.contains('<'))
        assertTrue(artist.origin.isNotBlank() && artist.formed.isNotBlank() && artist.genres.isNotEmpty())
        // Apple's own order, not whatever happened to be cached.
        val apple = engine.artist("657515")!!.getJSONArray("data").getJSONObject(0).getJSONObject("views")
            .getJSONObject("top-songs").getJSONArray("data")
        assertEquals((0 until apple.length()).map { apple.getJSONObject(it).getString("id") }, top.map { it.id })
        assertTrue(top.all { it.album.id.all(Char::isDigit) && it.album.cover != null })
        assertTrue(catalog.similarArtists(artist.id).size >= 5 && catalog.similarArtists(artist.id).all { it.cover != null })
        assertTrue(catalog.artistPlaylists(artist.id).isNotEmpty())
        assertTrue(catalog.albumsOfArtist(artist.id).size >= 20)
        assertTrue("artist page cost $calls requests", calls <= 3)
        // Opening it again is free.
        val again = engine.networkCalls
        catalog.prepareArtist(artist.id)
        assertEquals(again, engine.networkCalls)
    }

    @Test
    fun accountPlaylistsAndFavouritesComeFromTheProfile() = runBlocking {
        val catalog = RealCatalog(AmpEngine(cfg))
        catalog.loadLibrary()
        val playlists = catalog.libraryPlaylists
        println("[profile] playlists=${playlists.map { it.title + "(" + it.trackIds.size + ")" }}")
        println("[profile] favourites=${catalog.favoriteTrackIds.size}")
        assertTrue("no account playlists", playlists.isNotEmpty())
        assertTrue(playlists.all { it.id.startsWith("lp:") && catalog.playlist(it.id) != null })
        assertTrue("no favourites loaded", catalog.favoriteTrackIds.isNotEmpty())
        val favourites = catalog.favoriteTrackIds.mapNotNull(catalog::track)
        assertEquals(catalog.favoriteTrackIds.size, favourites.size)
        assertTrue(favourites.all { it.album.id.all(Char::isDigit) && it.track.durationMs > 0 })
        println("[profile] favourite sample=${favourites.take(3).map { it.track.title + " · " + it.artist.name }}")
        // Another playlist opens on demand and keeps its tracklist.
        val other = playlists.first { it.trackIds.isEmpty() }
        val tracks = catalog.preparePlaylist(other.id)
        println("[profile] '${other.title}' tracks=${tracks.size}")
        assertEquals(tracks.size, catalog.playlist(other.id)!!.trackIds.size)
        assertTrue("account playlists leaked into the Home rail", catalog.playlists.none { it.id.startsWith("lp:") })
    }

    @Test
    fun homeFollowsTheAccountsShelves() = runBlocking {
        val catalog = RealCatalog(AmpEngine(cfg))
        val before = catalog.revision.value
        catalog.loadPersonal()
        val shelves = catalog.shelves
        println("[home] " + shelves.joinToString(" | ") { (if (it.hero) "*" else "") + it.title + ":" + it.items.size })
        assertTrue("no shelves", shelves.size >= 3)
        assertEquals("exactly one leading shelf", 1, shelves.count { it.hero })
        assertTrue(shelves.all { it.title.isNotBlank() && it.items.isNotEmpty() })
        assertTrue("revision did not move", catalog.revision.value > before)
        // Every item must open: albums by id, playlists and stations through playlist().
        for (item in shelves.flatMap { it.items }) when (item) {
            is app.cadence.backend.model.ShelfItem.AlbumItem -> assertTrue(catalog.album(item.album.id) != null && item.album.cover != null)
            is app.cadence.backend.model.ShelfItem.PlaylistItem -> assertTrue("unresolvable ${item.playlist.id}", catalog.playlist(item.playlist.id) != null)
        }
    }

    @Test
    fun repeatedWorkDoesNotReachAppleAgain() = runBlocking {
        val engine = AmpEngine(cfg)
        val catalog = RealCatalog(engine)
        catalog.search("daft punk")
        val afterFirst = engine.networkCalls
        catalog.search("daft punk")
        assertEquals("a repeated search hit the network", afterFirst, engine.networkCalls)

        // A song with no lyrics: one request per candidate path, never retried, then cached.
        val before = engine.networkCalls
        assertEquals(null, engine.lyrics("1055140607"))
        val cost = engine.networkCalls - before
        assertEquals(null, engine.lyrics("1055140607"))
        println("[budget] search repeat=0 calls · missing lyrics cost=$cost then ${engine.networkCalls - before - cost} · cacheHits=${engine.cacheHits} throttled=${engine.throttledCount}")
        assertTrue("missing lyrics cost $cost requests", cost <= 3)
        assertEquals(before + cost, engine.networkCalls)
        assertEquals(0L, engine.throttledCount)
    }

    @Test
    fun albumPageRowsComeFromTheCatalogue() = runBlocking {
        val engine = AmpEngine(cfg)
        val catalog = RealCatalog(engine)
        catalog.prepareAlbum("1109714933")
        val before = engine.networkCalls
        catalog.prepareAlbumExtras("1109714933")
        catalog.prepareAlbumExtras("1109714933")
        val related = catalog.relatedAlbums("1109714933")
        println("[album] related=${related.map { it.title }} versions=${catalog.otherVersions("1109714933").size} calls=${engine.networkCalls - before}")
        assertEquals("one request, however often the page opens", 1, engine.networkCalls - before)
        assertTrue(related.size >= 5 && related.all { it.cover != null && it.id.all(Char::isDigit) })
    }
}
