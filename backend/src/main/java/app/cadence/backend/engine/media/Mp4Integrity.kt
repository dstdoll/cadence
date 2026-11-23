package app.cadence.backend.engine.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Structural check that a decrypted `.m4a` holds every sample of the encrypted HLS package it
 * came from. The native muxer returns 0 even when its parser skipped samples (e.g. reading only
 * the first `trun` of each fragment, or missing the `trex` default duration), which publishes a
 * file that plays a fraction of a second of every segment. Comparing the two sample tables
 * catches that before anything reaches the user's music folder.
 *
 * Pure JVM on purpose: it runs in host tests against real packages.
 */
object Mp4Integrity {

    /** What the fragmented package declares: every `trun` of every `moof`. */
    data class Expected(val samples: Long, val ticks: Long, val timescale: Int, val fragments: Int) {
        val seconds get() = if (timescale > 0) ticks.toDouble() / timescale else 0.0
    }

    /** What the progressive output's sample tables (`stsz`/`stts`) hold. */
    data class Actual(val samples: Long, val ticks: Long, val timescale: Int) {
        val seconds get() = if (timescale > 0) ticks.toDouble() / timescale else 0.0
    }

    internal class Box(val type: String, val start: Int, val end: Int)

    internal fun children(b: ByteBuffer, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = b.getInt(at).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L) { if (at + 16 > to) break; size = b.getLong(at + 8); header = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) break
            val type = String(ByteArray(4) { b.get(at + 4 + it) }, Charsets.ISO_8859_1)
            out += Box(type, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    internal fun find(b: ByteBuffer, from: Int, to: Int, vararg path: String): Box? {
        var lo = from; var hi = to; var hit: Box? = null
        for (name in path) {
            hit = children(b, lo, hi).firstOrNull { it.type == name } ?: return null
            lo = hit.start; hi = hit.end
        }
        return hit
    }

    /** Reads top-level boxes of [file], loading only the ones in [wanted] (never `mdat`). */
    internal fun topLevel(file: File, wanted: Set<String>, visit: (type: String, body: ByteBuffer) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var at = 0L
            val head = ByteArray(16)
            while (at + 8 <= length) {
                raf.seek(at)
                raf.readFully(head, 0, 8)
                var size = ByteBuffer.wrap(head).getInt(0).toLong() and 0xFFFFFFFFL
                var header = 8
                if (size == 1L) { raf.readFully(head, 8, 8); size = ByteBuffer.wrap(head).getLong(8); header = 16 }
                if (size == 0L) size = length - at
                if (size < header || at + size > length) return
                val type = String(head, 4, 4, Charsets.ISO_8859_1)
                if (type in wanted) {
                    val body = ByteArray((size - header).toInt())
                    raf.readFully(body)
                    visit(type, ByteBuffer.wrap(body))
                }
                at += size
            }
        }
    }

    /** The media timescale (`mdhd`) declared by an init segment, or 0 when it has none. */
    fun mediaTimescale(init: ByteArray): Int {
        val b = ByteBuffer.wrap(init)
        val moov = children(b, 0, init.size).firstOrNull { it.type == "moov" } ?: return 0
        val mdhd = find(b, moov.start, moov.end, "trak", "mdia", "mdhd") ?: return 0
        return b.getInt(mdhd.start + if (b.get(mdhd.start).toInt() == 1) 20 else 12)
    }

