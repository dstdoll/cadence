package app.cadence.backend.links

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.links.LinkTarget.Kind
import app.cadence.backend.real.RealCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkResolveTest {
    private fun catalog() = RealCatalog(AmpEngine(AmpConfig("", "", "us", "en-US")), null)
    private fun data(vararg o: String) = JSONObject().put("data", JSONArray().also { a -> o.forEach { a.put(JSONObject(it)) } })

    private val album = """{"id":"1665303755","type":"albums","attributes":{"name":"The Dark Side of the Moon","artistName":"Pink Floyd","releaseDate":"1973-03-01","genreNames":["Rock"],"trackCount":10}}"""
    private val song = """{"id":"1665303759","type":"songs","attributes":{"name":"Breathe (In the Air)","artistName":"Pink Floyd","albumName":"The Dark Side of the Moon","durationInMillis":170000,"url":"https://music.apple.com/us/album/x/1665303755?i=1665303759"},"relationships":{"albums":{"data":[{"id":"1665303755","type":"albums"}]}}}"""
    private val artist = """{"id":"487143","type":"artists","attributes":{"name":"Pink Floyd","genreNames":["Rock"]}}"""
    private val playlist = """{"id":"pl.f4d106fed2bd41149aaacabb233eb5eb","type":"playlists","attributes":{"name":"Today's Hits","curatorName":"Apple Music"}}"""
    private fun resolve(c: RealCatalog, link: AppleLink, vararg answers: Pair<String, JSONObject?>): LinkTarget {
        val asked = ArrayList<String>()
        val map = answers.toMap()
        return runBlocking { c.resolveLink(link) { path -> asked += path; map[path] } }.also { lastAsked = asked }
    }
    private var lastAsked: List<String> = emptyList()

    @Test fun anAlbumLinkRegistersTheAlbumAndOpensIt() {
        val c = catalog()
        val t = resolve(c, AppleLink.Album("1665303755"), "albums/1665303755" to data(album))
        assertEquals(LinkTarget.Open(Kind.Album, "1665303755", null), t)
        assertNotNull(c.album("1665303755"))
        assertEquals(listOf("albums/1665303755"), lastAsked)
    }

    @Test fun aSongInAnAlbumLinkOpensTheAlbumWithThatSongSelected() {
        val c = catalog()
        val t = resolve(c, AppleLink.Album("1665303755", "1665303759"), "albums/1665303755" to data(album))
        assertEquals(LinkTarget.Open(Kind.Album, "1665303755", "1665303759"), t)
    }

    @Test fun aSongLinkOpensTheSongsAlbumWithTheSongSelected() {
        val c = catalog()
        val t = resolve(c, AppleLink.Song("1665303759"), "songs/1665303759" to data(song))
        val open = t as LinkTarget.Open
        assertEquals(listOf(Kind.Album, "1665303759"), listOf(open.kind, open.selectSongId))
        assertNotNull("the album the song is on is registered", c.album(open.id))
        assertNotNull(c.track("1665303759"))
    }

    @Test fun anArtistAndAPlaylistLinkOpenTheirPages() {
        val c = catalog()
        val a = resolve(c, AppleLink.Artist("487143"), "artists/487143" to data(artist)) as LinkTarget.Open
        assertEquals(Kind.Artist, a.kind); assertNotNull(c.artist(a.id))
        val p = resolve(c, AppleLink.Playlist("pl.f4d106fed2bd41149aaacabb233eb5eb"), "playlists/pl.f4d106fed2bd41149aaacabb233eb5eb" to data(playlist)) as LinkTarget.Open
        assertEquals(listOf(Kind.Playlist, "pl:pl.f4d106fed2bd41149aaacabb233eb5eb"), listOf(p.kind, p.id).map { it })
        assertNotNull(c.playlist(p.id))
    }

    @Test fun nothingFoundOrNoAnswerIsNotFoundAndRegistersNothing() {
        val c = catalog()
        for (link in listOf(AppleLink.Album("999999"), AppleLink.Song("999999"), AppleLink.Artist("999999"), AppleLink.Playlist("pl.zzz"))) {
            assertEquals("$link", LinkTarget.NotFound, resolve(c, link))
            assertEquals("$link (an empty list)", LinkTarget.NotFound, resolve(c, link, *arrayOf(
                when (link) { is AppleLink.Album -> "albums/999999"; is AppleLink.Song -> "songs/999999"; is AppleLink.Artist -> "artists/999999"; else -> "playlists/pl.zzz" } to data())))
        }
        assertNull(c.album("999999")); assertNull(c.track("999999"))
    }

    @Test fun aResourceOfTheWrongKindIsNotMistakenForTheRightOne() {
        val c = catalog()
        assertEquals("an artist answer to an album question", LinkTarget.NotFound, resolve(c, AppleLink.Album("487143"), "albums/487143" to data(artist)))
    }

    @Test fun unsupportedLinksAreNamedWithoutAnyRequest() {
        val c = catalog()
        assertEquals(LinkTarget.Unsupported("music-video"), resolve(c, AppleLink.Unsupported("music-video")))
        assertEquals("no request was made", emptyList<String>(), lastAsked)
    }

    @Test fun onlyTheValidatedPathShapesAreEverRequested() {
        val c = catalog()
        resolve(c, AppleLinks.parse("https://music.apple.com/us/album/x/1665303755?i=1665303759")!!, "albums/1665303755" to data(album))
        resolve(c, AppleLinks.parse("https://music.apple.com/us/song/1665303759")!!, "songs/1665303759" to data(song))
        assertEquals(listOf("songs/1665303759"), lastAsked)
        assertEquals(null, AppleLinks.parse("https://music.apple.com/us/album/x/1665303755/../../me/library"))
    }
}
