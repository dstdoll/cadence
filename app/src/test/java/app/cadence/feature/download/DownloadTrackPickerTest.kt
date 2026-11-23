package app.cadence.feature.download

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import app.cadence.backend.engine.media.QualityProbeResult
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTrackPickerTest {

    @Test
    fun toggleTrack_addsAndRemovesTrackId() {
        val items = listOf(
            DownloadItem("t1", "Song 1", "Artist", "Album", "", null, "album"),
            DownloadItem("t2", "Song 2", "Artist", "Album", "", null, "album"),
        )
        val req = DownloadRequest.Choose(items, QualityProbeResult(emptyList(), 0, 0, 0), 0, emptyMap())
        assertEquals(setOf("t1", "t2"), req.selectedTracks)
        val toggled = req.toggleTrack("t1")
        assertEquals(setOf("t2"), toggled.selectedTracks)
        assertEquals(setOf("t1", "t2"), toggled.toggleTrack("t1").selectedTracks)
    }
}
