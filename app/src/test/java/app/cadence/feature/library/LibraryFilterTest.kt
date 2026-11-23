package app.cadence.feature.library

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {

    private val artistA = Artist(id = "ar1", name = "Daft Punk")
    private val artistB = Artist(id = "ar2", name = "Nujabes")
    private val albumA = Album(id = "al1", title = "Discovery", artistId = "ar1", year = 2001, genre = "Electronic", colors = listOf("#000000"))
    private val albumB = Album(id = "al2", title = "Modal Soul", artistId = "ar2", year = 2005, genre = "Hip-Hop", colors = listOf("#000000"))
    private val trackA = TrackInfo(Track(id = "t1", title = "One More Time", artistId = "ar1", albumId = "al1", number = 1, durationMs = 320_000, audio = ""), albumA, artistA)
    private val trackB = TrackInfo(Track(id = "t2", title = "Feather", artistId = "ar2", albumId = "al2", number = 1, durationMs = 175_000, audio = ""), albumB, artistB)

    @Test
    fun filterTracks_matchesTitleArtistOrAlbumCaseInsensitive() {
        val list = listOf(trackA, trackB)
        assertEquals(listOf(trackA), filterTracks(list, "one more"))
        assertEquals(listOf(trackB), filterTracks(list, "nujabes"))
        assertEquals(listOf(trackA), filterTracks(list, "DISCOVERY"))
        assertEquals(list, filterTracks(list, "   "))
    }

    @Test
    fun filterAlbums_matchesTitleOrArtist() {
        val list = listOf(albumA, albumB)
        val artists = mapOf("ar1" to "Daft Punk", "ar2" to "Nujabes")
        assertEquals(listOf(albumB), filterAlbums(list, { artists[it].orEmpty() }, "modal"))
        assertEquals(listOf(albumA), filterAlbums(list, { artists[it].orEmpty() }, "daft"))
    }

    @Test
    fun filterArtists_matchesName() {
        assertEquals(listOf(artistB), filterArtists(listOf(artistA, artistB), "nuj"))
    }

    @Test
    fun filterPlaylists_matchesTitleOrDescription() {
        val p1 = Playlist(id = "p1", title = "Late Night", description = "Chill beats", trackIds = emptyList())
        val p2 = Playlist(id = "p2", title = "Workout", description = "High energy", trackIds = emptyList())
        assertEquals(listOf(p1), filterPlaylists(listOf(p1, p2), "chill"))
        assertEquals(listOf(p2), filterPlaylists(listOf(p1, p2), "work"))
    }

    @Test
    fun filterGenres_matchesSubstring() {
        assertEquals(listOf("Hip-Hop"), filterGenres(listOf("Electronic", "Hip-Hop", "Jazz"), "hip"))
    }
}
