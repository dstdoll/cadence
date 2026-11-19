package app.cadence.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The quality badge's bit depth comes from the stream itself, never from an assumption. */
class AlacBitDepthTest {
    /** ALACSpecificConfig: frameLength(4) version(1) bitDepth(1) pb mb kb channels(1) maxRun(2) maxFrame(4) avgRate(4) sampleRate(4). */
    private fun cookie(bits: Int, rate: Int) = ByteArray(24).also {
        it[3] = 0x10; it[5] = bits.toByte(); it[9] = 2
        for (i in 0 until 4) it[20 + i] = (rate ushr (24 - 8 * i)).toByte()
    }

    private fun alac(init: ByteArray?) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC)
        .apply { if (init != null) setInitializationData(listOf(init)) }.build()

    @Test
    fun readsTheDepthFromTheMagicCookie() {
        assertEquals(16, alacBitDepth(alac(cookie(16, 44_100))))
        assertEquals(24, alacBitDepth(alac(cookie(24, 192_000))))
    }

    @Test
    fun unknownStaysUnknown() {
        assertNull(alacBitDepth(alac(null)))
        assertNull(alacBitDepth(alac(ByteArray(8))))
        assertNull(alacBitDepth(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setInitializationData(listOf(cookie(24, 44_100))).build()))
    }
}