    fun expected(pkg: File): Expected {
        var timescale = 0
        var trexDuration = 0L
        var samples = 0L
        var ticks = 0L
        var fragments = 0
        topLevel(pkg, setOf("moov", "moof")) { type, b ->
            val end = b.capacity()
            if (type == "moov") {
                find(b, 0, end, "trak", "mdia", "mdhd")?.let { m ->
                    val v = b.get(m.start).toInt()
                    timescale = b.getInt(m.start + if (v == 1) 20 else 12)
                }
                // trex: version/flags, track_ID, default_sample_description_index, default_sample_duration...
                find(b, 0, end, "mvex", "trex")?.let { trexDuration = b.getInt(it.start + 12).toLong() and 0xFFFFFFFFL }
                return@topLevel
            }
            fragments++
            for (traf in children(b, 0, end).filter { it.type == "traf" }) {
                var defaultDuration = trexDuration
                val kids = children(b, traf.start, traf.end)
                kids.firstOrNull { it.type == "tfhd" }?.let { t ->
                    val flags = b.getInt(t.start) and 0xFFFFFF
                    var p = t.start + 8
                    if (flags and 0x1 != 0) p += 8
                    if (flags and 0x2 != 0) p += 4
                    if (flags and 0x8 != 0) defaultDuration = b.getInt(p).toLong() and 0xFFFFFFFFL
                }
                for (trun in kids.filter { it.type == "trun" }) {
                    val flags = b.getInt(trun.start) and 0xFFFFFF
                    val count = b.getInt(trun.start + 4).toLong() and 0xFFFFFFFFL
                    samples += count
                    if (flags and 0x100 == 0) { ticks += count * defaultDuration; continue }
                    var p = trun.start + 8
                    if (flags and 0x1 != 0) p += 4
                    if (flags and 0x4 != 0) p += 4
                    val stride = 4 + (if (flags and 0x200 != 0) 4 else 0) + (if (flags and 0x400 != 0) 4 else 0) +
                        (if (flags and 0x800 != 0) 4 else 0)
                    for (i in 0 until count.toInt()) {
                        ticks += b.getInt(p + i * stride).toLong() and 0xFFFFFFFFL
                    }
                }
            }
        }
        return Expected(samples, ticks, timescale, fragments)
    }

    fun actual(m4a: File): Actual {
        var result = Actual(0, 0, 0)
        topLevel(m4a, setOf("moov")) { _, b ->
            val end = b.capacity()
            // The audio track is the one with a sound media header; cover art lives in udta/meta.
            val trak = children(b, 0, end).filter { it.type == "trak" }
                .firstOrNull { find(b, it.start, it.end, "mdia", "minf", "smhd") != null }
                ?: return@topLevel
            val mdhd = find(b, trak.start, trak.end, "mdia", "mdhd") ?: return@topLevel
            val timescale = b.getInt(mdhd.start + if (b.get(mdhd.start).toInt() == 1) 20 else 12)
            val stbl = find(b, trak.start, trak.end, "mdia", "minf", "stbl") ?: return@topLevel
            val stsz = find(b, stbl.start, stbl.end, "stsz")
            val samples = stsz?.let { b.getInt(it.start + 8).toLong() and 0xFFFFFFFFL } ?: 0L
            var ticks = 0L
            find(b, stbl.start, stbl.end, "stts")?.let { s ->
                val entries = b.getInt(s.start + 4)
                for (i in 0 until entries) {
                    val count = b.getInt(s.start + 8 + i * 8).toLong() and 0xFFFFFFFFL
                    val delta = b.getInt(s.start + 12 + i * 8).toLong() and 0xFFFFFFFFL
                    ticks += count * delta
                }
            }
            result = Actual(samples, ticks, timescale)
        }
        return result
    }

    /** Null when [out] holds everything [pkg] declares; otherwise a one-line reason. */
    fun mismatch(pkg: File, out: File): String? {
        val want = expected(pkg)
        if (want.samples == 0L) return null // not a fragmented package we understand; nothing to compare
        val got = actual(out)
        if (got.samples != want.samples) {
            return "decrypted file has ${got.samples} of ${want.samples} audio frames " +
                "(${"%.1f".format(got.seconds)} s of ${"%.1f".format(want.seconds)} s, ${want.fragments} segments)"
        }
        if (kotlin.math.abs(got.seconds - want.seconds) > 0.25) {
            return "decrypted file timing is wrong: ${"%.3f".format(got.seconds)} s declared for " +
                "${"%.1f".format(want.seconds)} s of audio (${got.samples} frames)"
        }
        return null
    }
}
