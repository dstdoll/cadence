package app.cadence.backend.engine.media

import java.nio.ByteBuffer

internal object NativeEac3 {
    init {
        NativeLibrary.loadOrThrow("cadence-native")
    }

    external fun create(sampleRate: Int, channels: Int): Long
    external fun maxOutputBytes(handle: Long, floatOut: Boolean): Int
    external fun decode(
        handle: Long,
        packet: ByteBuffer,
        inOffset: Int,
        inLen: Int,
        out: ByteBuffer,
        floatOut: Boolean,
    ): Int
    external fun reset(handle: Long)
    external fun destroy(handle: Long)
}
