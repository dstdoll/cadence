package app.cadence.feature.library

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Library artists count every song they are credited on, and never read "0 songs". */
class LibraryArtistCountTest {
    private val album = Album("al", "Album", "ar:x", 2020, "Pop", listOf("#ffffff"))
    private fun song(id: String, credit: String) =
        TrackInfo(Track(id, id, "ar:$credit", album.id, 1, 1000, "u"), album, Artist("ar:$credit", credit))

    @Test
    fun collaborationsCountForEachCreditedArtist() {
        val counts = songsPerArtistName(
            listOf(
                song("1", "North Pole & The Penguins"),
                song("2", "Lumen / Iris Vale / Sound Team / Game Music"),
                song("3", "Sound Team"),
                song("4", "Kite feat. Rowan"),
                song("5", "Kite, Rowan"),
            ),
        )
        assertEquals(1, counts["north pole"])
        assertEquals(1, counts["north pole & the penguins"])
        assertEquals(2, counts["sound team"])
        assertEquals(2, counts["kite"])
        assertEquals(2, counts["rowan"])
        assertEquals(null, counts["the"])
    }

    @Test
    fun aBandNameWithAndStaysWhole() {
        assertEquals(setOf("salt and pepper"), creditedNames("Salt and Pepper"))
    }

    @Test
    fun theSummaryFallsBackToAlbumsThenANote() {
        assertEquals(ArtistLine.Songs(3), artistLine(songs = 3, albums = 1))
        assertEquals(ArtistLine.Albums(1), artistLine(songs = 0, albums = 1))
        assertEquals(ArtistLine.InLibrary, artistLine(songs = 0, albums = 0))
    }
}
