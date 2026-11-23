package app.cadence.core.design

import app.cadence.backend.model.AlacCheckReport
import app.cadence.backend.model.AlacCheckStatus
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AlacReportFormatTest {

    @Test
    fun formatAlacReportText_includesMetadataAndPcmMetrics() {
        val artist = Artist(id = "ar1", name = "Daft Punk")
        val album = Album(id = "al1", title = "Discovery", artistId = "ar1", year = 2001, genre = "Electronic", colors = listOf("#000000"))
        val track = TrackInfo(
            Track(id = "6783930825", title = "Across the Sea", artistId = "ar1", albumId = "al1", number = 1, durationMs = 240_000, audio = ""),
            album,
            artist,
        )
        val report = AlacCheckReport(
            status = AlacCheckStatus.CLEAN,
            totalFrames = 2977,
            escapeFrames = 0,
            bits = 24,
            sampleRate = 96000,
            software = "1.25.36.42",
            effectiveBits = 16,
            bandwidthHz = 22050,
            upsampledSuspect = true,
        )

        val text = formatAlacReportText("Across the Sea", "cl", listOf(track to report), 1_700_000_000_000L)
        assertTrue(text.contains("Storefront: cl"))
        assertTrue(text.contains("Across the Sea [6783930825]: CLEAN"))
        assertTrue(text.contains("effBits=16"))
        assertTrue(text.contains("bw=22050Hz"))
        assertTrue(text.contains("upsampled=true"))
    }
}
