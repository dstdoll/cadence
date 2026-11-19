package app.cadence.playback

import android.os.Handler
import androidx.annotation.OptIn
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
import app.cadence.backend.api.AlacDecoderSession

@OptIn(UnstableApi::class)
internal class AlacDecoderException : DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}

@OptIn(UnstableApi::class)
internal class AlacDecoder(
    format: Format,
    val outputFloat: Boolean,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
    arrayOfNulls(NUM_BUFFERS),
    arrayOfNulls(NUM_BUFFERS),
) {
    val channelCount: Int = format.channelCount
    val sampleRate: Int = format.sampleRate
    val pcmEncoding: @C.PcmEncoding Int = if (outputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    private val session: AlacDecoderSession

    init {
        val cookie = format.initializationData.firstOrNull()
            ?: throw AlacDecoderException("Missing ALAC magic cookie in initializationData")
        session = AlacDecoderSession.open(cookie, outputFloat)
            ?: throw AlacDecoderException("Invalid ALAC magic cookie (${cookie.size} bytes)")
        setInitialInputBufferSize(DEFAULT_INPUT_BUFFER_SIZE)
    }

    override fun getName(): String = "cadence-alac"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): AlacDecoderException =
        AlacDecoderException("Unexpected ALAC decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): AlacDecoderException? {
        val inData = inputBuffer.data ?: return null
        val inOffset = inData.position()
        val inLen = inData.remaining()
        if (inLen == 0) {
            outputBuffer.init(inputBuffer.timeUs, 0)
            return null
        }
        val outData = outputBuffer.init(inputBuffer.timeUs, session.maxOutputBytes)
        val written = session.decode(inData, inOffset, inLen, outData, outputFloat)
        if (written < 0) {
            return AlacDecoderException("ALAC decode failed (code=$written, inBytes=$inLen, outCap=${outData.capacity()})")
        }
        outData.position(0)
        outData.limit(written)
        return null
    }

    override fun release() {
        super.release()
        session.close()
    }

    companion object {
        private const val NUM_BUFFERS = 16
        private const val DEFAULT_INPUT_BUFFER_SIZE = 32 * 1024
    }
}

@OptIn(UnstableApi::class)
internal class AlacAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<AlacDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "AlacAudioRenderer"

    override fun supportsFormatInternal(format: Format): @C.FormatSupport Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_ALAC || format.initializationData.isEmpty()) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val encoding = if (useFloatFor(format)) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        if (!sinkSupportsFormat(Util.getPcmFormat(encoding, format.channelCount, format.sampleRate))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacDecoder =
        AlacDecoder(format, outputFloat = useFloatFor(format))

    override fun getOutputFormat(decoder: AlacDecoder): Format =
        Util.getPcmFormat(decoder.pcmEncoding, decoder.channelCount, decoder.sampleRate)

    private fun useFloatFor(format: Format): Boolean =
        sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_FLOAT, format.channelCount, format.sampleRate))
}
