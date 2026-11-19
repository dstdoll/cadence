package app.cadence.playback

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** The Dolby formats a device decoder (or passthrough) should handle before FFmpeg does. */
internal val DOLBY_MIMES = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4)

internal fun isDolbyMime(mime: String?): Boolean = mime != null && mime in DOLBY_MIMES

/**
 * Offers the device's decoders for Dolby formats only. A MediaCodec renderer built with it sits in
 * front of FFmpeg: on a phone with a licensed Dolby decoder, E-AC-3 JOC (Dolby Atmos) is rendered by
 * it, objects included; for every other format it reports no decoder and FFmpeg takes over.
 */
@OptIn(UnstableApi::class)
internal class DolbyCodecSelector(private val delegate: MediaCodecSelector) : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> =
        if (isDolbyMime(mimeType)) delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder) else emptyList()
}
