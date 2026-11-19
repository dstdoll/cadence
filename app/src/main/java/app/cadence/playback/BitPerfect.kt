package app.cadence.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import app.cadence.backend.engine.CadenceLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/*
 * Bit-perfect output to USB DACs (Android 14+).
 *
 * Android normally mixes every app at the output's own rate. A USB DAC can instead be given a
 * "bit-perfect" mixer that passes one app's samples through untouched, as long as the track's rate,
 * sample format and channel mask match it exactly and no audio effect sits on the session. The
 * decoder hands us 32-bit float (exact for 24-bit content), so when the DAC's bit-perfect mixer wants
 * integers the samples are converted losslessly on the way into the AudioTrack.
 *
 * Stereo only. Multichannel audio (Dolby Atmos decoded to 5.1 or 7.1) is a lossy decoder's render,
 * not a source to keep untouched, and stereo DACs can't take it anyway: it goes through the system
 * mixer as usual, and the DSP processes it (see [dspBypassed]).
 */

/** AudioFormat encodings and masks used here, as plain ints so the decision runs on the JVM. */
internal object Pcm {
    const val PCM_16 = 2
    const val FLOAT = 4
    const val PCM_24_PACKED = 21
    const val PCM_32 = 22
    const val OUT_MONO = 4
    const val OUT_STEREO = 12

    fun bytesPerSample(encoding: Int) = when (encoding) {
        PCM_16 -> 2
        PCM_24_PACKED -> 3
        FLOAT, PCM_32 -> 4
        else -> 0
    }

    fun name(encoding: Int) = when (encoding) {
        PCM_16 -> "pcm16"
        PCM_24_PACKED -> "pcm24"
        PCM_32 -> "pcm32"
        FLOAT -> "float32"
        else -> "encoding=$encoding"
    }
}

