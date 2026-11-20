package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun same(t: String, ar: String, al: String, tt: String?, tar: String?, tal: String?) = LegacyDownloads.sameSong(t, ar, al, tt, tar, tal)

    @Test fun theSameSongIsRecognisedWhateverTheCaseOrSpacing() {
        assertTrue(same("Breathe (In the Air)", "Pink Floyd", "The Dark Side of the Moon", "breathe  (in the air)", "PINK FLOYD", "the dark side of the moon"))
    }

    @Test fun anotherArtistOrTitleIsNotTheSong() {
        assertFalse(same("Breathe", "Pink Floyd", "A", "Breathe", "The Prodigy", "A"))
        assertFalse(same("Breathe", "Pink Floyd", "A", "Breathe Out", "Pink Floyd", "A"))
        assertFalse("no artist tag is not a match", same("Breathe", "Pink Floyd", "A", "Breathe", null, "A"))
    }

    @Test fun aCompilationOfTheSameSongOnAnotherAlbumIsNotTheDownloadedFile() {
        assertFalse(same("Money", "Pink Floyd", "The Dark Side of the Moon", "Money", "Pink Floyd", "Echoes: The Best of Pink Floyd"))
    }

    @Test fun anArtistTagWithSeveralNamesStillMatches() {
        assertTrue(same("Song", "Daft Punk", "Album", "Song", "Daft Punk & Pharrell Williams", "Album"))
        assertTrue(same("Song", "Daft Punk & Pharrell Williams", "Album", "Song", "Daft Punk", "Album"))
    }

    @Test fun anUnknownAlbumTagDoesNotBlockTheMatch() {
        assertTrue(same("Song", "Artist", "Album", "Song", "Artist", ""))
        assertTrue(same("Song", "Artist", "", "Song", "Artist", "Album"))
    }

    @Test fun theRegistryListsEveryTrackWithAFileAndForgetsDeletedOnes() {
        val alive = mutableSetOf("u1", "u2")
        val store = DownloadRegistryStore(tmp.newFile("r.json"), exists = { it in alive })
        store.record(DownloadRecord("t1", "aac:128", "u1", "a.m4a", 1))
        store.record(DownloadRecord("t2", "", "u2", "b.m4a", 2))
        store.record(DownloadRecord("t1", "alac:24:0", "u3", "c.m4a", 3))
        assertEquals(setOf("t1", "t2"), store.downloadedTrackIds())
        alive.remove("u2")
        assertEquals("t2's only file is gone", setOf("t1"), store.downloadedTrackIds())
    }
}
