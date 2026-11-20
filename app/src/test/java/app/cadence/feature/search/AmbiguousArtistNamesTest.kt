package app.cadence.feature.search

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.search.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbiguousArtistNamesTest {
    private fun hit(id: String, name: String) = SearchHit.ArtistHit(Artist(id, name, genres = listOf("Rap")), 1)

    @Test fun onlyNamesSharedByMoreThanOneArtistRowAreAmbiguous() {
        val rows = listOf(hit("ar:Joey Yung", "Joey Yung"), hit("arx:1547065248:Joey Yung", "Joey Yung"), hit("ar:容祖兒", "容祖兒"))
        assertEquals(setOf("Joey Yung"), ambiguousArtistNames(rows))
    }

    @Test fun otherKindsOfRowsAndAnEmptyListNeverCount() {
        val album = SearchHit.AlbumHit(Album("1", "Joey Yung", "ar:Joey Yung", 2020, "Pop", listOf("#000")), "Joey Yung", 1)
        assertEquals(emptySet<String>(), ambiguousArtistNames(listOf(album, album)))
        assertEquals(emptySet<String>(), ambiguousArtistNames(emptyList()))
    }
}
