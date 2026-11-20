package app.cadence.backend.real

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchHit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Search tab crashed the app with `IllegalArgumentException: Key "ar:ar:Joey Yung" was already
 * used` (a LazyColumn row key) when "Artists" was opened after searching "Joey Yung".
 *
 * Real cause, checked against Apple's public search API on 2026-11-20 (itunes.apple.com/search,
 * entity=musicArtist, both the us and mx storefronts): Apple has two different artists named exactly
 * "Joey Yung" (catalogue ids 1547065248 and 1274231511, both rap), next to the Cantopop singer
 * "容祖兒" (370528896). The app identifies an artist by name (`ar:<name>`), so the two rows got the
 * same key; the "Top results" list was already de-duplicated by key, the per-type lists were not.
 * These tests feed the real ids in Apple's response shape through the same code the UI uses.
 */
class SearchDuplicateHitsTest {
    private val rapperA = "1274231511" // the order the mx storefront returned them in
    private val rapperB = "1547065248"
    private val singer = "370528896"

    private fun catalog() = RealCatalog(AmpEngine(AmpConfig("", "", "us", "en-US")))

    private fun artist(id: String, name: String) = JSONObject(
        """{"id":"$id","type":"artists","attributes":{"name":"$name","genreNames":["Hip-Hop/Rap"]}}""",
    )

    private fun album(id: String, title: String, artistName: String) = JSONObject(
        """{"id":"$id","type":"albums","attributes":{"name":"$title","artistName":"$artistName","releaseDate":"2004-05-18","genreNames":["Pop"],"trackCount":10}}""",
    )

    private fun array(vararg items: JSONObject) = JSONArray().also { a -> items.forEach(a::put) }

    /** Apple's search response: the sections, plus `topResults` repeating the best of them. */
    private fun response(artists: JSONArray, albums: JSONArray? = null, top: JSONArray = artists) = JSONObject().put(
        "results",
        JSONObject().apply {
            put("artists", JSONObject().put("data", artists))
            albums?.let { put("albums", JSONObject().put("data", it)) }
            put("topResults", JSONObject().put("data", top))
        },
    ).put("meta", JSONObject().put("results", JSONObject().put("order", JSONArray(listOf("artists", "albums")))))

    private fun List<SearchHit>.keys() = map { it.key }

    @Test
    fun twoArtistsWithTheSameNameGetTwoRowsWithUniqueKeys() {
        val res = catalog().resultsFrom(
            response(array(artist(rapperA, "Joey Yung"), artist(rapperB, "Joey Yung"), artist(singer, "容祖兒"))),
            "Joey Yung",
        )
        val artists = res.byType.getValue(HitType.Artist)
        assertEquals("duplicate keys in the Artists tab: ${artists.keys()}", artists.keys().distinct(), artists.keys())
        assertEquals(listOf("Joey Yung", "Joey Yung", "容祖兒"), artists.map { (it as SearchHit.ArtistHit).artist.name })
        assertEquals("duplicate keys in Top results", res.top.keys().distinct(), res.top.keys())
    }

    @Test
    fun theRowThatSurvivesOpensTheArtistAppleRankedFirst() {
        val catalog = catalog()
        catalog.resultsFrom(response(array(artist(rapperA, "Joey Yung"), artist(rapperB, "Joey Yung"))), "Joey Yung")
        // Before the fix the second record overwrote the first, so the row opened the wrong artist.
        assertEquals(rapperA, catalog.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun theSameArtistRepeatedIsOneRowAndTouchesTheCachesOnce() {
        val catalog = catalog()
        val hits = catalog.hitsOf(array(artist(rapperA, "Joey Yung"), artist(rapperA, "Joey Yung")))
        assertEquals(1, hits.size)
        assertEquals(rapperA, catalog.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun theSameAlbumListedTwiceIsOneRow() {
        val a = album("542212024", "Give Love a Break", "Joey Yung")
        val res = catalog().resultsFrom(response(array(artist(singer, "容祖兒")), albums = array(a, a)), "give love")
        val albums = res.byType.getValue(HitType.Album)
        assertEquals(1, albums.size)
        assertEquals(albums.keys().distinct(), albums.keys())
    }

    @Test
    fun suggestionMatchesAreUniqueToo() {
        val matches = catalog().hitsOf(array(artist(rapperA, "Joey Yung"), artist(rapperB, "Joey Yung"), artist(singer, "容祖兒"))).take(6)
        assertEquals(matches.keys().distinct(), matches.keys())
    }

    @Test
    fun distinctResultsKeepAppleOrderAndScores() {
        val hits = catalog().hitsOf(array(artist("1", "Alpha"), artist("2", "Beta"), artist("3", "Gamma")))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), hits.map { (it as SearchHit.ArtistHit).artist.name })
        assertEquals(listOf(100, 99, 98), hits.map { it.score })
    }

    @Test
    fun aResourceWithoutAnIdOrNameIsStillSkippedNotCrashed() {
        val broken = JSONObject("""{"id":"9","type":"artists","attributes":{}}""")
        val noId = JSONObject("""{"type":"albums","attributes":{"name":"x","artistName":"y"}}""")
        val hits = catalog().hitsOf(array(broken, noId, artist("1", "Alpha")))
        assertEquals(listOf("Alpha"), hits.map { (it as SearchHit.ArtistHit).artist.name })
        assertTrue(hits.all { it.score >= 1 })
    }
}
