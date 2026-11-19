package app.cadence.state.library

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device library model. It is persisted as JSON, so these guard both the serialisation
 * (round-trip + forward/backward compatibility of new fields) and the pure state transitions that
 * every library/favourite/follow action depends on.
 */
class UserLibraryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun roundTrip(lib: UserLibrary): UserLibrary =
        json.decodeFromString(UserLibrary.serializer(), json.encodeToString(UserLibrary.serializer(), lib))

    @Test fun round_trip_preserves_all_fields() {
        val lib = UserLibrary(
            savedTracks = setOf("t1", "t2"),
            savedAlbums = setOf("al1"),
            favorites = setOf("t1"),
            followedArtists = setOf("ar1", "ar2"),
            pinned = listOf("al:al1", "t:t2"),
            recentTracks = listOf("t2", "t1"),
        )
        assertEquals(lib, roundTrip(lib))
    }

    @Test fun old_json_without_new_fields_still_loads() {
        // A save written before followedArtists existed must deserialise with the default empty set.
        val old = """{"savedTracks":["t1"],"favorites":["t1"]}"""
        val lib = json.decodeFromString(UserLibrary.serializer(), old)
        assertEquals(setOf("t1"), lib.savedTracks)
        assertTrue(lib.followedArtists.isEmpty())
    }

    @Test fun add_and_remove_from_library() {
        var lib = UserLibrary().addToLibrary(listOf("t1", "t2"), albumId = "al1")
        assertTrue(lib.inLibrary("t1"))
        assertTrue("al1" in lib.savedAlbums)
        lib = lib.removeFromLibrary("t1")
        assertFalse(lib.inLibrary("t1"))
        assertTrue(lib.inLibrary("t2"))
    }

    @Test fun favorite_adds_to_library_and_removing_from_library_clears_it() {
        var lib = UserLibrary().toggleFavorite("t1", addToLibrary = true)
        assertTrue("t1" in lib.favorites)
        assertTrue("favoriting adds to library", lib.inLibrary("t1"))
        lib = lib.removeFromLibrary("t1")
        assertFalse("removing from library drops the favorite", "t1" in lib.favorites)
    }

    @Test fun favorite_toggles_off() {
        val lib = UserLibrary().toggleFavorite("t1").toggleFavorite("t1")
        assertFalse("t1" in lib.favorites)
    }

    @Test fun follow_toggles() {
        var lib = UserLibrary().toggleFollow("ar1")
        assertTrue("ar1" in lib.followedArtists)
        lib = lib.toggleFollow("ar1")
        assertFalse("ar1" in lib.followedArtists)
    }

    @Test fun history_is_most_recent_first_deduped_and_capped() {
        var lib = UserLibrary()
        (1..60).forEach { lib = lib.played("t$it") }
        lib = lib.played("t5") // replaying moves it to the front, no duplicate
        assertEquals("t5", lib.recentTracks.first())
        assertEquals(lib.recentTracks.distinct(), lib.recentTracks)
        assertTrue("capped at 50", lib.recentTracks.size <= 50)
    }

    @Test fun create_playlist_adds_it_and_its_songs() {
        val (lib, p) = UserLibrary().createPlaylist("Road Trip", listOf("t1", "t2"), addSongsToLibrary = true)
        assertTrue(p.userCreated)
        assertEquals(listOf("t1", "t2"), p.trackIds)
        assertTrue(lib.playlists.any { it.id == p.id })
        assertTrue(lib.inLibrary("t1"))
    }
}
