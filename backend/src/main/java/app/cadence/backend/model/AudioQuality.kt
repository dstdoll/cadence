package app.cadence.backend.model

import kotlinx.serialization.Serializable

/** What we know about a stream's encoding. Every field but [codec] is optional; sources vary in detail. */
@Serializable
data class AudioFormatInfo(
    // BACKEND: here goes the codec name: "FLAC", "ALAC", "WAV", "AAC", "MP3", "Opus"... (decides Lossless vs Standard).
    val codec: String,
    // BACKEND: here goes the bit depth (16, 24...) for lossless files; null for lossy.
    val bitDepth: Int? = null,
    // BACKEND: here goes the sample rate in Hz (44100, 48000, 96000...). Above 48 kHz + lossless = "Hi-Res Lossless".
    val sampleRate: Int? = null,
    // BACKEND: here goes the channel count (1 mono, 2 stereo, 6 for 5.1...).
    val channels: Int? = null,
    // BACKEND: here goes the bitrate in bits per second; meaningful for lossy codecs ("AAC · 256 kbps").
    val bitrate: Int? = null,
)

enum class QualityTier(val label: String) {
    HiResLossless("Hi-Res Lossless"),
    Lossless("Lossless"),
    Lossy("Standard"),
}

object AudioQuality {
    private val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "WAVPACK", "APE")

    fun isLossless(info: AudioFormatInfo) = info.codec.uppercase() in LOSSLESS

    fun describe(tier: QualityTier) = when (tier) {
        QualityTier.HiResLossless -> "Bit-for-bit identical to the studio master, sampled above CD and streaming rates."
        QualityTier.Lossless -> "Bit-for-bit identical to the original recording. Nothing is thrown away."
        QualityTier.Lossy -> "Compressed to save space. Some detail is discarded, usually inaudibly."
    }

    /** Hi-Res means lossless above 48 kHz; 24-bit at 48 kHz or below is plain Lossless. */
    fun tier(info: AudioFormatInfo): QualityTier = when {
        !isLossless(info) -> QualityTier.Lossy
        (info.sampleRate ?: 0) > 48_000 -> QualityTier.HiResLossless
        else -> QualityTier.Lossless
    }

    /** "24-bit / 96 kHz" for lossless, "AAC · 256 kbps" for lossy; falls back gracefully on missing fields. */
    fun spec(info: AudioFormatInfo): String {
        val rate = info.sampleRate?.let(::khz)
        return if (isLossless(info)) {
            listOfNotNull(info.bitDepth?.let { "$it-bit" }, rate).joinToString(" / ").ifEmpty { info.codec }
        } else {
            listOfNotNull(info.codec, info.bitrate?.let { "${(it + 500) / 1000} kbps" } ?: rate).joinToString(" · ")
        }
    }

    fun khz(hz: Int): String {
        val k = hz / 1000.0
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(k)
    }

    fun channels(n: Int?) = when (n) { null -> null; 1 -> "Mono"; 2 -> "Stereo"; else -> "$n channels" }

    /** Maps a MIME type (as reported by the player) to a display codec name. [container] disambiguates raw PCM. */
    fun codecForMime(mime: String?, container: String? = null): String = when (mime?.lowercase()) {
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/raw" -> if (container?.contains("wav", true) == true) "WAV" else "PCM"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/eac3-joc" -> "Dolby Atmos"
        "audio/ac4" -> "Dolby AC-4"
        null -> "Unknown"
        else -> mime.substringAfter('/').uppercase()
    }
}
