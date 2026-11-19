package app.cadence.feature.nowplaying

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/*
 * Text colours that stay readable on the player's moving background.
 *
 * PlayerBackground paints three tinted blobs (up to 85% opaque) over surfaceContainerLowest and then
 * a 25% veil of that same base. Text can therefore sit on the plain base, on any blob, or where blobs
 * overlap. Luminance changes monotonically along each blend, so the darkest and lightest points the
 * text can meet are the blobs at full strength: checking the base plus every blob combination at peak
 * covers the whole background, wherever the drift has taken it.
 */

/** Opacity of a blob at its centre and of the veil drawn over everything (see PlayerBackground). */
internal const val BLOB_ALPHA = 0.85f
internal const val VEIL_ALPHA = 0.25f

/** WCAG contrast ratio between two colours (1 to 21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Every colour the player background can show under the text: the base and each blob overlap at peak. */
internal fun playerBackdrops(base: Color, blobs: List<Color>): List<Color> {
    val out = ArrayList<Color>()
    for (mask in 0 until (1 shl blobs.size)) {
        var c = base
        blobs.forEachIndexed { i, blob -> if (mask and (1 shl i) != 0) c = blob.copy(alpha = BLOB_ALPHA).compositeOver(c) }
        out += base.copy(alpha = VEIL_ALPHA).compositeOver(c)
    }
    return out
}

/** The lowest contrast [fg] has against any of [backdrops]. */
internal fun worstContrast(fg: Color, backdrops: List<Color>): Float = backdrops.minOf { contrastRatio(fg, it) }

/**
 * [fg] if it already reaches [minRatio] against every backdrop; otherwise [fg] moved step by step
 * towards white or black (whichever reads better there), so the theme's tint is kept as long as
 * possible. If even pure white or black cannot reach [minRatio], the better of the two.
 */
internal fun readableOn(fg: Color, backdrops: List<Color>, minRatio: Float = 4.5f): Color {
    if (worstContrast(fg, backdrops) >= minRatio) return fg
    val target = if (worstContrast(Color.White, backdrops) >= worstContrast(Color.Black, backdrops)) Color.White else Color.Black
    for (step in 1..20) {
        val f = step / 20f
        val candidate = Color(
            red = fg.red + (target.red - fg.red) * f,
            green = fg.green + (target.green - fg.green) * f,
            blue = fg.blue + (target.blue - fg.blue) * f,
        )
        if (worstContrast(candidate, backdrops) >= minRatio) return candidate
    }
    return target
}

/** Title and artist colours for the player, readable on its background. */
internal data class PlayerTextColors(val title: Color, val artist: Color)

internal fun playerTextColors(cs: ColorScheme): PlayerTextColors {
    val backdrops = playerBackdrops(cs.surfaceContainerLowest, listOf(cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer))
    // WCAG AA for body text (4.5:1) for both lines: the artist line is small, and the title marquee
    // scrolls, so neither should rely on the large-text allowance.
    return PlayerTextColors(title = readableOn(cs.onSurface, backdrops), artist = readableOn(cs.onSurfaceVariant, backdrops))
}
