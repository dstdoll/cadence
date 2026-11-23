package app.cadence.backend.engine.media

import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.stream.HlsMediaParser
import app.cadence.backend.model.AlacCheckReport
import app.cadence.backend.model.AlacCheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Inspects Apple ALAC streams for the escape-frame packing bug (`AppleMusicDecrypt#136`).
 *
 * Uncompressed ("escape") ALAC frames are 4 bytes larger than raw PCM (`maxBlock * channels * bits / 8`).
 * Because `cbcs` only encrypts complete 16-byte blocks, the trailing 4 bytes of an escape frame are
 * plaintext on the CDN and can be checked via minimal byte-range requests without requesting `/key`.
 */
object AlacIntegrity {

    /** 1904-01-01T00:00:00Z in Unix epoch milliseconds. */
    private const val MAC_EPOCH_OFFSET_MS = 2_082_844_800_000L
    private const val MOOF_PREFETCH_BYTES = 32_768L

    internal data class InitInfo(
        val packDateMs: Long?,
        val software: String,
        val timescale: Int,
        val maxBlock: Int,
        val bits: Int,
        val channels: Int,
    ) {
        val rawPcmBytes: Int get() = maxBlock * channels * (bits / 8)
        val fullEscapeBytes: Int get() = ((23 + maxBlock * channels * bits + 3 + 7) / 8)
    }

    internal data class MoofSamples(
        val moofSize: Int,
        val dataOffset: Int,
        val sizes: IntArray,
    )

    internal enum class TailResult { CLEAN, MISSING_END, DIRTY_PADDING }

