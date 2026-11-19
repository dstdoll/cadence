package app.cadence.backend.real

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.engine.stream.StreamPlayback
import app.cadence.backend.engine.stream.WebPlayback
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RealCatalogAudioUriTest {

    private val catalog = RealCatalog(AmpEngine(AmpConfig("dev", "", "us", "en-US")))

    private fun info(id: String, audio: String): TrackInfo {
        val artist = Artist("a1", "Artist")
        val album = Album("al1", "Album", "a1", 2024, "Pop", emptyList())
        val track = Track(id, "Title", "a1", "al1", 1, 180_000L, audio)
        return TrackInfo(track, album, artist)
    }

    @Test
    fun returnsWebPlaybackUriWhenTrackAudioIsBlank() {
        assertEquals(
            WebPlayback.uriFor("1568453070"),
            catalog.audioUri(info(id = "1568453070", audio = "")),
        )
    }

    @Test
    fun returnsStreamUriWhenTrackAudioIsPresent() {
        assertEquals(
            StreamPlayback.uriFor("1568453070"),
            catalog.audioUri(info(id = "1568453070", audio = "https://example.com/master.m3u8")),
        )
    }

    @Test
    fun returnsEmptyWhenTrackIdIsBlank() {
        assertEquals("", catalog.audioUri(info(id = "", audio = "")))
    }
}
