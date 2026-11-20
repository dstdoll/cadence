package app.cadence.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import app.cadence.backend.api.Eac3DecoderSession
import java.nio.ByteOrder

@UnstableApi
class Eac3DecoderException(message: String) : DecoderException(message)

@UnstableApi
class Eac3Decoder(
    format: Format,
    private val outputFloat: Boolean,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Eac3DecoderException>(
    arrayOfNulls(4),
    arrayOfNulls(4),
) {
    val sampleRate: Int = if (format.sampleRate > 0) format.sampleRate else 48_000
    // cadence-eac3 always downmixes multi-channel streams to stereo.
    val channelCount: Int = 2
    val pcmEncoding: @C.PcmEncoding Int =
        if (outputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    private val session: Eac3DecoderSession =
        Eac3DecoderSession.open(sampleRate, format.channelCount.coerceAtLeast(1), outputFloat)
            ?: throw Eac3DecoderException("Invalid AC-3/E-AC-3 decoder configuration")

    init {
        setInitialInputBufferSize(16 * 1024)
    }

    override fun getName(): String = "Eac3Decoder"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): Eac3DecoderException =
        Eac3DecoderException(error.message ?: "Unexpected AC-3/E-AC-3 decode failure")

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): Eac3DecoderException? {
        if (reset) {
            session.reset()
        }
        val inData = inputBuffer.data ?: return null
        val inLen = inData.remaining()
        if (inLen == 0) return null

        val outBuf = outputBuffer.init(inputBuffer.timeUs, session.maxOutputBytes)
        outBuf.order(ByteOrder.LITTLE_ENDIAN)

        val bytes = session.decode(inData, inData.position(), inLen, outBuf, outputFloat)
        if (bytes < 0) {
            return Eac3DecoderException("cadence-eac3 decode error ($bytes)")
        }
        outBuf.position(0)
        outBuf.limit(bytes)
        return null
    }

    override fun release() {
        super.release()
        session.close()
    }
}

@UnstableApi
class Eac3AudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<Eac3Decoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "Eac3AudioRenderer"

    override fun supportsFormatInternal(format: Format): @C.FormatSupport Int {
        val mime = format.sampleMimeType
        if (mime != MimeTypes.AUDIO_E_AC3 &&
            mime != MimeTypes.AUDIO_E_AC3_JOC &&
            mime != MimeTypes.AUDIO_AC3
        ) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val sr = if (format.sampleRate > 0) format.sampleRate else 48_000
        val encoding = if (useFloatFor(sr)) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        if (!sinkSupportsFormat(Util.getPcmFormat(encoding, 2, sr))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Eac3Decoder {
        val sr = if (format.sampleRate > 0) format.sampleRate else 48_000
        return Eac3Decoder(format, outputFloat = useFloatFor(sr))
    }

    override fun getOutputFormat(decoder: Eac3Decoder): Format =
        Util.getPcmFormat(decoder.pcmEncoding, decoder.channelCount, decoder.sampleRate)

    private fun useFloatFor(sampleRate: Int): Boolean =
        sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_FLOAT, 2, sampleRate))
}
