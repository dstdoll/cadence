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

    @Test
    fun playlistFolders_createMoveDeleteAndBackupRoundTrip() {
        val (withFolder, folder) = UserLibrary().createFolder("Ambient")
        val moved = withFolder.movePlaylistToFolder("p1", folder.id)
        assertEquals(folder.id, moved.playlistFolders["p1"])

        val restored = UserLibrary().restoreFromBackupJson(moved.toBackupJson(), merge = false).getOrThrow()
        assertEquals(1, restored.folders.size)
        assertEquals(folder.id, restored.playlistFolders["p1"])

        val deleted = restored.deleteFolder(folder.id)
        assertTrue(deleted.folders.isEmpty())
        assertTrue(deleted.playlistFolders.isEmpty())
    }
}
