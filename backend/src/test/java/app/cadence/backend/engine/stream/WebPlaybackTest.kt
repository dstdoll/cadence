package app.cadence.backend.engine.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.util.Base64

class WebPlaybackTest {

    @Test
    fun absolutizeRewritesIsoKeyToWidevineCencAndResolvesRelativeUris() {
        val raw = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,AAAAAF18sc4AHXdf4kPQQQ=="
            #EXT-X-MAP:URI="mzaf_123.aac.wa.mp4",BYTERANGE="1338@0"
            #EXTINF:9.98,
            #EXT-X-BYTERANGE:353997@1338
            mzaf_123.aac.wa.mp4
        """.trimIndent()

        assertEquals("data:;base64,AAAAAF18sc4AHXdf4kPQQQ==", WebPlayback.extractWidevineKeyUri(raw))

        val base = URL("https://aod-ssl.itunes.apple.com/assets/test.m3u8")
        val out = WebPlayback.absolutizePlaylist(raw, base)

        assertFalse(out.contains("METHOD=ISO-23001-7"))
        assertTrue(out.contains("""METHOD=SAMPLE-AES-CENC,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed""""))

        val keyId = Base64.getDecoder().decode("AAAAAF18sc4AHXdf4kPQQQ==")
        val expectedProto = Base64.getEncoder().encodeToString(WebPlayback.buildWidevineCencPssh(keyId))
        assertTrue(out.contains("""URI="data:text/plain;base64,$expectedProto""""))
        assertTrue(out.contains("""#EXT-X-MAP:URI="https://aod-ssl.itunes.apple.com/assets/mzaf_123.aac.wa.mp4",BYTERANGE="1338@0""""))
        assertTrue(out.lines().last() == "https://aod-ssl.itunes.apple.com/assets/mzaf_123.aac.wa.mp4")
    }
}