/** The output types a USB DAC shows up as. The DSP bypass and the bit-perfect mixer agree on this. */
internal fun isUsbOutput(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE

/** One mixer configuration a USB device offers. */
internal data class MixerOption(val encoding: Int, val sampleRate: Int, val channelMask: Int, val bitPerfect: Boolean)

internal sealed interface BitPerfectChoice {
    data class Active(val encoding: Int, val sampleRate: Int, val channelMask: Int) : BitPerfectChoice
    data class Inactive(val reason: String) : BitPerfectChoice
}

/**
 * Picks the bit-perfect mixer for a track, or says why there is none. Only a stereo or mono track
 * qualifies, and only a mixer with its exact rate and channel mask (nothing is resampled). Float decoder output may go to a 24 or
 * 32-bit integer mixer (exact) or a float one, never to 16-bit, which would truncate 24-bit audio.
 */
internal fun chooseBitPerfect(
    enabled: Boolean,
    sdkInt: Int,
    usbPresent: Boolean,
    decoderEncoding: Int,
    sampleRate: Int,
    channelMask: Int,
    options: List<MixerOption>,
): BitPerfectChoice {
    if (!enabled) return BitPerfectChoice.Inactive("disabled")
    if (sdkInt < 34) return BitPerfectChoice.Inactive("API < 34")
    if (!usbPresent) return BitPerfectChoice.Inactive("no USB device")
    val channels = Integer.bitCount(channelMask)
    if (channels > 2) return BitPerfectChoice.Inactive("multichannel ($channels ch)")
    val order = when (decoderEncoding) {
        Pcm.FLOAT -> listOf(Pcm.PCM_24_PACKED, Pcm.PCM_32, Pcm.FLOAT)
        Pcm.PCM_16 -> listOf(Pcm.PCM_16, Pcm.PCM_24_PACKED, Pcm.PCM_32)
        else -> return BitPerfectChoice.Inactive("decoder output ${Pcm.name(decoderEncoding)} not supported")
    }
    val fitting = options.filter { it.bitPerfect && it.sampleRate == sampleRate && it.channelMask == channelMask }
    for (encoding in order) {
        if (fitting.any { it.encoding == encoding }) return BitPerfectChoice.Active(encoding, sampleRate, channelMask)
    }
    return BitPerfectChoice.Inactive("no matching mixer for ${sampleRate}Hz")
}

/**
 * Converts [samples] little-endian samples from [src] ([from]: float or 16-bit) into [dst] as [to].
 * Float holds 24 bits of mantissa, so 24-bit content comes back exactly; 16-bit input widens exactly.
 */
internal fun convertSamples(src: ByteBuffer, from: Int, dst: ByteBuffer, to: Int, samples: Int) {
    src.order(ByteOrder.LITTLE_ENDIAN)
    dst.order(ByteOrder.LITTLE_ENDIAN)
    repeat(samples) {
        val unit: Double = when (from) {
            Pcm.FLOAT -> src.getFloat().toDouble()
            Pcm.PCM_16 -> src.getShort() / 32768.0
            else -> error("unsupported source encoding $from")
        }
        when (to) {
            Pcm.PCM_32 -> dst.putInt((unit * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            Pcm.PCM_24_PACKED -> {
                val v = (unit * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                dst.put(v.toByte()); dst.put((v shr 8).toByte()); dst.put((v shr 16).toByte())
            }
            Pcm.PCM_16 -> dst.putShort((unit * 32768.0).roundToLong().coerceIn(-32768L, 32767L).toInt().toShort())
            Pcm.FLOAT -> dst.putFloat(unit.toFloat())
            else -> error("unsupported target encoding $to")
        }
    }
}

/**
 * An output that receives the sink's samples in [from] and writes them to [delegate] in [to]. The
 * sink's buffer is advanced only by the frames the inner AudioTrack really accepted, so a partial
 * write resumes exactly where it stopped and the frame count never drifts.
 */
@OptIn(UnstableApi::class)
internal class ConvertingAudioOutput(
    delegate: AudioOutput,
    private val from: Int,
    private val to: Int,
    channels: Int,
) : ForwardingAudioOutput(delegate) {
    private val inFrame = Pcm.bytesPerSample(from) * channels
    private val outFrame = Pcm.bytesPerSample(to) * channels
    private val channels = channels
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val out = pending ?: run {
            val frames = buffer.remaining() / inFrame
            if (scratch.capacity() < frames * outFrame) scratch = ByteBuffer.allocateDirect(frames * outFrame)
            scratch.clear()
            convertSamples(buffer.duplicate(), from, scratch, to, frames * channels)
            scratch.flip()
            scratch.also { pending = it }
        }
        val before = out.remaining()
        val done = super.write(out, encodedAccessUnitCount, presentationTimeUs)
        val frames = (before - out.remaining()) / outFrame
        buffer.position(buffer.position() + frames * inFrame)
        if (!out.hasRemaining()) pending = null
        return done
    }

    override fun flush() { pending = null; super.flush() }
    override fun stop() { super.stop() }
    override fun release() { pending = null; super.release() }
}

/**
 * Asks the USB DAC for a bit-perfect mixer that matches each track and builds the AudioTrack in its
 * format. Off, without a USB device, below Android 14 or when nothing matches, it does nothing and
 * playback goes through the normal (mixed) path; every failure falls back to that path too.
 */
@OptIn(UnstableApi::class)
internal class BitPerfectOutputProvider(
    delegate: AudioOutputProvider,
    context: Context,
    private val enabled: () -> Boolean,
) : ForwardingAudioOutputProvider(delegate) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val media = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    @Volatile private var preferredOn: AudioDeviceInfo? = null
    @Volatile private var lastState = ""

    private fun usbDevice(): AudioDeviceInfo? = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { isUsbOutput(it.type) }

    @RequiresApi(34)
    private fun options(device: AudioDeviceInfo): List<MixerOption> = runCatching {
        audio!!.getSupportedMixerAttributes(device).map {
            MixerOption(it.format.encoding, it.format.sampleRate, it.format.channelMask, it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
        }
    }.getOrDefault(emptyList())

    private fun report(state: String) {
        if (state != lastState) { lastState = state; CadenceLog.i("player", "bitperfect $state") }
    }

    private fun clear() {
        val device = preferredOn ?: return
        preferredOn = null
        if (Build.VERSION.SDK_INT >= 34) runCatching { audio?.clearPreferredMixerAttributes(media, device) }
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val device = usbDevice()
        val choice = chooseBitPerfect(
            enabled = enabled(),
            sdkInt = Build.VERSION.SDK_INT,
            usbPresent = device != null,
            decoderEncoding = config.encoding,
            sampleRate = config.sampleRate,
            channelMask = config.channelMask,
            options = if (device != null && Build.VERSION.SDK_INT >= 34) options(device) else emptyList(),
        )
        if (choice !is BitPerfectChoice.Active || device == null || Build.VERSION.SDK_INT < 34) {
            clear()
            report("inactive reason=${(choice as? BitPerfectChoice.Inactive)?.reason ?: "unavailable"}")
            return super.getAudioOutput(config)
        }
        return runCatching { activate(device, choice, config) }.getOrElse { t ->
            clear()
            report("inactive reason=failed: ${t.javaClass.simpleName}: ${t.message}")
            super.getAudioOutput(config)
        }
    }

    @RequiresApi(34)
    private fun activate(device: AudioDeviceInfo, choice: BitPerfectChoice.Active, config: AudioOutputProvider.OutputConfig): AudioOutput {
        val format = AudioFormat.Builder().setEncoding(choice.encoding).setSampleRate(choice.sampleRate).setChannelMask(choice.channelMask).build()
        val attributes = AudioMixerAttributes.Builder(format).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()
        if (audio?.setPreferredMixerAttributes(media, device, attributes) != true) error("the system refused the mixer")
        preferredOn = device
        val channels = Integer.bitCount(choice.channelMask)
        report("active device=${device.productName} type=${device.type} rate=${choice.sampleRate}Hz encoding=${Pcm.name(choice.encoding)}")
        if (choice.encoding == config.encoding) return super.getAudioOutput(config)
        // Same duration of buffer in the narrower or wider format.
        val bufferSize = (config.bufferSize.toLong() * Pcm.bytesPerSample(choice.encoding) / Pcm.bytesPerSample(config.encoding)).toInt()
        val inner = super.getAudioOutput(config.buildUpon().setEncoding(choice.encoding).setBufferSize(bufferSize).build())
        return ConvertingAudioOutput(inner, config.encoding, choice.encoding, channels)
    }

    override fun release() { clear(); super.release() }
}
