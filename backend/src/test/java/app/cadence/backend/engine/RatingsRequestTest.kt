package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ratings write is what pushes a favourite to the real Apple account (the "Favourite Songs"
 * system playlist is populated from song ratings). These pin the request shape — the path is the
 * subscriber-scoped song-ratings resource and the body is the documented rating payload with the
 * love value 1 — so a refactor can't silently change what gets sent to the account.
 */
class RatingsRequestTest {
    @Test
    fun pathTargetsTheSongRatingResource() {
        assertEquals("ratings/songs/1440857781", AmpEngine.ratingsPath("1440857781"))
    }

    @Test
    fun favouriteBodyIsTheLoveRating() {
        assertEquals("""{"type":"rating","attributes":{"value":1}}""", AmpEngine.ratingBody(1))
    }

    @Test
    fun dislikeBodyIsTheNegativeRating() {
        assertEquals("""{"type":"rating","attributes":{"value":-1}}""", AmpEngine.ratingBody(-1))
    }
}
