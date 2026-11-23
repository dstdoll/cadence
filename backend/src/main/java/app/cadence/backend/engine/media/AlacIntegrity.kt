package app.cadence.backend.engine.media

import android.content.Context
import android.net.Uri
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.stream.HlsMediaParser
import app.cadence.backend.model.AlacCheckReport
import app.cadence.backend.model.AlacCheckStatus
import app.cadence.backend.model.StreamVariantInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
        val cookie: ByteArray = ByteArray(0),
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
        val cookieBytes = ByteArray(24) { idx -> b.get(off + idx) }
        return InitInfo(packDateMs, software, timescale, maxBlock, bits, channels, cookieBytes)
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

    /**
     * Repairs the 3-bit `END` marker (`111`) and zeros trailing padding bits in [tail4Bytes].
     * Returns `true` if any byte was modified.
     */
    internal fun repairEscapeTail(tail4Bytes: ByteArray, init: InitInfo): Boolean {
        if (tail4Bytes.size < 4) return false
        val packetLen = init.fullEscapeBytes
        val tailStartByte = packetLen - 4
        val endBit = 23 + init.maxBlock * init.channels * init.bits
        val totalBits = packetLen * 8
        var changed = false

        fun setBit(absBit: Int, one: Boolean) {
            val byteIdx = absBit / 8 - tailStartByte
            val mask = 1 shl (7 - (absBit % 8))
            val cur = tail4Bytes[byteIdx].toInt() and 0xFF
            val next = if (one) (cur or mask) else (cur and mask.inv())
            if (next != cur) {
                tail4Bytes[byteIdx] = next.toByte()
                changed = true
            }
        }

        for (i in 0 until 3) setBit(endBit + i, true)
        for (bit in (endBit + 3) until totalBits) setBit(bit, false)
        return changed
    }

    suspend fun listStreamVariants(enhancedHlsUrl: String?, trackId: String): List<StreamVariantInfo> =
        withContext(Dispatchers.IO) {
            if (enhancedHlsUrl.isNullOrBlank()) return@withContext emptyList()
            runCatching {
                val master = fetchBytes(enhancedHlsUrl, null, null).toString(Charsets.UTF_8)
                val parsed = HlsVariants.parse(master, enhancedHlsUrl)
                val out = parsed.map { v ->
                    val kind = when {
                        v.alac -> "ALAC"
                        v.atmos -> "Dolby Atmos"
                        v.binaural -> "Binaural AAC"
                        v.downmix -> "Downmix AAC"
                        v.heAac -> "HE-AAC"
                        else -> "AAC"
                    }
                    StreamVariantInfo(
                        codec = v.codecs,
                        kind = kind,
                        bitrateBps = if (v.averageBandwidth > 0L) v.averageBandwidth else v.bandwidth,
                        channels = v.group["CHANNELS"].orEmpty(),
                        sampleRate = v.group["SAMPLE-RATE"]?.toIntOrNull() ?: 0,
                        bitDepth = v.group["BIT-DEPTH"]?.toIntOrNull() ?: 0,
                    )
                }.sortedByDescending { it.bitrateBps }
                CadenceLog.i("StreamVariants", "trackId=$trackId count=${out.size} best=${out.firstOrNull()?.kind ?: "none"}")
                out
            }.getOrDefault(emptyList())
        }

    suspend fun checkOrRepairLocalTrack(
        trackId: String,
        repair: Boolean,
    ): AlacCheckReport? = withContext(Dispatchers.IO) {
        val context = DownloadRegistry.attachedContext ?: return@withContext null
        val record = DownloadRegistry.existing(context, trackId).firstOrNull() ?: return@withContext null
        val uri = record.uri
        runCatching {
            if (uri.startsWith("content:") || uri.startsWith("file:")) {
                val mode = if (repair) "rw" else "r"
                context.contentResolver.openFileDescriptor(Uri.parse(uri), mode)?.use { pfd ->
                    val rafChannel = if (repair) java.io.RandomAccessFile("/proc/self/fd/${pfd.fd}", "rw").channel
                    else java.io.FileInputStream(pfd.fileDescriptor).channel
                    rafChannel.use { ch -> checkOrRepairChannel(ch, uri, repair) }
                }
            } else {
                RandomAccessFile(File(uri), if (repair) "rw" else "r").use { raf ->
                    checkOrRepairChannel(raf.channel, uri, repair)
                }
            }
        }.onFailure {
            CadenceLog.e("AlacRepair", "local check/repair failed uri=$uri", it)
        }.getOrNull()
    }

    internal fun checkOrRepairChannel(ch: FileChannel, label: String, repair: Boolean): AlacCheckReport? {
        val fileSize = ch.size()
        if (fileSize < 16L) return null
        val header = ByteBuffer.allocate(8)
        var pos = 0L
        var moovBytes: ByteArray? = null
        while (pos + 8 <= fileSize) {
            header.clear()
            if (ch.read(header, pos) < 8) break
            header.flip()
            val sz32 = header.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { header.get(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val boxSize = if (sz32 == 1L && pos + 16 <= fileSize) {
                val ext = ByteBuffer.allocate(8)
                ch.read(ext, pos + 8)
                ext.flip()
                ext.long
            } else if (sz32 == 0L) fileSize - pos else sz32
            if (boxSize < 8L || pos + boxSize > fileSize) break
            if (type == "moov" && boxSize <= 32L * 1024 * 1024) {
                val buf = ByteBuffer.allocate(boxSize.toInt())
                ch.read(buf, pos)
                moovBytes = buf.array()
                break
            }
            pos += boxSize
        }
        val moov = moovBytes ?: return null
        val init = parseInit(moov) ?: return AlacCheckReport(AlacCheckStatus.NOT_ALAC, source = "local")
        val stbl = run {
            val b = ByteBuffer.wrap(moov)
            val moovBox = Mp4Integrity.children(b, 0, moov.size).firstOrNull { it.type == "moov" } ?: return null
            Mp4Integrity.find(b, moovBox.start, moovBox.end, "trak", "mdia", "minf", "stbl") ?: return null
        }
        val b = ByteBuffer.wrap(moov)
        val stsz = Mp4Integrity.find(b, stbl.start, stbl.end, "stsz") ?: return null
        val sampleSizeFixed = b.getInt(stsz.start + 4)
        val sampleCount = b.getInt(stsz.start + 8)
        val stco = Mp4Integrity.find(b, stbl.start, stbl.end, "stco")
        val co64 = if (stco == null) Mp4Integrity.find(b, stbl.start, stbl.end, "co64") else null
        val stsc = Mp4Integrity.find(b, stbl.start, stbl.end, "stsc") ?: return null

        val chunkOffsets: LongArray = when {
            stco != null -> {
                val n = b.getInt(stco.start + 4)
                LongArray(n) { i -> b.getInt(stco.start + 8 + i * 4).toLong() and 0xFFFFFFFFL }
            }
            co64 != null -> {
                val n = b.getInt(co64.start + 4)
                LongArray(n) { i -> b.getLong(co64.start + 8 + i * 8) }
            }
            else -> return null
        }
        val stscEntries = b.getInt(stsc.start + 4)
        val firstChunk = IntArray(stscEntries) { i -> b.getInt(stsc.start + 8 + i * 12) }
        val samplesPerChunk = IntArray(stscEntries) { i -> b.getInt(stsc.start + 12 + i * 12) }

        val expectedEsc = init.fullEscapeBytes
        var escapeFrames = 0
        var missingEnd = 0
        var dirtyPad = 0
        var patched = 0
        var sampleIdx = 0
        val tailBuf = ByteBuffer.allocate(4)

        for (chunkIdx in chunkOffsets.indices) {
            if (sampleIdx >= sampleCount) break
            val chunkNum = chunkIdx + 1
            var spc = 1
            for (e in stscEntries - 1 downTo 0) {
                if (chunkNum >= firstChunk[e]) { spc = samplesPerChunk[e]; break }
            }
            var offset = chunkOffsets[chunkIdx]
            for (k in 0 until spc) {
                if (sampleIdx >= sampleCount) break
                val sz = if (sampleSizeFixed != 0) sampleSizeFixed else b.getInt(stsz.start + 12 + sampleIdx * 4)
                if (sz == expectedEsc && sz >= 4) {
                    escapeFrames++
                    tailBuf.clear()
                    val tailPos = offset + sz - 4
                    if (ch.read(tailBuf, tailPos) == 4) {
                        val tail = tailBuf.array().copyOf()
                        when (inspectEscapeTail(tail, init)) {
                            TailResult.CLEAN -> {}
                            TailResult.MISSING_END -> {
                                if (repair && repairEscapeTail(tail, init)) {
                                    ch.write(ByteBuffer.wrap(tail), tailPos)
                                    patched++
                                } else missingEnd++
                            }
                            TailResult.DIRTY_PADDING -> {
                                if (repair && repairEscapeTail(tail, init)) {
                                    ch.write(ByteBuffer.wrap(tail), tailPos)
                                    patched++
                                } else dirtyPad++
                            }
                        }
                    }
                }
                offset += sz
                sampleIdx++
            }
        }
        val status = when {
            missingEnd > 0 -> AlacCheckStatus.MISSING_END
            dirtyPad > 0 -> AlacCheckStatus.DIRTY_PADDING
            else -> AlacCheckStatus.CLEAN
        }
        if (repair) {
            CadenceLog.i("AlacRepair", "uri=$label patchedFrames=$patched afterStatus=$status")
        }
        return AlacCheckReport(
            status = status,
            totalFrames = sampleCount,
            escapeFrames = escapeFrames,
            missingEndFrames = missingEnd,
            dirtyPaddingFrames = dirtyPad,
            packDateMs = init.packDateMs,
            software = init.software,
            bits = init.bits,
            sampleRate = init.timescale,
            source = "local",
        )
    }

    suspend fun checkCdnTrack(
        enhancedHlsUrl: String,
        trackId: String = "",
        analyzePcm: Boolean = false,
    ): AlacCheckReport = withContext(Dispatchers.IO) {
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
            val pcm = if (analyzePcm && trackId.isNotBlank() && init.cookie.size == 24 && media.segments.isNotEmpty()) {
                analyzePrefetchSegment(trackId, initBytes, init, media.segments.first())
            } else null

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
                effectiveBits = pcm?.first,
                bandwidthHz = pcm?.second,
                upsampledSuspect = pcm?.third ?: false,
            )
        }.getOrElse {
            CadenceLog.e("AlacIntegrity", "check failed", it)
            AlacCheckReport(AlacCheckStatus.UNAVAILABLE)
        }
    }

    private fun analyzePrefetchSegment(
        trackId: String,
        initBytes: ByteArray,
        init: InitInfo,
        seg0: app.cadence.backend.engine.stream.HlsSegment,
    ): Triple<Int, Int, Boolean>? = runCatching {
        val t0 = System.currentTimeMillis()
        val rawSeg = fetchBytes(seg0.url, seg0.offset, seg0.length)
        val kd = NativeWrapper.kd(trackId, "skd://itunes.apple.com/P000000000/s1/e1")
        if (kd == 0L) return@runCatching null
        val clearSeg = NativeDrm.clearSegment(kd, initBytes, rawSeg)
        val moof = parseMoofSamples(clearSeg) ?: return@runCatching null
        val offsets = IntArray(moof.sizes.size)
        var rel = moof.dataOffset
        for (i in moof.sizes.indices) {
            offsets[i] = rel
            rel += moof.sizes[i]
        }
        val res = NativeAlac.analyzeFrames(init.cookie, clearSeg, offsets, moof.sizes)
            ?: return@runCatching null
        if (res.size < 4) return@runCatching null
        val elapsed = System.currentTimeMillis() - t0
        val effBits = res[0]
        val bwHz = res[1]
        val upsampled = res[2] != 0
        CadenceLog.i(
            "AlacPcmAnalyzer",
            "trackId=$trackId containerBits=${init.bits} effectiveBits=$effBits rate=${init.timescale} rolloffHz=$bwHz upsampled=$upsampled elapsedMs=$elapsed",
        )
        Triple(effBits, bwHz, upsampled)
    }.onFailure {
        CadenceLog.e("AlacPcmAnalyzer", "prefetch analyze failed trackId=$trackId", it)
    }.getOrNull()

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
