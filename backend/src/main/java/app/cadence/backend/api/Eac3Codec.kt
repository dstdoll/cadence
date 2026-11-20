package app.cadence.backend.api

import app.cadence.backend.engine.media.NativeEac3
import java.nio.ByteBuffer

/** Stateful native AC-3 / E-AC-3 packet decoder handle backed by `cadence-eac3` in `libcadence-native.so`. */
class Eac3DecoderSession private constructor(
    private var handle: Long,
    val maxOutputBytes: Int,
) : AutoCloseable {
    /** Decodes one direct [packet] slice into direct [out], returning bytes written or negative error code. */
    fun decode(packet: ByteBuffer, inOffset: Int, inLen: Int, out: ByteBuffer, floatOut: Boolean): Int {
        val h = handle
        if (h == 0L) return -1
        return NativeEac3.decode(h, packet, inOffset, inLen, out, floatOut)
    }

    fun reset() {
        val h = handle
        if (h != 0L) {
            NativeEac3.reset(h)
        }
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            NativeEac3.destroy(h)
        }
    }

    companion object {
        fun open(sampleRate: Int, channels: Int, floatOut: Boolean): Eac3DecoderSession? {
            val handle = NativeEac3.create(sampleRate, channels)
            if (handle == 0L) return null
            val maxBytes = NativeEac3.maxOutputBytes(handle, floatOut)
            if (maxBytes <= 0) {
                NativeEac3.destroy(handle)
                return null
            }
            return Eac3DecoderSession(handle, maxBytes)
        }
    }
}
