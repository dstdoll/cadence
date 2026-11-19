package app.cadence.backend.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue parsing. Feeds the amp-api JSON shapes the real backend sees into [Mapping] and asserts
 * the model it produces, so a schema drift or a regression in field mapping (release date, ℗/label,
 * track count, single flag, artwork palette, id prefixes) is caught offline before a push.
 */
class MappingTest {
    private fun obj(json: String) = JSONObject(json)

    @Test fun album_maps_core_and_footer_fields() {
        val a = Mapping.album(
            obj(
                """{"id":"1440","type":"albums","attributes":{
                  "name":"Ants From Up There","artistName":"Black Country, New Road",
                  "releaseDate":"2022-02-04","genreNames":["Alternative","Music"],
                  "trackCount":12,"isSingle":false,
                  "copyright":"℗ 2022 Ninja Tune","recordLabel":"Ninja Tune",
                  "artwork":{"url":"https://is1.mzstatic.com/a/{w}x{h}bb.jpg","bgColor":"e02200","textColor1":"ffffff"}}}""",
            ),
        )!!
        assertEquals("1440", a.id)
        assertEquals("Ants From Up There", a.title)
        assertEquals("ar:Black Country, New Road", a.artistId)
        assertEquals(2022, a.year)
        assertEquals("Alternative", a.genre)
        assertEquals("2022-02-04", a.released)
        assertEquals(12, a.trackCount)
        assertFalse(a.isSingle)
        assertEquals("℗ 2022 Ninja Tune", a.copyright)
        assertEquals("Ninja Tune", a.recordLabel)
        // cover resolved to the small display size from the {w}x{h} template
        assertEquals("https://is1.mzstatic.com/a/300x300bb.jpg", a.cover)
        // vibrant palette: the saturated red beats the white text colour
        assertEquals("#e02200", a.colors.first())
    }

    @Test fun album_missing_optionals_fall_back() {
        val a = Mapping.album(obj("""{"id":"7","attributes":{"name":"X","artistName":"Y"}}"""))!!
        assertEquals(0, a.year)
        assertEquals("Music", a.genre)
        assertNull(a.released)
        assertNull(a.copyright)
        assertNull(a.recordLabel)
        assertEquals(0, a.trackCount)
        assertFalse(a.isSingle)
        assertNull(a.cover)
        assertEquals(ArtworkPalette.DEFAULT, a.colors)
    }

    @Test fun album_requires_id_and_attributes() {
        assertNull(Mapping.album(obj("""{"attributes":{"name":"X"}}""")))
        assertNull(Mapping.album(obj("""{"id":"1"}""")))
    }

    @Test fun single_detected_by_flag_and_small_count() {
        val a = Mapping.album(obj("""{"id":"2","attributes":{"name":"S","artistName":"A","isSingle":true,"trackCount":1}}"""))!!
        assertTrue(a.isSingle)
        assertEquals(1, a.trackCount)
    }

    @Test fun artist_maps_name_tint_and_cover() {
        val a = Mapping.artist(
            obj("""{"id":"ar1","attributes":{"name":"Mira Okafor","artwork":{"url":"x/{w}x{h}bb.jpg","bgColor":"112233"}}}"""),
        )!!
        assertEquals("ar:Mira Okafor", a.id)
        assertEquals("Mira Okafor", a.name)
        assertEquals("#112233", a.color)
        assertEquals("x/300x300bb.jpg", a.cover)
    }

    @Test fun artist_without_artwork_uses_default_tint() {
        val a = Mapping.artist(obj("""{"id":"x","attributes":{"name":"N"}}"""))!!
        assertEquals("#888888", a.color)
        assertNull(a.cover)
    }

    @Test fun artist_requires_a_name() {
        assertNull(Mapping.artist(obj("""{"id":"x","attributes":{"name":""}}""")))
        assertNull(Mapping.artist(obj("""{"id":"x"}""")))
    }

    @Test fun playlist_prefixes_id_and_resolves_curator_and_description() {
        val p = Mapping.playlist(
            obj("""{"id":"pl.u-abc","attributes":{"name":"Chill","curatorName":"Apple Music","description":{"short":"Relax"}}}"""),
        )!!
        assertEquals("pl:pl.u-abc", p.id)
        assertEquals("Chill", p.title)
        assertEquals("Relax", p.description)
        assertEquals("Apple Music", p.curator)
        assertFalse(p.userCreated)
        assertTrue(p.trackIds.isEmpty())
    }

    @Test fun station_prefixes_id_and_labels_live_vs_vod() {
        val live = Mapping.station(obj("""{"id":"ra.123","attributes":{"name":"Hits","isLive":true}}"""))!!
        assertEquals("st:ra.123", live.id)
        assertEquals("Live radio", live.description)
        val vod = Mapping.station(obj("""{"id":"ra.9","attributes":{"name":"Chill Radio"}}"""))!!
        assertEquals("st:ra.9", vod.id)
        assertEquals("Apple Music Radio", vod.description)
    }

    @Test fun albumFromSong_uses_album_name_and_the_given_ids() {
        val a = Mapping.albumFromSong(
            obj("""{"attributes":{"albumName":"Deluxe","releaseDate":"2021-06-10","genreNames":["Pop"]}}"""),
            "al42",
            "ar:Z",
        )
        assertEquals("al42", a.id)
        assertEquals("Deluxe", a.title)
        assertEquals("ar:Z", a.artistId)
        assertEquals(2021, a.year)
        assertEquals("Pop", a.genre)
    }

    @Test fun library_playlist_is_prefixed_and_owned() {
        val p = Mapping.libraryPlaylist(obj("""{"id":"p.123","attributes":{"name":"My Mix"}}"""))!!
        assertEquals("lp:p.123", p.id)
        assertEquals("My Mix", p.title)
        assertEquals("Your library", p.curator)
    }
}
