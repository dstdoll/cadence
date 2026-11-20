package app.cadence.backend.real

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.model.ArtistIds
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchHit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two different artists can share a name (real Apple data: "Joey Yung" is 1274231511 and 1547065248,
 * next to the Cantopop singer 容祖兒, 370528896). Each is its own row, with its own page, albums and
 * photo; the one that owns the plain name keeps `ar:<name>`, the other gets `arx:<id>:<name>`.
 */
class ArtistIdentityTest {
    private val a = "1274231511"
    private val b = "1547065248"
    private val qb = "arx:1547065248:Joey Yung"

    private fun catalog(cache: File? = null) = RealCatalog(AmpEngine(AmpConfig("", "", "us", "en-US")), cache)
    private fun arr(vararg o: JSONObject) = JSONArray().also { x -> o.forEach(x::put) }

    private fun artist(id: String, name: String = "Joey Yung", genre: String = "Hip-Hop/Rap") =
        JSONObject("""{"id":"$id","type":"artists","attributes":{"name":"$name","genreNames":["$genre"]}}""")

    private fun album(id: String, artistId: String? = null, artistName: String = "Joey Yung") = JSONObject(
        """{"id":"$id","type":"albums","attributes":{"name":"Album $id","artistName":"$artistName","releaseDate":"2010-01-01","genreNames":["Rap"],"trackCount":3}""" +
            (artistId?.let { ""","relationships":{"artists":{"data":[{"id":"$it","type":"artists"}]}}""" } ?: "") + "}",
    )

    private fun song(id: String, albumId: String, artistId: String?) = JSONObject(
        """{"id":"$id","type":"songs","attributes":{"name":"Song $id","artistName":"Joey Yung","albumName":"Album $albumId","durationInMillis":1000,"url":"https://music.apple.com/us/album/x/$albumId?i=$id"},""" +
            """"relationships":{"albums":{"data":[{"id":"$albumId","type":"albums"}]}""" +
            (artistId?.let { ""","artists":{"data":[{"id":"$it","type":"artists"}]}""" } ?: "") + "}}",
    )

    @Test fun twoSameNamedArtistsAreTwoRowsWithTheirOwnIdsGenresAndPhotos() {
        val c = catalog()
        val hits = c.hitsOf(arr(artist(a, genre = "Underground Rap"), artist(b, genre = "Hip-Hop/Rap"), artist("370528896", "容祖兒", "Cantopop/HK-Pop")))
        val artists = hits.map { (it as SearchHit.ArtistHit).artist }
        assertEquals(listOf("ar:Joey Yung", qb, "ar:容祖兒"), artists.map { it.id })
        assertEquals(listOf("Joey Yung", "Joey Yung", "容祖兒"), artists.map { it.name })
        assertEquals(listOf("Underground Rap", "Hip-Hop/Rap", "Cantopop/HK-Pop"), artists.map { it.genres.first() })
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
        assertEquals(b, c.realArtistId(qb))
        assertEquals(hits.map { it.key }.distinct(), hits.map { it.key })
    }

