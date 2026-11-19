package app.cadence.backend.engine.media

import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineDownloaderM4pTest {

    @Test
    fun prepareJobUsesConfiguredFolderAndMetadataForAacTrack() {
        val item = DownloadEngine.DownloadItem(
            trackId = "1568453070",
            title = "the rot",
            artist = "Dean Blunt",
            albumTitle = "BLACK METAL 2",
            trackNumber = 10,
            discNumber = 1,
            trackTotal = 10,
            kind = "single",
            collectionTitle = "",
            artworkTemplate = "https://example.com/{w}x{h}bb.jpg",
            releaseDate = "2021-06-11",
            genre = "Alternative",
            copyright = "℗ 2021 World Music",
            hlsUrl = "",
            hasLyrics = false,
        )
        val prefs = DownloadPreferences(
            albumFolderTemplate = "{artist}/{album}",
            trackFileTemplate = "{track:02d} {title}",
            codec = "aac",
        )
        val setup = EngineDownloader.prepareJobForTest(item, prefs, hls = "", codec = "aac")
        assertEquals("Music/Cadence/Dean Blunt/BLACK METAL 2", setup.relativeDir)
        assertEquals("10 the rot", setup.fileName)
        assertEquals("Dean Blunt", setup.album.artist)
        assertEquals("BLACK METAL 2", setup.album.title)
        assertEquals("2021-06-11", setup.album.releaseDate)
        assertEquals("Alternative", setup.album.genre)
    }

    @Test
    fun parseM4pAssetHandlesArm64TbiTaggedPointer() {
        val taggedPtr = 0xb4000072015203f0UL.toLong()
        val parsed = parseM4pAsset("$taggedPtr\nhttps://example.com/track.m4p")
        assertEquals(taggedPtr, parsed?.decryptorCtx)
        assertEquals("https://example.com/track.m4p", parsed?.url)
    }
}
