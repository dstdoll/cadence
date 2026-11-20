package app.cadence.feature.download

import app.cadence.backend.engine.DownloadEngine.DownloadItem
import app.cadence.backend.engine.media.DownloadRecord
import app.cadence.backend.engine.media.QualityOption
import app.cadence.backend.engine.media.QualityProbeResult
import app.cadence.backend.engine.media.RenditionSpec
import app.cadence.state.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DownloadRequestStateTest {
    private fun item(id: String) = DownloadItem(id, "T$id", "A", "Al", "https://x/$id.m3u8")
    private fun rec(track: String, spec: String, at: Long = 1) = DownloadRecord(track, spec, "content://m/$track/$spec", "n", at)
    private fun stereo(spec: String, highest: Boolean = false) = QualityOption(if (spec.startsWith("alac")) "lossless" else "aac", RenditionSpec.parse(spec), 0, 1, 1, 0, 0, highest)
    private val atmos = QualityOption("atmos", null, 768, 1, 1, 0, 0)
    private val spatial = QualityOption("spatial", null, 0, 1, 1, 0, 0)

    // ---- what a request does first ---------------------------------------------------------------------------------

    @Test fun askingIsTheDefaultAndAlwaysShowsThePicker() {
        assertEquals(RequestStep.Pick, requestStep(asks = true, alreadyDownloaded = false))
        assertEquals("the picker also tells about existing files", RequestStep.Pick, requestStep(asks = true, alreadyDownloaded = true))
    }

    @Test fun withTheDefaultInUseADownloadStartsAtOnceUnlessItWouldAddASecondCopy() {
        assertEquals(RequestStep.Enqueue, requestStep(asks = false, alreadyDownloaded = false))
        assertEquals(RequestStep.Confirm, requestStep(asks = false, alreadyDownloaded = true))
    }

    // ---- the files that already exist ------------------------------------------------------------------------------

    @Test fun oneSongAlreadyDownloadedReportsItsQuality() {
        val e = summarizeExisting(listOf(item("1")), mapOf("1" to listOf(rec("1", "alac:24:44100", 20), rec("1", "aac:128", 30))))
        assertEquals(listOf(1, 1), listOf(e.songs, e.total))
        assertEquals(listOf("alac:24:44100", "aac:128"), e.specs)
        assertEquals("the newest file", "aac:128", e.newest!!.spec)
    }

    @Test fun anAlbumCountsOnlyTheSongsThatHaveAFile() {
        val items = (1..12).map { item("$it") }
        val e = summarizeExisting(items, mapOf("3" to listOf(rec("3", "aac:256")), "5" to listOf(rec("5", "aac:256")), "9" to listOf(rec("9", "alac:16:44100")), "99" to listOf(rec("99", "aac:64"))))
        assertEquals(listOf(3, 12), listOf(e.songs, e.total))
        assertEquals(listOf("aac:256", "alac:16:44100"), e.specs)
    }

    @Test fun nothingDownloadedAndUnrecordedQualitiesAreHandled() {
        assertEquals(0, summarizeExisting(listOf(item("1")), emptyMap()).songs)
        assertEquals(0, summarizeExisting(listOf(item("1")), mapOf("1" to emptyList())).songs)
        val unknown = summarizeExisting(listOf(item("1")), mapOf("1" to listOf(rec("1", ""))))
        assertEquals(listOf(1, 1), listOf(unknown.songs, unknown.total)); assertEquals(listOf(""), unknown.specs)
        assertNull(summarizeExisting(emptyList(), emptyMap()).newest)
    }

    // ---- the picker ------------------------------------------------------------------------------------------------

    @Test fun selectingStaysInsideTheListedRows() {
        val probe = QualityProbeResult(listOf(stereo("alac:24:44100"), stereo("aac:256"), stereo("aac:128")), preselected = 1, tracks = 1, unchecked = 0)
        val c = DownloadRequest.Choose(listOf(item("1")), probe, probe.preselected, emptyMap())
        assertEquals("aac:256", c.option!!.spec!!.format())
        assertEquals("aac:128", c.select(2).option!!.spec!!.format())
        assertSame("a bad index changes nothing", c, c.select(7))
        assertSame(c, c.select(-1))
        assertNull(DownloadRequest.Choose(listOf(item("1")), QualityProbeResult(emptyList(), 0, 1, 1), 0, emptyMap()).option)
    }

    // ---- "use as my default" ---------------------------------------------------------------------------------------

    @Test fun aStereoRowBecomesTheDefaultQuality() {
        val s = UserSettings().withDefaultFrom(stereo("aac:128"), hadAtmosRow = false)
        assertEquals("aac:128", s.downloadQuality)
        assertEquals("the Atmos setting is untouched when there was no Atmos row", "off", s.downloadAtmos)
        assertEquals("alac:24:96000", UserSettings().withDefaultFrom(stereo("alac:24:96000"), false).downloadQuality)
        assertEquals("the highest-available row saves the open-ended rung", "alac:24:0", UserSettings().withDefaultFrom(stereo("alac:24:0", highest = true), false).downloadQuality)
    }

    @Test fun pickingStereoOverAnAtmosRowTurnsTheAtmosPreferenceOff() {
        val s = UserSettings(downloadAtmos = "atmos").withDefaultFrom(stereo("alac:24:44100"), hadAtmosRow = true)
        assertEquals("off", s.downloadAtmos)
        assertEquals("alac:24:44100", s.downloadQuality)
        assertEquals("but without an Atmos row the preference is left alone", "atmos", UserSettings(downloadAtmos = "atmos").withDefaultFrom(stereo("aac:256"), hadAtmosRow = false).downloadAtmos)
    }

    @Test fun anAtmosOrSpatialRowSavesTheAtmosChoiceOnly() {
        val a = UserSettings(downloadQuality = "aac:128").withDefaultFrom(atmos, true)
        assertEquals("atmos" to "aac:128", a.downloadAtmos to a.downloadQuality)
        assertEquals("spatial", UserSettings().withDefaultFrom(spatial, true).downloadAtmos)
    }

    @Test fun theTopLosslessRowSavesHighestAvailableSoFutureHigherRatesAreNotCapped() {
        val rows = listOf(stereo("alac:24:176400"), stereo("alac:24:48000"), stereo("aac:256"))
        val probe = QualityProbeResult(rows, 0, 9, 0)
        assertEquals(true, probe.isTopLossless(rows[0]))
        assertEquals(false, probe.isTopLossless(rows[1]))
        assertEquals("aac rows are never 'top lossless'", false, probe.isTopLossless(rows[2]))
        assertEquals("alac:24:0", UserSettings().withDefaultFrom(rows[0], false, probe.isTopLossless(rows[0])).downloadQuality)
        assertEquals("a middle row is a deliberate cap", "alac:24:48000", UserSettings().withDefaultFrom(rows[1], false, probe.isTopLossless(rows[1])).downloadQuality)
        assertEquals("aac:256", UserSettings().withDefaultFrom(rows[2], false, probe.isTopLossless(rows[2])).downloadQuality)
        assertEquals("the highest-available row is not itself 'top lossless'", false, probe.isTopLossless(stereo("alac:24:0", highest = true)))
    }
}
