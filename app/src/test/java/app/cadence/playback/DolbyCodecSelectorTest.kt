package app.cadence.playback

import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dolby formats reach the device decoder; everything else is left to FFmpeg. */
class DolbyCodecSelectorTest {
    @Test
    fun onlyDolbyFormatsAreOffered() {
        val asked = mutableListOf<String>()
        val selector = DolbyCodecSelector(MediaCodecSelector { mime, _, _ -> asked += mime; emptyList() })
        for (mime in listOf("audio/alac", "audio/mp4a-latm", "audio/flac", "audio/raw")) {
            assertTrue(selector.getDecoderInfos(mime, false, false).isEmpty())
        }
        assertTrue("non-Dolby formats must not even be looked up", asked.isEmpty())
        for (mime in listOf("audio/eac3-joc", "audio/eac3", "audio/ac3", "audio/ac4")) selector.getDecoderInfos(mime, false, false)
        assertEquals(listOf("audio/eac3-joc", "audio/eac3", "audio/ac3", "audio/ac4"), asked)
    }

    @Test
    fun recognisesDolbyMimes() {
        assertTrue(isDolbyMime("audio/eac3-joc"))
        assertFalse(isDolbyMime("audio/alac"))
        assertFalse(isDolbyMime(null))
    }
}
