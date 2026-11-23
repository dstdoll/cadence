package app.cadence.backend.engine.media

import app.cadence.backend.engine.media.AlacIntegrity.TailResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class AlacIntegrityTest {

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        ByteBuffer.wrap(out).putInt(out.size)
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        body.copyInto(out, 8)
        return out
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(4 + payload.size)
        body[0] = version.toByte()
        body[1] = ((flags ushr 16) and 0xFF).toByte()
        body[2] = ((flags ushr 8) and 0xFF).toByte()
        body[3] = (flags and 0xFF).toByte()
        payload.copyInto(body, 4)
        return box(type, body)
    }

    @Test
    fun parseInit_readsCookieSoftwareAndDate() {
        val mvhdPayload = ByteArray(96).apply {
            // creation_time = Mac epoch + 1_000_000 seconds
            ByteBuffer.wrap(this).putInt(0, 1_000_000)
        }
        val mvhd = fullBox("mvhd", 0, 0, mvhdPayload)
        val swrePayload = byteArrayOf(0, 0) + "Lavf60.16.100\u0000".toByteArray()
        val udta = box("udta", fullBox("swre", 0, 0, swrePayload))

        val mdhdPayload = ByteArray(20).apply {
            ByteBuffer.wrap(this).putInt(8, 44100) // offset 12 in FullBox - 4 = 8 in payload
        }
        val mdhd = fullBox("mdhd", 0, 0, mdhdPayload)

        val alacConfig = ByteArray(24).apply {
            ByteBuffer.wrap(this).putInt(0, 4096)
            this[5] = 24
            this[9] = 2
        }
        val alacBox = fullBox("alac", 0, 0, alacConfig)
        val encaBody = ByteArray(28) + alacBox
        val enca = box("enca", encaBody)
        val stsd = fullBox("stsd", 0, 0, ByteArray(4) + enca)
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + minf)
        val trak = box("trak", mdia)
        val moov = box("moov", mvhd + udta + trak)

        val parsed = AlacIntegrity.parseInit(moov)
        assertNotNull(parsed)
        assertEquals("Lavf60.16.100", parsed!!.software)
        assertEquals(44100, parsed.timescale)
        assertEquals(4096, parsed.maxBlock)
        assertEquals(24, parsed.bits)
        assertEquals(2, parsed.channels)
        assertEquals(24580, parsed.fullEscapeBytes)
    }

    @Test
    fun inspectEscapeTail_detectsCleanMissingEndAndDirtyPadding() {
        val init = AlacIntegrity.InitInfo(
            packDateMs = null,
            software = "",
            timescale = 44100,
            maxBlock = 4096,
            bits = 24,
            channels = 2,
        )
        // For 4096 * 2 * 24 = 196608 bits, endBit = 23 + 196608 = 196631.
        // tailStartByte = 24580 - 4 = 24576 (bit 196608).
        // endBit relative to tail = 23 (byte 2, bit 7; byte 3, bits 0..1 -> 0x01, 0xC0).
        val clean = byteArrayOf(0, 0, 0x01, 0xC0.toByte())
        assertEquals(TailResult.CLEAN, AlacIntegrity.inspectEscapeTail(clean, init))

        val missingEnd = byteArrayOf(0, 0, 0x01, 0x40.toByte())
        assertEquals(TailResult.MISSING_END, AlacIntegrity.inspectEscapeTail(missingEnd, init))

        val dirtyPad = byteArrayOf(0, 0, 0x01, 0xC1.toByte())
        assertEquals(TailResult.DIRTY_PADDING, AlacIntegrity.inspectEscapeTail(dirtyPad, init))
    }

    @Test
    fun parseMoofSamples_readsSizesAndDataOffset() {
        val tfhd = fullBox("tfhd", 0, 0, ByteBuffer.allocate(4).putInt(1).array())
        val trunPayload = ByteBuffer.allocate(16)
            .putInt(2) // sample_count
            .putInt(200) // data_offset
            .putInt(1000)
            .putInt(24580)
            .array()
        val trun = fullBox("trun", 0, 0x0201, trunPayload)
        val traf = box("traf", tfhd + trun)
        val moof = box("moof", traf)

        val parsed = AlacIntegrity.parseMoofSamples(moof)
        assertNotNull(parsed)
        assertEquals(200, parsed!!.dataOffset)
        assertEquals(listOf(1000, 24580), parsed.sizes.toList())
    }

    @Test
    fun repairEscapeTail_patchesMissingEndAndDirtyPadding() {
        val init = AlacIntegrity.InitInfo(null, "", 44100, 4096, 24, 2)
        val broken = byteArrayOf(0, 0, 0x01, 0x4F.toByte())
        assertEquals(TailResult.MISSING_END, AlacIntegrity.inspectEscapeTail(broken, init))
        assertEquals(true, AlacIntegrity.repairEscapeTail(broken, init))
        assertEquals(TailResult.CLEAN, AlacIntegrity.inspectEscapeTail(broken, init))
        assertEquals(false, AlacIntegrity.repairEscapeTail(broken, init))
    }
}
