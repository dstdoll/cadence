package app.cadence.core.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import app.cadence.core.theme.LocalMonochrome
import kotlin.math.abs
import android.graphics.Color as AColor

/**
 * Procedural cover art: a seeded gradient with two large Material shapes on top.
 * Items without an image get a stable, unique cover.
 */
object ArtworkRenderer {
    private const val SIZE = 512
    private val cache = LruCache<String, Bitmap>(32)
    private val shapes by lazy {
        with(MaterialShapes) {
            listOf(Cookie9Sided, Clover4Leaf, Sunny, Flower, SoftBurst, Pill, Arch, Gem, Puffy, Cookie6Sided, Burst, Bun)
        }
    }

    fun parse(hex: String) = runCatching { AColor.parseColor(hex) }.getOrDefault(AColor.GRAY)

    fun seed(colors: List<String>): Color = Color(parse(colors.firstOrNull() ?: "#6750A4"))

    fun bitmap(key: String, colors: List<String>): Bitmap = cache.get(key) ?: render(key, colors).also { cache.put(key, it) }

    /** One large Material shape on a cover: polygon index, size and placement as fractions of the cover. */
    data class ShapeSpec(val shape: Int, val scale: Float, val cx: Float, val cy: Float, val rotation: Float, val colorIndex: Int, val alpha: Int)

    /** Stable layout per key, shared by the static bitmap and the animated (motion) cover so they match. */
    fun specs(key: String): List<ShapeSpec> {
        val h = abs(key.hashCode())
        return listOf(
            ShapeSpec(h, 0.95f, 0.55f + (h % 7) / 40f, 0.6f + (h % 5) / 30f, (h % 360).toFloat(), 2, 220),
            ShapeSpec(h / 7, 0.45f, 0.2f + (h % 3) / 20f, 0.25f, (h % 90).toFloat(), 1, 200),
        )
    }

    fun polygon(index: Int) = shapes[index % shapes.size]

    /** Path of a shape normalized to a unit box centered on the origin (cache it; it never changes). */
    fun unitPath(index: Int): android.graphics.Path {
        val poly = polygon(index)
        val path = poly.toPath()
        val b = poly.calculateBounds()
        val m = Matrix()
        m.postTranslate(-(b[0] + b[2]) / 2, -(b[1] + b[3]) / 2)
        m.postScale(1f / (b[2] - b[0]), 1f / (b[3] - b[1]))
        path.transform(m)
        return path
    }

    fun palette(colors: List<String>): List<Int> {
        val c = colors.map(::parse).ifEmpty { listOf(AColor.GRAY) }
        return listOf(c[0], c.getOrElse(1) { c[0] }, c.getOrElse(2) { c.getOrElse(1) { c[0] } })
    }

    private fun render(key: String, colors: List<String>): Bitmap {
        val (c0, c1, _) = palette(colors)
        val pal = palette(colors)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = SIZE.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(0f, 0f, s, s, c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, s, s, paint)

        for (spec in specs(key)) {
            val path = unitPath(spec.shape)
            val m = Matrix()
            m.postScale(s * spec.scale, s * spec.scale)
            m.postRotate(spec.rotation)
            m.postTranslate(s * spec.cx, s * spec.cy)
            path.transform(m)
            paint.shader = null
            paint.color = pal[spec.colorIndex]
            paint.alpha = spec.alpha
            canvas.drawPath(path, paint)
        }

        // Soft light from the top-left keeps covers from looking flat.
        paint.alpha = 255
        paint.shader = RadialGradient(s * 0.2f, s * 0.1f, s * 0.9f, AColor.argb(70, 255, 255, 255), AColor.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, s, s, paint)
        return bmp
    }
}

/**
 * Apple artwork URLs end with a `<w>x<h>` size token (e.g. `.../300x300bb.jpg`). The catalogue
 * resolves display covers to a fixed literal size, so any code that swaps a hard-coded literal
 * (`replace("300x300", ...)`) silently becomes a no-op the day that literal changes - leaving
 * downloads embedding whatever the display size happened to be instead of the size chosen in
 * Settings. These helpers rewrite the actual last size token instead, so they work whatever the
 * resolved size is (and are a no-op for procedural covers that have no token).
 */
private val ARTWORK_SIZE_TOKEN = Regex("""\d{2,5}x\d{2,5}""")

/** Rewrites a resolved Apple artwork URL to [size] (e.g. "900x900" or the "{w}x{h}" template). */
fun coverAtSize(url: String?, size: String): String? = url?.let { u ->
    val token = ARTWORK_SIZE_TOKEN.findAll(u).lastOrNull() ?: return u
    u.substring(0, token.range.first) + size + u.substring(token.range.last + 1)
}

/** The `{w}x{h}` template for a resolved cover, so downloads can embed the size chosen in Settings. */
fun coverTemplate(url: String?): String? = coverAtSize(url, "{w}x{h}")

/** For large hero images, request a bigger variant than the small grid cover. */
fun hiResCover(url: String?): String? = coverAtSize(url, "900x900")

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun Artwork(
    key: String,
    colors: List<String>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    square: Boolean = true,
    coverUrl: String? = null,
) {
    val bmp = remember(key) { ArtworkRenderer.bitmap(key, colors).asImageBitmap() }
    // Size the fetch to the drawn box (measured once), so one high-res download serves every use.
    // Default until measured; a plain Box keeps the original layout (BoxWithConstraints + a sole
    // matchParentSize child collapses to zero inside a weighted Row, e.g. the mini-player).
    var targetPx by remember { mutableIntStateOf(0) }
    // Real cover art when the backend supplied one; procedural art until it decodes.
    val remote = rememberCover(coverUrl, if (targetPx > 0) targetPx else 512)
    Box(
        (if (square) modifier.aspectRatio(1f) else modifier)
            .clip(shape)
            .onSizeChanged { if (it.width > 0) targetPx = it.width },
    ) {
        Image(
            bitmap = remote ?: bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (LocalMonochrome.current) grayscale else null,
            modifier = Modifier.matchParentSize(),
        )
    }
}
