package app.cadence.state.library

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Favourites come from two places: the app's own set and the Apple account's playlist. */
class FavoritesTest {
    private val account = setOf("a1", "a2")

    @Test
    fun undoFavoriteWorksForASongFavouritedOnTheAccount() {
        var lib = UserLibrary()
        assertTrue(lib.isFavorite("a1", account))

        lib = lib.toggleFavorite("a1", account = account)
        assertFalse("Undo Favorite left the song a favourite", lib.isFavorite("a1", account))
        assertFalse("a1" in lib.favorites)

        lib = lib.toggleFavorite("a1", account = account)
        assertTrue(lib.isFavorite("a1", account))
        assertFalse("re-favouriting clears the local undo", "a1" in lib.unfavorited)
    }

    @Test
    fun localFavouritesBehaveAsBefore() {
        var lib = UserLibrary().toggleFavorite("t1", addToLibrary = true, account = account)
        assertTrue(lib.isFavorite("t1", account))
        assertTrue(lib.inLibrary("t1"))
        lib = lib.toggleFavorite("t1", account = account)
        assertFalse(lib.isFavorite("t1", account))
        assertTrue("a local song is never recorded as an account undo", lib.unfavorited.isEmpty())
    }

    @Test
    fun favoriteIdsAreTheUnionMinusUndone() {
        val lib = UserLibrary(favorites = setOf("t1")).toggleFavorite("a2", account = account)
        assertEquals(setOf("t1", "a1"), lib.favoriteIds(account))
    }

    @Test
    fun aLibrarySavedBeforeThisFieldStillLoads() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(UserLibrary.serializer(), """{"favorites":["t1"]}""")
        assertTrue(old.unfavorited.isEmpty())
        assertTrue(old.isFavorite("t1"))
    }
}
