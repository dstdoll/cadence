package app.cadence.feature.library

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Artist rows show the artist photo, else their newest album cover, never someone else's. */
class ArtistArtTest {
    private fun album(id: String, artist: String, year: Int, cover: String?) =
        Album(id = id, title = id, artistId = artist, year = year, genre = "Pop", colors = listOf("#112233"), cover = cover)

    private val albums = listOf(
        album("old", "a1", 2001, "old.jpg"),
        album("new", "a1", 2020, "new.jpg"),
        album("newest-no-cover", "a1", 2024, null),
        album("other", "a2", 2025, "other.jpg"),
    )

    @Test
    fun theArtistPhotoWins() = assertEquals("photo.jpg", artistArt(Artist("a1", "A", cover = "photo.jpg"), albums))

    @Test
    fun withoutAPhotoTheNewestAlbumWithACover() = assertEquals("new.jpg", artistArt(Artist("a1", "A"), albums))

    @Test
    fun anotherArtistsCoverIsNeverUsed() = assertNull(artistArt(Artist("a3", "C"), albums))

    @Test
    fun noCoversAnywhereGivesThePlaceholder() = assertNull(artistArt(Artist("a1", "A"), albums.map { it.copy(cover = null) }))
}
