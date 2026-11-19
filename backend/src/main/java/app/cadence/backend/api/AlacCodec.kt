package app.cadence.backend.api

import app.cadence.backend.engine.media.NativeAlac
import java.nio.ByteBuffer

/** Stateful native ALAC packet decoder handle backed by `cadence-alac` in `libcadence-native.so`. */
class AlacDecoderSession private constructor(
    private var handle: Long,
    val maxOutputBytes: Int,
) : AutoCloseable {
    /** Decodes one direct [packet] slice into direct [out], returning bytes written or negative error code. */
    fun decode(packet: ByteBuffer, inOffset: Int, inLen: Int, out: ByteBuffer, floatOut: Boolean): Int {
        val h = handle
        if (h == 0L) return -1
        return NativeAlac.decode(h, packet, inOffset, inLen, out, floatOut)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            NativeAlac.destroy(h)
        }
    }

    companion object {
        fun open(cookie: ByteArray, floatOut: Boolean): AlacDecoderSession? {
            val handle = NativeAlac.create(cookie)
            if (handle == 0L) return null
            val maxBytes = NativeAlac.maxOutputBytes(handle, floatOut)
            if (maxBytes <= 0) {
                NativeAlac.destroy(handle)
                return null
            }
            return AlacDecoderSession(handle, maxBytes)
        }
    }
}
