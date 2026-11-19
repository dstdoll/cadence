package app.cadence.core.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The Kotlin palette must give exactly the colours the reference JavaScript gives for the same pixels.
 * Fixtures were produced by running the original `paletteOf` in Node on the same pixels.
 * Set PALETTE_FIXTURES to a file made with `--covers` to run the same check on real covers.
 */
class CoverPaletteParityTest {
    private class Fixture(val name: String, val argb: IntArray, val w: Int, val h: Int, val expected: CoverPalette.Result)

    private fun fixtures(): List<Fixture> {
        val text = System.getenv("PALETTE_FIXTURES")?.let(::File)?.takeIf { it.isFile }?.readText()
            ?: javaClass.getResource("/palette/reference-fixtures.json")!!.readText()
        fun rgb(a: JsonElement) = a.jsonArray.map { it.jsonPrimitive.int }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
        return Json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            val w = o["w"]!!.jsonPrimitive.int
            val h = o["h"]!!.jsonPrimitive.int
            val rgba = Base64.getDecoder().decode(o["rgba"]!!.jsonPrimitive.content)
            val argb = IntArray(w * h) { i ->
                (0xFF shl 24) or ((rgba[i * 4].toInt() and 0xFF) shl 16) or ((rgba[i * 4 + 1].toInt() and 0xFF) shl 8) or (rgba[i * 4 + 2].toInt() and 0xFF)
            }
            val e = o["expected"]!!.jsonObject
            Fixture(o["name"]!!.jsonPrimitive.content, argb, w, h, CoverPalette.Result(rgb(e["accent"]!!), e["palette"]!!.jsonArray.map(::rgb)))
        }
    }

    @Test
    fun matchesTheReferenceForEveryFixture() {
        val all = fixtures()
        assertTrue("no fixtures", all.isNotEmpty())
        fun hex(c: Int) = "#%06x".format(c)
        for (f in all) {
            val actual = CoverPalette.of(f.argb, f.w, f.h)
            assertEquals("${f.name} accent", hex(f.expected.accent), hex(actual.accent))
            assertEquals("${f.name} palette", f.expected.palette.map(::hex), actual.palette.map(::hex))
        }
        println("[palette] ${all.size} fixtures match the reference")
    }

    @Test
    fun aCoverWithoutColourGivesTheNeutralNeon() {
        val grey = IntArray(48 * 48) { 0xFF808080.toInt() }
        assertEquals(CoverPalette.NEUTRAL, CoverPalette.of(grey, 48, 48))
        assertEquals(CoverPalette.NEUTRAL, CoverPalette.of(IntArray(0), 0, 0))
    }
}
