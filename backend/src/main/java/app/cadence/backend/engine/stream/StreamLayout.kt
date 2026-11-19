package app.cadence.backend.engine.stream

import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * Byte layout of one track's clear stream, the single fragmented MP4 the player reads:
 *
 * ```text
 * [ clear init ][ sidx ][ segment 0 ][ segment 1 ] ...
 * └──────── head ──────┘
 * ```
 *
 * Decryption is in place, so a clear segment is exactly as long as its encrypted source and the
 * whole layout follows from the playlist alone. That is what lets the player seek to, or simply
 * reach, a segment that has not been downloaded yet: its position is already known.
 */
class StreamLayout(val head: ByteArray, val segmentLengths: LongArray) {
    /** Position of each segment's first byte in the stream. */
    val segmentOffsets: LongArray = LongArray(segmentLengths.size).also { offsets ->
        var at = head.size.toLong()
        for (i in segmentLengths.indices) {
            offsets[i] = at
            at += segmentLengths[i]
        }
    }
    val totalSize: Long = head.size + segmentLengths.sum()

    /** Index of the segment holding [position], or -1 when it falls in the head or past the end. */
    fun segmentAt(position: Long): Int {
        if (position < head.size || position >= totalSize) return -1
        var lo = 0
        var hi = segmentOffsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segmentOffsets[mid] <= position) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /**
         * @param clearInit the init segment after its protected entries were rewritten
         * @param timescale the media timescale, to express segment durations in the index
         */
        fun of(clearInit: ByteArray, timescale: Int, media: HlsMedia): StreamLayout {
            val lengths = LongArray(media.segments.size) { media.segments[it].length }
            val ticks = LongArray(media.segments.size) { (media.segments[it].durationSec * timescale).roundToLong() }
            return StreamLayout(clearInit + sidx(timescale, lengths, ticks), lengths)
        }

        /**
         * A `sidx` box indexing consecutive segments that start right after it. The demuxer maps
         * a seek time to a byte offset through this table, and takes the track duration from it.
         */
        fun sidx(timescale: Int, lengths: LongArray, durationTicks: LongArray): ByteArray {
            require(lengths.size == durationTicks.size && lengths.size <= 0xFFFF)
            val b = ByteBuffer.allocate(32 + lengths.size * 12)
            b.putInt(b.capacity()).put("sidx".toByteArray(Charsets.ISO_8859_1))
            b.putInt(0) // version 0, flags 0
            b.putInt(1) // reference_ID: the single track
            b.putInt(timescale)
            b.putInt(0) // earliest_presentation_time
            b.putInt(0) // first_offset: segments follow immediately
            b.putShort(0).putShort(lengths.size.toShort())
            for (i in lengths.indices) {
                require(lengths[i] in 1..0x7FFFFFFF) { "segment $i has no valid size" }
                b.putInt(lengths[i].toInt()) // reference_type 0 (media) in the top bit
                b.putInt(durationTicks[i].toInt())
                b.putInt(0x90000000.toInt()) // starts with a sync sample (SAP type 1)
            }
            return b.array()
        }
    }
}
