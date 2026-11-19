package app.cadence.backend.search

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecommenderAvailabilityTest {

    private fun info(
        id: String,
        artistId: String,
        albumId: String,
        genre: String,
        available: Boolean = true,
    ): TrackInfo = TrackInfo(
        track = Track(id, "Track $id", artistId, albumId, 1, 180_000L, "", available = available),
        album = Album(albumId, "Album $albumId", artistId, 2022, genre, listOf("#000000"), available = available),
        artist = Artist(artistId, "Artist $artistId"),
    )

    @Test
    fun similarFallsBackToSameGenreAndCatalogueWhenArtistTracksExhausted() {
        val seed = info("1", "ar1", "al1", "Electronic")
        val sameGenre = info("2", "ar2", "al2", "Electronic")
        val otherGenre = info("3", "ar3", "al3", "Rock")
        val unavailable = info("4", "ar1", "al1", "Electronic", available = false)

        val pool = listOf(seed, sameGenre, otherGenre, unavailable).filter { it.track.available }
        val picks = Recommender.similar(seed, pool, exclude = emptySet(), count = 5, random = Random(42))

        // Same-genre track ranks ahead of unrelated-genre track, and both are returned so autoplay never stalls.
        assertEquals(listOf("2", "3"), picks.map { it.id })
        assertFalse(picks.any { it.id == "4" })
    }

    @Test
    fun discoveryExcludesUnavailableTracks() {
        val ok = info("1", "ar1", "al1", "Pop", available = true)
        val locked = info("2", "ar2", "al2", "Pop", available = false)
        val picks = Recommender.discovery(listOf(ok, locked).filter { it.track.available }, emptySet(), 5, Random(1))
        assertTrue(picks.map { it.id } == listOf("1"))
    }
}
