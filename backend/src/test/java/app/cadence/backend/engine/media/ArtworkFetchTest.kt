package app.cadence.backend.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover embed used to drop the artwork whenever its three fixed sizes all failed. The fetch now
 * steps down a descending ladder, tries both image formats, and only accepts a real JPEG/PNG, so a
 * cover is embedded whenever any size is reachable. These cover the pure logic (no network).
 */
class ArtworkFetchTest {
    private val template = "https://is1.mzstatic.com/image/thumb/abc/{w}x{h}bb.jpg"

    @Test
    fun ladderStartsWithRequestedDescendsAndIsDistinct() {
        val ladder = Artwork.sizeLadder("1400x1400")
        assertEquals("requested first", "1400x1400", ladder.first())
        assertEquals("no duplicate when requested matches a preset", ladder.distinct(), ladder)
        assertEquals("ends on a small guaranteed size", "300x300", ladder.last())

        val huge = Artwork.sizeLadder("10000x10000") // the "original" master request
        assertEquals("10000x10000", huge.first())
        assertTrue("still falls back to a small size", huge.contains("300x300"))
    }

    @Test
    fun sizedUrlSubstitutesSizeAndFormat() {
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/abc/2000x2000bb.jpg",
            Artwork.sizedUrl(template, "2000x2000", "jpg"),
        )
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/abc/600x600bb.png",
            Artwork.sizedUrl(template, "600x600", "png"),
        )
        assertTrue(
            "\"original\" keeps the template extension",
            Artwork.sizedUrl(template, "600x600", "original")!!.endsWith("bb.jpg"),
        )
        assertNull(Artwork.sizedUrl(null, "600x600", "jpg"))
        assertNull(Artwork.sizedUrl("", "600x600", "jpg"))
    }

    @Test
    fun imageMimeAcceptsRealImagesAndRejectsJunk() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x11.toByte(), 0x22.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        assertEquals("image/jpeg", Artwork.imageMime(jpeg))
        assertEquals("image/png", Artwork.imageMime(png))
        assertNull("html error page", Artwork.imageMime("<!DOCTYPE html><html>error".toByteArray()))
        assertNull("empty body", Artwork.imageMime(ByteArray(0)))
        assertNull("truncated signature", Artwork.imageMime(byteArrayOf(0xFF.toByte())))
    }
}
