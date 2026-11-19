package app.cadence.core.design

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cover's own palette for the neon tube and the neon wave bar, computed from its pixels.
 *
 * A port of the desktop visualiser's `paletteOf` (art.js), so the neon looks the same on the phone
 * as on the desktop. Apple's artwork metadata only carries legibility colours (background and text tones,
 * often greys); this reads the image instead: the most weighted vivid hue is the accent, the next
 * clearly different hue (at least 40° away) the second colour, and a third hue or a light variant
 * of the accent the third, all lifted to a lightness that glows on a dark background. Grey covers
 * give a warm white / silver neon. Pure and deterministic; parity with the JavaScript original is
 * checked by CoverPaletteParityTest.
 */
object CoverPalette {
    /** Accent first, then two more colours; each packed as 0xRRGGBB. */
    data class Result(val accent: Int, val palette: List<Int>)

    /** Warm white, silver and white: the neon of a cover without colour. */
    val NEUTRAL = Result(rgb(242, 240, 236), listOf(rgb(242, 240, 236), rgb(196, 204, 224), rgb(255, 255, 255)))

    private class Bucket(var w: Double = 0.0, var h: Double = 0.0, var s: Double = 0.0, var l: Double = 0.0)
    private class Tone(val w: Double, val h: Double, val s: Double, val l: Double)

    /** [argb] holds [w]×[h] pixels as 0xAARRGGBB (what `Bitmap.getPixels` returns). */
    fun of(argb: IntArray, w: Int, h: Int): Result {
        if (w <= 0 || h <= 0 || argb.size < w * h) return NEUTRAL
        val buckets = Array(18) { Bucket() }
        val step = max(1, floor(min(w, h) / 24.0).toInt())
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = argb[y * w + x]
                val (hh, s, l) = rgbToHsl((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                val weight = s * s * max(0.0, 1 - abs(l - 0.55) * 1.8)
                if (weight > 0.01) {
                    val b = buckets[floor(hh * 18).toInt() % 18]
                    b.w += weight
                    b.h += hh * weight
                    b.s += s * weight
                    b.l += l * weight
                }
                x += step
            }
            y += step
        }
        val ranked = buckets.filter { it.w > 0 }
            .map { Tone(it.w, it.h / it.w, it.s / it.w, it.l / it.w) }
            .sortedByDescending { it.w } // stable, like Array.prototype.sort
        if (ranked.isEmpty() || ranked[0].w < 1.5) return NEUTRAL
        fun hueGap(a: Double, b: Double) = min(abs(a - b), 1 - abs(a - b))
        fun tone(c: Tone, lMin: Double, lMax: Double) = hslToRgb(c.h, min(0.9, max(0.5, c.s)), min(lMax, max(lMin, c.l)))
        val first = ranked[0]
        val second = ranked.firstOrNull { hueGap(it.h, first.h) >= 1.0 / 9 && it.w >= first.w * 0.08 }
        val third = second?.let { sec ->
            ranked.firstOrNull { it !== first && it !== sec && hueGap(it.h, first.h) >= 1.0 / 12 && hueGap(it.h, sec.h) >= 1.0 / 12 && it.w >= first.w * 0.05 }
        }
        val accent = tone(first, 0.64, 0.74)
        val c2 = if (second != null) tone(second, 0.58, 0.70) else tone(Tone(first.w, (first.h + 0.06) % 1, first.s, first.l), 0.56, 0.66)
        val c3 = if (third != null) tone(third, 0.62, 0.76) else tone(Tone(first.w, first.h, first.s * 0.7, first.l), 0.80, 0.86)
        return Result(accent, listOf(accent, c2, c3))
    }

    internal fun rgbToHsl(r255: Int, g255: Int, b255: Int): Triple<Double, Double, Double> {
        val r = r255 / 255.0
        val g = g255 / 255.0
        val b = b255 / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return Triple(h / 6, s, l)
    }

    internal fun hslToRgb(h: Double, s: Double, l: Double): Int {
        if (s == 0.0) return rgb(jsRound(l * 255), jsRound(l * 255), jsRound(l * 255))
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Double): Double {
            val t = (t0 + 1) % 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return rgb(jsRound(f(h + 1.0 / 3) * 255), jsRound(f(h) * 255), jsRound(f(h - 1.0 / 3) * 255))
    }

    /** JavaScript's Math.round: halves go up (towards +∞). */
    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
}

/** The palette as Compose colours, accent first. */
fun CoverPalette.Result.colors(): List<Color> = palette.map { Color(0xFF000000.toInt() or it) }

private val paletteCache = LruCache<String, CoverPalette.Result>(64)

/**
 * Warm the caches for a cover that is about to play, so when the user skips to it the art shows and
 * the neon is themed with no on-screen work: download the one shared source to disk, decode a display
 * bucket, and compute + cache the palette (the two things that otherwise run during the skip
 * animation and skip frames). Every stage is a cache hit after the first, so repeat calls are cheap.
 * Keyed by the same [coverUrl] the player passes to [rememberCoverPalette].
 */
suspend fun prefetchCover(context: Context, coverUrl: String?) {
    val url = coverUrl ?: return
    loadCoverBitmap(context, url, 512) // downloads the shared source + warms a display bucket
    if (paletteCache.get(url) != null) return
    val bitmap = loadCoverBitmap(context, url, 128) ?: return
    val palette = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        CoverPalette.of(pixels, bitmap.width, bitmap.height)
    }
    paletteCache.put(url, palette)
}

/**
 * The cover's neon palette, or null until it is ready (callers keep their own fallback). Decoded
 * small: the analysis samples about a 24×24 grid, so a 128 px bitmap gives the same result as the
 * full-size cover.
 */
@Composable
fun rememberCoverPalette(coverUrl: String?): CoverPalette.Result? {
    val bitmap = rememberCover(coverUrl, targetPx = 128)
    val result by produceState(coverUrl?.let(paletteCache::get), coverUrl, bitmap) {
        val url = coverUrl ?: return@produceState
        paletteCache.get(url)?.let { value = it; return@produceState }
        val image = bitmap ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val b = image.asAndroidBitmap()
            val pixels = IntArray(b.width * b.height)
            b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
            CoverPalette.of(pixels, b.width, b.height)
        }.also { paletteCache.put(url, it) }
    }
    return result
}
