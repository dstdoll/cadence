package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun stripsSecretsAndKeepsTheUsefulPart() {
        val cases = mapOf(
            "master=https://aod.itunes.apple.com/itunes-assets/HLS/P1_default.m3u8?accessKey=abc123&sig=ZZZ" to
                "master=https://aod.itunes.apple.com/itunes-assets/HLS/P1_default.m3u8?<redacted>",
            "session saved for alex.someone@example.com" to "session saved for a***@example.com",
            "auto init base=/data/user/0/app.cadence/files device=9f8e7d6c5b4a3210" to
                "auto init base=/data/user/0/app.cadence/files device=<id>",
            "handle=-5476376613124155472 for skd://itunes.apple.com/P000000000/s1/e1" to
                "handle=<kd> for skd://itunes.apple.com/P000000000/s1/e1",
            "Authorization: Bearer abc.def-ghi_jklmnop" to "Authorization: Bearer <token>",
            "first album cover=https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/aa/bb/cc/" + "f".repeat(60) + ".jpg/300x300bb.jpg" to
                "first album cover=https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/aa/bb/cc/" + "f".repeat(60) + ".jpg/300x300bb.jpg",
            "GET songs/1055140607/lyrics -> 404 bytes=120" to "GET songs/1055140607/lyrics -> 404 bytes=120",
            "segment 3/8 start=5538690 len=2751463 bytes=2751463" to "segment 3/8 start=5538690 len=2751463 bytes=2751463",
        )
        for ((raw, expected) in cases) assertEquals(expected, LogRedactor.clean(raw))
    }

    @Test
    fun tokensNeverSurvive() {
        val jwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJBQkMxMjMifQ.c2lnbmF0dXJlLXZhbHVlLWhlcmU"
        val userToken = "A".repeat(20) + "bCdEfGhIjKlMnOpQrStUvWxYz0123456789+/" + "Z".repeat(40) + "=="
        val out = LogRedactor.clean("dev=$jwt media-user-token=$userToken done")
        assertFalse(out.contains("eyJ"))
        assertFalse(out.contains(userToken))
        assertEquals("dev=<jwt> media-user-token=<secret:${userToken.length}> done", out)
    }

    @Test
    fun deviceAddressesAreRemovedButServersStay() {
        val line = "java.net.SocketTimeoutException: failed to connect to /17.253.0.1 (port 443) from /10.20.30.40 (port 40000) after 10000ms"
        val clean = LogRedactor.clean(line)
        org.junit.Assert.assertEquals(
            "java.net.SocketTimeoutException: failed to connect to /17.253.0.1 (port 443) from /<local> (port 40000) after 10000ms",
            clean,
        )
        org.junit.Assert.assertEquals("dns=<lan-ip>,<lan-ip>", LogRedactor.clean("dns=192.168.1.1,100.64.0.10"))
        org.junit.Assert.assertEquals("gw <lan-ip> peer 172.32.0.1", LogRedactor.clean("gw 172.20.4.7 peer 172.32.0.1"))
        org.junit.Assert.assertEquals("from /<local>", LogRedactor.clean("from /2800:150:1:2::3a"))
        org.junit.Assert.assertEquals("ffmpegLavc63.1.101 version 10.1", LogRedactor.clean("ffmpegLavc63.1.101 version 10.1"))
    }

    @Test
    fun logsWrittenBeforeARuleArePassedThroughItOnce() {
        val dir = java.nio.file.Files.createTempDirectory("logs").toFile()
        val log = java.io.File(dir, "cadence.log").apply { writeText("07:57:34 E/download failed to connect to /17.253.0.1 (port 443) from /10.20.30.40 (port 4)\n07:58:00 I/x dns=192.168.1.1\n") }
        val marker = java.io.File(dir, ".redaction")
        CadenceLog.rescrub(log, marker)
        val text = log.readText()
        org.junit.Assert.assertFalse(text, text.contains("10.20.30.40") || text.contains("192.168"))
        org.junit.Assert.assertTrue(text.contains("17.253.0.1") && text.contains("from /<local>"))
        // Once per version: a later raw line is left to the normal write path, not rewritten again.
        log.appendText("raw 10.0.0.1\n")
        CadenceLog.rescrub(log, marker)
        org.junit.Assert.assertTrue(log.readText().endsWith("raw 10.0.0.1\n"))
        dir.deleteRecursively()
    }
}