    internal fun parseInit(bytes: ByteArray): InitInfo? {
        val b = ByteBuffer.wrap(bytes)
        val moov = Mp4Integrity.children(b, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return null
        val mvhd = Mp4Integrity.find(b, moov.start, moov.end, "mvhd")
        val packDateMs = mvhd?.let {
            val ver = b.get(it.start).toInt() and 0xFF
            val sec = if (ver == 1) b.getLong(it.start + 4) else (b.getInt(it.start + 4).toLong() and 0xFFFFFFFFL)
            if (sec > 0L) sec * 1000L - MAC_EPOCH_OFFSET_MS else null
        }

        val udta = Mp4Integrity.find(b, moov.start, moov.end, "udta")
        val swre = udta?.let { Mp4Integrity.find(b, it.start, it.end, "swre") }
        val software = if (swre != null && swre.start + 6 <= swre.end) {
            val raw = ByteArray(swre.end - (swre.start + 6))
            for (i in raw.indices) raw[i] = b.get(swre.start + 6 + i)
            val nul = raw.indexOf(0)
            String(raw, 0, if (nul >= 0) nul else raw.size, Charsets.UTF_8).trim()
        } else ""

        val mdia = Mp4Integrity.find(b, moov.start, moov.end, "trak", "mdia") ?: return null
        val mdhd = Mp4Integrity.find(b, mdia.start, mdia.end, "mdhd") ?: return null
        val mdhdVer = b.get(mdhd.start).toInt() and 0xFF
        val timescale = b.getInt(mdhd.start + if (mdhdVer == 1) 20 else 12)

        val stsd = Mp4Integrity.find(b, mdia.start, mdia.end, "minf", "stbl", "stsd") ?: return null
        var alacCookie: Mp4Integrity.Box? = null
        for (entry in Mp4Integrity.children(b, stsd.start + 8, stsd.end)) {
            alacCookie = Mp4Integrity.children(b, entry.start + 28, entry.end).firstOrNull { it.type == "alac" }
            if (alacCookie != null) break
        }
        val cookie = alacCookie ?: return null
        // ALACSpecificConfig is 24 bytes (after 4-byte FullBox version/flags inside the `alac` box).
        val off = cookie.start + 4
        if (off + 24 > cookie.end) return null
        val maxBlock = b.getInt(off)
        val bits = b.get(off + 5).toInt() and 0xFF
        val channels = b.get(off + 9).toInt() and 0xFF
        if (maxBlock <= 0 || bits !in setOf(16, 20, 24, 32) || channels !in 1..2) return null
        return InitInfo(packDateMs, software, timescale, maxBlock, bits, channels)
    }

    internal fun parseMoofSamples(bytes: ByteArray): MoofSamples? {
        if (bytes.size < 8) return null
        val b = ByteBuffer.wrap(bytes)
        val moof = Mp4Integrity.children(b, 0, bytes.size).firstOrNull { it.type == "moof" } ?: return null
        val traf = Mp4Integrity.find(b, moof.start, moof.end, "traf") ?: return null
        val tfhd = Mp4Integrity.find(b, traf.start, traf.end, "tfhd") ?: return null
        val trun = Mp4Integrity.find(b, traf.start, traf.end, "trun") ?: return null

        val tfhdFlags = ((b.get(tfhd.start + 1).toInt() and 0xFF) shl 16) or
            ((b.get(tfhd.start + 2).toInt() and 0xFF) shl 8) or
            (b.get(tfhd.start + 3).toInt() and 0xFF)
        var tPos = tfhd.start + 8
        if ((tfhdFlags and 0x01) != 0) tPos += 8
        if ((tfhdFlags and 0x02) != 0) tPos += 4
        if ((tfhdFlags and 0x08) != 0) tPos += 4
        val defaultSize = if ((tfhdFlags and 0x10) != 0 && tPos + 4 <= tfhd.end) b.getInt(tPos) else 0

        val trunFlags = ((b.get(trun.start + 1).toInt() and 0xFF) shl 16) or
            ((b.get(trun.start + 2).toInt() and 0xFF) shl 8) or
            (b.get(trun.start + 3).toInt() and 0xFF)
        val count = b.getInt(trun.start + 4)
        var p = trun.start + 8
        val dataOffset = if ((trunFlags and 0x01) != 0) {
            val v = b.getInt(p); p += 4; v
        } else moof.end + 8
        if ((trunFlags and 0x04) != 0) p += 4

        val hasDuration = (trunFlags and 0x100) != 0
        val hasSize = (trunFlags and 0x200) != 0
        val hasFlags = (trunFlags and 0x400) != 0
        val hasCto = (trunFlags and 0x800) != 0

        val sizes = IntArray(count)
        for (i in 0 until count) {
            if (hasDuration) p += 4
            sizes[i] = if (hasSize) { val s = b.getInt(p); p += 4; s } else defaultSize
            if (hasFlags) p += 4
            if (hasCto) p += 4
        }
        return MoofSamples(moof.end, dataOffset, sizes)
    }

    /**
     * Checks the 3-bit `END` marker (`111`) and zero-padding bits in the trailing [tail4Bytes]
     * of a full-block uncompressed ALAC escape frame (`packetLen == init.fullEscapeBytes`).
     */
    internal fun inspectEscapeTail(tail4Bytes: ByteArray, init: InitInfo): TailResult {
        if (tail4Bytes.size < 4) return TailResult.MISSING_END
        val packetLen = init.fullEscapeBytes
        val tailStartByte = packetLen - 4
        val endBit = 23 + init.maxBlock * init.channels * init.bits
        val totalBits = packetLen * 8

        fun bitAt(absBit: Int): Int {
            val byteIdx = absBit / 8 - tailStartByte
            val shift = 7 - (absBit % 8)
            return (tail4Bytes[byteIdx].toInt() ushr shift) and 1
        }

        val b0 = bitAt(endBit)
        val b1 = bitAt(endBit + 1)
        val b2 = bitAt(endBit + 2)
        if (b0 != 1 || b1 != 1 || b2 != 1) return TailResult.MISSING_END

        for (bit in (endBit + 3) until totalBits) {
            if (bitAt(bit) != 0) return TailResult.DIRTY_PADDING
        }
        return TailResult.CLEAN
    }

    suspend fun checkCdnTrack(enhancedHlsUrl: String): AlacCheckReport = withContext(Dispatchers.IO) {
        runCatching {
            val master = fetchBytes(enhancedHlsUrl, null, null).toString(Charsets.UTF_8)
            val alac = HlsVariants.parse(master, enhancedHlsUrl)
                .filter { it.alac }
                .maxByOrNull { it.bandwidth }
                ?: return@withContext AlacCheckReport(AlacCheckStatus.NOT_ALAC)

            val mediaText = fetchBytes(alac.url, null, null).toString(Charsets.UTF_8)
            val media = HlsMediaParser.parse(mediaText, alac.url)
            val initBytes = fetchBytes(media.initUrl, media.initOffset, media.initLength)
            val init = parseInit(initBytes)
                ?: return@withContext AlacCheckReport(AlacCheckStatus.UNAVAILABLE)

            var totalFrames = 0
            var escapeFrames = 0
            var missingEnd = 0
            var dirtyPad = 0
            val expectedEscapeLen = init.fullEscapeBytes

            for (seg in media.segments) {
                val prefetchLen = minOf(MOOF_PREFETCH_BYTES, seg.length)
                var segHead = fetchBytes(seg.url, seg.offset, prefetchLen)
                if (segHead.size >= 8) {
                    val firstSize = ByteBuffer.wrap(segHead).getInt(0)
                    if (firstSize > segHead.size && firstSize <= seg.length) {
                        segHead = fetchBytes(seg.url, seg.offset, firstSize.toLong())
                    }
                }
                val moof = parseMoofSamples(segHead) ?: continue
                totalFrames += moof.sizes.size
                var sampleRelOffset = moof.dataOffset.toLong()
                for (sampleSize in moof.sizes) {
                    if (sampleSize == expectedEscapeLen && sampleSize >= 4) {
                        escapeFrames++
                        val tailOffset = seg.offset + sampleRelOffset + sampleSize - 4
                        val tail = fetchBytes(seg.url, tailOffset, 4)
                        when (inspectEscapeTail(tail, init)) {
                            TailResult.CLEAN -> {}
                            TailResult.MISSING_END -> missingEnd++
                            TailResult.DIRTY_PADDING -> dirtyPad++
                        }
                    }
                    sampleRelOffset += sampleSize
                }
            }

            val status = when {
                missingEnd > 0 -> AlacCheckStatus.MISSING_END
                dirtyPad > 0 -> AlacCheckStatus.DIRTY_PADDING
                else -> AlacCheckStatus.CLEAN
            }
            CadenceLog.i(
                "AlacIntegrity",
                "status=$status frames=$totalFrames escape=$escapeFrames missingEnd=$missingEnd dirtyPad=$dirtyPad bits=${init.bits} rate=${init.timescale} sw=${init.software}",
            )
            AlacCheckReport(
                status = status,
                totalFrames = totalFrames,
                escapeFrames = escapeFrames,
                missingEndFrames = missingEnd,
                dirtyPaddingFrames = dirtyPad,
                packDateMs = init.packDateMs,
                software = init.software,
                bits = init.bits,
                sampleRate = init.timescale,
                source = "cdn",
            )
        }.getOrElse {
            CadenceLog.e("AlacIntegrity", "check failed", it)
            AlacCheckReport(AlacCheckStatus.UNAVAILABLE)
        }
    }

    private fun fetchBytes(url: String, offset: Long?, length: Long?): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
            if (offset != null && length != null && length > 0) {
                setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
