package app.cadence.backend.real

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.search.SearchHit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An artist row opens the catalogue artist it was bound to. Several code paths bind a display id
 * (`ar:<name>`) to a catalogue id: search artists, the `relationships.artists` of every album/song
 * hit, the artist page and its album list. They used to overwrite each other, so with two different
 * artists named "Joey Yung" (ids 1274231511 and 1547065248, real Apple data) one album hit carrying
 * the other artist's id flipped the row to the wrong artist. The first binding must stay.
 */
class ArtistBindingHotfixTest {
    private val a = "1274231511"
    private val b = "1547065248"

    private fun catalog() = RealCatalog(AmpEngine(AmpConfig("", "", "us", "en-US")))
    private fun artist(id: String) = JSONObject("""{"id":"$id","type":"artists","attributes":{"name":"Joey Yung"}}""")

    /** An album by "Joey Yung" whose `relationships.artists` names [artistId] (ids only, as Apple documents). */
    private fun album(id: String, artistId: String) = JSONObject(
        """{"id":"$id","type":"albums","attributes":{"name":"Album $id","artistName":"Joey Yung","releaseDate":"2010-01-01","genreNames":["Rap"]},""" +
            """"relationships":{"artists":{"data":[{"id":"$artistId","type":"artists"}]}}}""",
    )

    private fun array(vararg o: JSONObject) = JSONArray().also { arr -> o.forEach(arr::put) }

    @Test
    fun anAlbumHitCarryingTheOtherArtistsIdDoesNotRebindTheRow() {
        val c = catalog()
        c.hitsOf(array(artist(a), artist(b)))
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
        c.hitsOf(array(album("901", b)))
        assertEquals("the first binding must stay", a, c.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun aSearchResponseWithArtistsThenAlbumsKeepsTheFirstArtist() {
        val c = catalog()
        val response = JSONObject().put(
            "results",
            JSONObject()
                .put("artists", JSONObject().put("data", array(artist(a), artist(b))))
                .put("albums", JSONObject().put("data", array(album("901", b), album("902", b))))
                .put("topResults", JSONObject().put("data", array(artist(a), artist(b)))),
        ).put("meta", JSONObject().put("results", JSONObject().put("order", JSONArray(listOf("artists", "albums")))))
        c.resultsFrom(response, "Joey Yung")
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun aLaterSearchListingTheArtistsInAnotherOrderDoesNotRebindEither() {
        val c = catalog()
        c.hitsOf(array(artist(a), artist(b)))          // e.g. the mx storefront's order
        c.hitsOf(array(artist(b), artist(a)))          // e.g. the us storefront's order
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun anAlbumHitBindsAnUnboundRowAndIsThenKept() {
        val c = catalog()
        c.hitsOf(array(album("901", a)))
        assertEquals("nothing bound yet: the album's artist binds", a, c.realArtistId("ar:Joey Yung"))
        c.hitsOf(array(album("902", b)))
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
    }

    @Test
    fun bindCatalogIdBindsOnlyWhenNothingIsBound() {
        val c = catalog()
        assertEquals(true, c.bindCatalogId("ar:X", "1"))
        assertEquals("1", c.realArtistId("ar:X"))
        assertEquals("a different id does not replace it", false, c.bindCatalogId("ar:X", "2"))
        assertEquals("1", c.realArtistId("ar:X"))
        assertEquals("the same id again is fine", true, c.bindCatalogId("ar:X", "1"))
        assertEquals("a blank id never binds", false, c.bindCatalogId("ar:Y", " "))
        assertEquals(null, c.realArtistId("ar:Y"))
    }

    private fun artistWithPhoto(id: String, photo: String) = JSONObject(
        """{"id":"$id","type":"artists","attributes":{"name":"Joey Yung","artwork":{"url":"https://img.example/$photo/{w}x{h}bb.jpg","width":1000,"height":1000}}}""",
    )

    @Test
    fun aLaterSearchKeepsEachArtistsOwnPhotoAndId() {
        val c = catalog()
        c.hitsOf(array(artistWithPhoto(a, "a")))
        val later = c.hitsOf(array(artistWithPhoto(b, "b"))).single() as SearchHit.ArtistHit
        assertEquals("the second artist has its own record", "https://img.example/b/300x300bb.jpg", later.artist.cover)
        assertEquals("arx:$b:Joey Yung", later.artist.id)
        assertEquals("the first artist keeps its photo and the plain name", "https://img.example/a/300x300bb.jpg", c.artists.single { it.id == "ar:Joey Yung" }.cover)
        assertEquals(a, c.realArtistId("ar:Joey Yung"))
        assertEquals(b, c.realArtistId(later.artist.id))
    }
}
