package app.cadence.backend.model

import app.cadence.backend.model.AudioFormatInfo
import app.cadence.backend.model.AudioQuality
import app.cadence.backend.model.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityTest {
    @Test fun tiers() {
        assertEquals(QualityTier.HiResLossless, AudioQuality.tier(AudioFormatInfo("FLAC", 24, 96_000)))
        assertEquals(QualityTier.Lossless, AudioQuality.tier(AudioFormatInfo("ALAC", 24, 48_000)))
        assertEquals(QualityTier.Lossless, AudioQuality.tier(AudioFormatInfo("WAV", 16, 44_100)))
        assertEquals(QualityTier.Lossy, AudioQuality.tier(AudioFormatInfo("AAC", null, 44_100, bitrate = 256_000)))
    }

    @Test fun specs() {
        assertEquals("24-bit / 96 kHz", AudioQuality.spec(AudioFormatInfo("FLAC", 24, 96_000)))
        assertEquals("16-bit / 44.1 kHz", AudioQuality.spec(AudioFormatInfo("WAV", 16, 44_100)))
        assertEquals("44.1 kHz", AudioQuality.spec(AudioFormatInfo("FLAC", null, 44_100)))
        assertEquals("AAC · 96 kbps", AudioQuality.spec(AudioFormatInfo("AAC", bitrate = 95_800)))
        assertEquals("AAC · 22.1 kHz", AudioQuality.spec(AudioFormatInfo("AAC", sampleRate = 22_050)))
    }

    @Test fun mimeMapping() {
        assertEquals("FLAC", AudioQuality.codecForMime("audio/flac"))
        assertEquals("ALAC", AudioQuality.codecForMime("audio/alac"))
        assertEquals("WAV", AudioQuality.codecForMime("audio/raw", "audio/wav"))
        assertEquals("PCM", AudioQuality.codecForMime("audio/raw"))
        assertEquals("AAC", AudioQuality.codecForMime("audio/mp4a-latm"))
    }
}
