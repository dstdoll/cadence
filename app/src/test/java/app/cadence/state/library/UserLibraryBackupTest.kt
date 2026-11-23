package app.cadence.state.library

import app.cadence.backend.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLibraryBackupTest {

    @Test
    fun roundTrip_mergeAndReplacePreserveAndSanitizeData() {
        val original = UserLibrary(
            savedTracks = setOf("100", "200"),
            savedAlbums = setOf("al1"),
            favorites = setOf("100"),
            pinned = listOf("al:al1"),
            playlists = listOf(Playlist(id = "u1", title = "Chill", trackIds = listOf("100"), userCreated = true)),
        )
        val json = original.toBackupJson()

        val existing = UserLibrary(savedTracks = setOf("999"), favorites = setOf("999"))
        val merged = existing.restoreFromBackupJson(json, merge = true).getOrThrow()
        assertEquals(setOf("999", "100", "200"), merged.savedTracks)
        assertEquals(setOf("999", "100"), merged.favorites)
        assertEquals(1, merged.playlists.size)

        val replaced = existing.restoreFromBackupJson(json, merge = false).getOrThrow()
        assertEquals(setOf("100", "200"), replaced.savedTracks)
        assertEquals(setOf("100"), replaced.favorites)
    }

    @Test
    fun invalidJson_returnsFailure() {
        val res = UserLibrary().restoreFromBackupJson("{not valid json", merge = true)
        assertTrue(res.isFailure)
    }

    @Test
    fun duplicatePlaylist_clonesTitleTracksAndDescription() {
        val src = Playlist(id = "p1", title = "Roadtrip", description = "Highway", trackIds = listOf("1", "2"))
        val (updated, dup) = UserLibrary().duplicatePlaylist(src, "Copy")
        assertEquals("Roadtrip (Copy)", dup.title)
        assertEquals("Highway", dup.description)
        assertEquals(listOf("1", "2"), dup.trackIds)
        assertTrue(dup.userCreated)
        assertEquals(1, updated.playlists.size)
    }
}
