package app.cadence.backend.engine

import kotlin.math.abs

/**
 * Chooses the theme seed colour from a cover, without decoding the image.
 *
 * Apple already ships a palette with every artwork object (`bgColor` plus `textColor1..4`), so the
 * fast and reliable path is to reorder *those* colours rather than fetch and analyse pixels: the
 * first colour returned is the most vibrant one usable as a Material seed, and a couple more are
 * kept for gradients. Pure and deterministic - every branch is covered by ArtworkPaletteTest.
 */
object ArtworkPalette {
    /** App default when a cover ships no usable colours (e.g. procedural art). */
    val DEFAULT = listOf("#6750A4", "#21005D")

    /**
     * Apple's cover palette (any of bgColor/textColor1..4, nulls/blanks allowed), ordered
     * most-vibrant first and de-duplicated, capped to three. Falls back to [DEFAULT] when none of
     * the inputs are real colours. Always at least two entries: screens draw these as gradients,
     * and a gradient with one stop throws. A cover with a single usable colour (only `bgColor`,
     * or text colours equal to it) pairs it with a dark neutral, as before this palette existed.
     */
    fun themeColors(appleHexes: List<String?>): List<String> {
        val valid = appleHexes.mapNotNull(::normalize).distinct()
        if (valid.isEmpty()) return DEFAULT
        val ranked = valid.sortedByDescending(::vibrancy).take(3)
        if (ranked.size >= 2) return ranked
        val only = ranked.single()
        return listOf(only, if (only == NEUTRAL) DEFAULT[0] else NEUTRAL)
    }

    /** The dark partner of a single-colour cover. */
    private const val NEUTRAL = "#1b1b1f"

    /**
     * Vibrancy score (0 = unusable grey/black/white, higher = more vivid). Saturated colours at a
     * mid lightness win; near-black, near-white and greys lose, so a cover's accent beats its
     * legibility-driven text colours. Uses HSL saturation with a symmetric lightness penalty.
     */
    fun vibrancy(hex: String): Double {
        val (r, g, b) = toRgb(normalize(hex) ?: return 0.0)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        val sat = if (max == min) 0.0 else (max - min) / (1.0 - abs(2 * l - 1.0)).coerceAtLeast(1e-6)
        return sat * (1.0 - abs(l - 0.5))
    }

    /** Normalises "ffffff", "#FFFFFF", "fff", "ffffffff" (alpha ignored) to "#rrggbb", else null. */
    fun normalize(hex: String?): String? {
        val h = hex?.trim()?.removePrefix("#")?.lowercase() ?: return null
        val rgb = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            6 -> h
            8 -> h.substring(0, 6) // drop alpha
            else -> return null
        }
        if (rgb.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return "#$rgb"
    }

    private fun toRgb(hex: String): Triple<Double, Double, Double> {
        val h = hex.removePrefix("#")
        return Triple(
            h.substring(0, 2).toInt(16) / 255.0,
            h.substring(2, 4).toInt(16) / 255.0,
            h.substring(4, 6).toInt(16) / 255.0,
        )
    }
}
