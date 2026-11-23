package app.cadence.backend.engine.media

import java.nio.ByteBuffer

internal object NativeAlac {
    init {
        NativeLibrary.loadOrThrow("cadence-native")
    }

    external fun create(cookie: ByteArray): Long
    external fun maxOutputBytes(handle: Long, floatOut: Boolean): Int
    external fun decode(
        handle: Long,
        packet: ByteBuffer,
        inOffset: Int,
        inLen: Int,
        out: ByteBuffer,
        floatOut: Boolean,
    ): Int
    external fun destroy(handle: Long)
    external fun analyzeFrames(
        cookie: ByteArray,
        data: ByteArray,
        offsets: IntArray,
        sizes: IntArray,
    ): IntArray?
}