    @Test fun anAlbumOrSongOfTheOtherArtistLinksToThatArtistNotToTheNameOwner() {
        val c = catalog()
        c.hitsOf(arr(artist(a), artist(b)))
        val albumB = c.hitsOf(arr(album("901", b))).single() as SearchHit.AlbumHit
        val albumA = c.hitsOf(arr(album("902", a))).single() as SearchHit.AlbumHit
        assertEquals(qb, albumB.album.artistId)
        assertEquals("ar:Joey Yung", albumA.album.artistId)
        assertEquals("the album row subtitle shows the plain name, never the id", "Joey Yung", albumB.artistName)
        assertEquals("Joey Yung", c.artist(qb)?.name)
        val songB = c.hitsOf(arr(song("5", "901", b))).single() as SearchHit.SongHit
        assertEquals(qb, songB.track.artist.id)
        assertEquals("Joey Yung", songB.track.artist.name)
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test fun aCreditThatMatchesNoSoleArtistStaysNameBased() {
        val c = catalog()
        val two = JSONObject(
            """{"id":"7","type":"albums","attributes":{"name":"Duet","artistName":"A & B","releaseDate":"2010-01-01","genreNames":["Pop"]},""" +
                """"relationships":{"artists":{"data":[{"id":"1","type":"artists"},{"id":"2","type":"artists"}]}}}""",
        )
        val hit = c.hitsOf(arr(two)).single() as SearchHit.AlbumHit
        assertEquals("ar:A & B", hit.album.artistId)
        assertNull(c.realArtistId("ar:A & B"))
    }

    @Test fun theTwoArtistsAlbumListsDoNotMerge() {
        val c = catalog()
        c.hitsOf(arr(artist(a), artist(b)))
        c.applyArtistAlbums("ar:Joey Yung", JSONObject().put("data", arr(album("901"), album("902"))))
        c.applyArtistAlbums(qb, JSONObject().put("data", arr(album("903"), album("904"), album("905"))))
        assertEquals(listOf("901", "902"), c.albumsOfArtist("ar:Joey Yung").map { it.id })
        assertEquals(listOf("903", "904", "905"), c.albumsOfArtist(qb).map { it.id })
        // an album credited to exactly this artist's name is re-credited to the artist whose page returned it
        assertEquals(qb, c.albums.single { it.id == "903" }.artistId)
        assertEquals("ar:Joey Yung", c.albums.single { it.id == "901" }.artistId)
    }

    @Test fun theViewsOfAnArtistPageAreAdoptedToo() {
        val c = catalog()
        c.hitsOf(arr(artist(a), artist(b)))
        val resource = JSONObject(
            """{"id":"$b","type":"artists","attributes":{"name":"Joey Yung","genreNames":["Hip-Hop/Rap"],"artistBio":"b"},"views":{""" +
                """"full-albums":{"data":[${album("911")}]},"latest-release":{"data":[${album("912")}]},"singles":{"data":[${album("913")}]},""" +
                """"top-songs":{"data":[]},"similar-artists":{"data":[]},"featured-playlists":{"data":[]}}}""",
        )
        c.applyArtistDetail(qb, resource)
        assertEquals(setOf("911", "912", "913"), c.albumsOfArtist(qb).map { it.id }.toSet())
        assertTrue(c.albums.filter { it.id in setOf("911", "912", "913") }.all { it.artistId == qb })
        assertEquals("the page keeps this artist's own id and bio", qb, c.artist(qb)?.id)
        assertEquals("b", c.artist(qb)?.bio)
    }

    @Test fun beforeAnArtistPageLoadsTheAlbumsCreditedToTheIdAreUsed() {
        val c = catalog()
        c.hitsOf(arr(artist(a), artist(b)))
        c.hitsOf(arr(album("901", a), album("903", b)))
        assertEquals(listOf("901"), c.albumsOfArtist("ar:Joey Yung").map { it.id })
        assertEquals(listOf("903"), c.albumsOfArtist(qb).map { it.id })
    }

    @Test fun aQualifiedIdOpensItsOwnCatalogueArtistWithoutAnyBinding() {
        val c = catalog()          // nothing bound, as after a restart with an old cache
        assertEquals(b, c.realArtistId(qb))
        assertNull(c.realArtistId("ar:Joey Yung"))
    }

    @Test fun bindingsSurviveARestartAndAnOldCacheWithoutThemStillLoads() {
        val dir = kotlin.io.path.createTempDirectory("artist-identity").toFile()
        try {
            val file = File(dir, "catalog.json")
            val c = catalog(file)
            c.hitsOf(arr(artist(b), artist(a)))                   // b first: b owns the plain name
            c.hitsOf(arr(album("901", b)))
            c.saveCache()
            val again = catalog(file)
            assertTrue(again.loadCache())
            assertEquals(b, again.realArtistId("ar:Joey Yung"))
            assertEquals("a restarted app gives the other artist the same qualified id", "arx:$a:Joey Yung", again.displayIdFor(a, "Joey Yung"))
            assertNotNull(again.artist("arx:$a:Joey Yung"))

            // a cache written before bindings existed has no such field
            val old = JSONObject(file.readText()).also { it.remove("artistBindings") }
            file.writeText(old.toString())
            val legacy = catalog(file)
            assertTrue(legacy.loadCache())
            assertNull(legacy.realArtistId("ar:Joey Yung"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aNewBindingIsSavedShortlyAfterWithoutAnExplicitSave() {
        val dir = kotlin.io.path.createTempDirectory("artist-identity").toFile()
        try {
            val file = File(dir, "catalog.json")
            val c = catalog(file)
            c.bindCatalogId("ar:Joey Yung", a)
            c.bindCatalogId("ar:Joey Yung", b)                  // refused: no second save needed
            val deadline = System.currentTimeMillis() + 8_000
            while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("the debounced save never ran", file.isFile)
            assertTrue(file.readText().contains(a))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theNameOwnerIsDecidedByTheOldNameSearchNotByTheOrderOfTheResponse() = runBlocking {
        val c = catalog()
        val root = JSONObject().put("results", JSONObject().put("artists", JSONObject().put("data", arr(artist(b), artist(a)))))
        var asked = 0
        c.bindCollidingNames(root) { asked++; a }               // the old name search ranks `a` first
        assertEquals(1, asked)
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
        val ids = c.hitsOf(arr(artist(b), artist(a))).map { (it as SearchHit.ArtistHit).artist.id }
        assertEquals(listOf(qb, "ar:Joey Yung"), ids)
        c.bindCollidingNames(root) { asked++; b }               // already bound: no second request
        assertEquals(1, asked)
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test fun theOldRuleIsOnlyUsedForACollisionAndOnlyIfItPicksAnArtistOfTheResponse() = runBlocking {
        val c = catalog()
        val one = JSONObject().put("results", JSONObject().put("artists", JSONObject().put("data", arr(artist(a)))))
        var asked = 0
        c.bindCollidingNames(one) { asked++; a }
        assertEquals("a name with one artist needs no request", 0, asked)
        val two = JSONObject().put("results", JSONObject().put("artists", JSONObject().put("data", arr(artist(b), artist(a)))))
        c.bindCollidingNames(two) { asked++; "999" }            // not one of the response's artists
        assertEquals(1, asked)
        assertNull("falls back to first seen", c.realArtistId("ar:Joey Yung"))
        assertEquals(listOf("ar:Joey Yung", "arx:$a:Joey Yung"), c.hitsOf(arr(artist(b), artist(a))).map { (it as SearchHit.ArtistHit).artist.id })
    }

    @Test fun searchTabsKeepBothArtistsAndStayCrashFree() {
        val c = catalog()
        val response = JSONObject().put(
            "results",
            JSONObject()
                .put("artists", JSONObject().put("data", arr(artist(a), artist(b))))
                .put("albums", JSONObject().put("data", arr(album("901", b))))
                .put("topResults", JSONObject().put("data", arr(artist(a), artist(b)))),
        ).put("meta", JSONObject().put("results", JSONObject().put("order", JSONArray(listOf("artists", "albums")))))
        val res = c.resultsFrom(response, "Joey Yung")
        val rows = res.byType.getValue(HitType.Artist)
        assertEquals(2, rows.size)
        assertFalse(rows[0].key == rows[1].key)
        assertEquals(res.top.map { it.key }.distinct(), res.top.map { it.key })
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test fun anAlbumFromThisArtistsOwnCallListsOnThisArtistWhateverNameItIsCreditedTo() {
        val c = catalog()
        c.hitsOf(arr(artist(a), artist(b)))
        c.applyArtistAlbums(qb, JSONObject().put("data", arr(album("903"), album("906", artistName = "Joey Yung & Someone"))))
        assertEquals(listOf("903", "906"), c.albumsOfArtist(qb).map { it.id })
        assertEquals("a collaboration credit keeps its own name-based artist", "ar:Joey Yung & Someone", c.albums.single { it.id == "906" }.artistId)
        assertTrue(c.albumsOfArtist("ar:Joey Yung").none { it.id == "906" })
    }

    @Test fun searchSettlesTheNameOwnerBeforeBuildingTheLists() = runBlocking {
        val c = catalog()
        val response = JSONObject().put(
            "results",
            JSONObject().put("artists", JSONObject().put("data", arr(artist(b), artist(a)))),
        ).put("meta", JSONObject().put("results", JSONObject().put("order", JSONArray(listOf("artists")))))
        val res = c.searchFrom(response, "Joey Yung") { a }       // the old name search ranks `a` first
        val ids = res.byType.getValue(HitType.Artist).map { (it as SearchHit.ArtistHit).artist.id }
        assertEquals(listOf(qb, "ar:Joey Yung"), ids)
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }
}
