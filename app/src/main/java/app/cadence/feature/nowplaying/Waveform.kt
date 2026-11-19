package app.cadence.feature.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.state.settings.WaveStyle
import kotlin.math.max

/**
 * The seek bar as the track's real waveform, filled in as it plays. The measure is the desktop
 * (RMS of each slice against the 99.5th percentile, see `LiveWave`) and so is the drawing: one
 * column every 3 dp, each the loudest of its slices, unmeasured parts a thin line. The waveform
 * itself lives in the playback service's hub, so it keeps growing while this is off screen.
 *
 * @param fraction playback progress 0..1, read during draw so scrubbing does not recompose.
 */
@Composable
fun WaveformBar(
    style: WaveStyle,
    isPlaying: Boolean,
    fraction: () -> Float,
    played: Color,
    rest: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val hub = LocalContainer.current.wave
    val version by hub.waveVersion.collectAsStateWithLifecycle()
    // A new value with every analyser block (about 43 a second) while playing. It is only read inside
    // the draw lambdas below, so it redraws the bar without recomposing it (a recomposition also
    // rebuilt the blur's RenderEffect).
    val levels by hub.levels.collectAsStateWithLifecycle()
    val neon = style == WaveStyle.Neon
    val cache = remember { BarCache() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    val currentFraction by rememberUpdatedState(fraction)
    // The blurred layer is the expensive pass. It only changes when another bar becomes played (or
    // with the levels), so it reads this count instead of the playhead, which moves every frame.
    val playedBars by remember(stepPx) { derivedStateOf { playedBarCount(currentFraction().coerceIn(0f, 1f), widthPx, stepPx) } }

    Box(modifier.fillMaxWidth().height(40.dp).graphicsLayer().onSizeChanged { widthPx = it.width.toFloat() }) {
        if (neon) {
            // The light: the played part drawn wide and blurred, breathing with level and bass hits.
            Canvas(Modifier.matchParentSize().blur(9.dp, BlurredEdgeTreatment.Unbounded)) {
                val pulse = if (isPlaying) (0.45f + 0.55f * levels.level + 0.6f * levels.hit).coerceIn(0f, 1.4f) else 0.35f
                val step = 3.dp.toPx()
                val heights = cache.heights(hub.waveLevels, version, max(1, (size.width / step).toInt()))
                drawBars(heights, step, playedProgress(playedBars, size.width, step), accent.copy(alpha = (0.55f * pulse).coerceIn(0f, 1f)), Color.Transparent, widen = 2.4f, mirror = true, grow = 1.25f)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val step = 3.dp.toPx()
            // Reading the version here redraws when the waveform gains slices.
            val heights = cache.heights(hub.waveLevels, version, max(1, (size.width / step).toInt()))
            val progress = fraction().coerceIn(0f, 1f)
            when (style) {
                WaveStyle.Line -> drawLine(heights, step, progress, played, rest)
                // The neon bar: what has played runs from warm white at the start to the cover's
                // accent at the playhead (bass hits brighten the accent end a little).
                WaveStyle.Neon -> drawBars(
                    heights, step, progress, accent, rest, widen = 1f, mirror = true,
                    playedBrush = cache.playedBrush(lerpToWhite(accent, 0.25f * levels.hit), progress * size.width),
                )
                WaveStyle.Mirror -> drawBars(heights, step, progress, played, rest, widen = 1f, mirror = true)
                else -> drawBars(heights, step, progress, played, rest, widen = 1f, mirror = false)
            }
        }
    }
}

/** Each column takes the loudest of the slices it covers; 0 stays 0 (not measured). */
internal fun columnHeights(levels: IntArray, columns: Int): FloatArray {
    val out = FloatArray(columns)
    for (c in 0 until columns) {
        val from = c * levels.size / columns
        val to = max(from + 1, (c + 1) * levels.size / columns)
        var top = 0
        for (i in from until minOf(to, levels.size)) if (levels[i] > top) top = levels[i]
        out[c] = top / 255f
    }
    return out
}

internal fun lerpToWhite(c: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(c.red + (1f - c.red) * k, c.green + (1f - c.green) * k, c.blue + (1f - c.blue) * k, c.alpha)
}

/**
 * What the bar keeps between frames. It redraws every frame while the playhead moves (and on every
 * redraw of the sheet around it), but the column heights only change with the waveform, a few times
 * a second, and the neon gradient only with its accent and the playhead.
 */
internal class BarCache {
    private var levels: IntArray? = null
    private var version = 0
    private var heights = FloatArray(0)
    private var brush: Brush? = null
    private var brushAccent = Color.Unspecified
    private var brushUntil = Float.NaN

    /**
     * [columnHeights] of [levels], recomputed only for another array, another [version] (the hub
     * bumps it whenever it repaints the array) or another number of [columns].
     */
    fun heights(levels: IntArray, version: Int, columns: Int): FloatArray {
        if (levels !== this.levels || version != this.version || columns != heights.size) {
            heights = columnHeights(levels, columns)
            this.levels = levels
            this.version = version
        }
        return heights
    }

    /** [neonPlayedBrush], rebuilt only when [accent] or [playedUntil] changed. */
    fun playedBrush(accent: Color, playedUntil: Float): Brush {
        brush?.let { if (accent == brushAccent && playedUntil == brushUntil) return it }
        return neonPlayedBrush(accent, playedUntil).also { brush = it; brushAccent = accent; brushUntil = playedUntil }
    }
}

/** Bars whose centre is at or before the playhead: the rule [drawBars] uses (a bar's centre is c * step + step / 3). */
internal fun playedBarCount(progress: Float, width: Float, step: Float): Int {
    if (width <= 0f || step <= 0f) return 0
    val columns = max(1, (width / step).toInt())
    return (kotlin.math.floor((progress * width - step / 3f) / step).toInt() + 1).coerceIn(0, columns)
}

/**
 * A playhead position that makes [drawBars] mark exactly [count] bars as played: just past the
 * centre of the last played bar (a thousandth of a bar, so rounding cannot drop it), and a whole
 * step short of the next one.
 */
internal fun playedProgress(count: Int, width: Float, step: Float): Float =
    if (count <= 0 || width <= 0f) 0f else ((count - 1) * step + step / 3f + step / 1000f) / width

/** Warm white (slightly translucent) at the start of the track, the accent at [playedUntil]. */
internal fun neonPlayedBrush(accent: Color, playedUntil: Float): Brush =
    Brush.horizontalGradient(
        0f to Color(1f, 1f, 1f, 0.95f),
        1f to accent,
        startX = 0f,
        endX = max(1f, playedUntil),
    )

private fun DrawScope.drawBars(
    heights: FloatArray, step: Float, progress: Float, played: Color, rest: Color, widen: Float, mirror: Boolean, grow: Float = 1f,
    /** When set, played bars are painted with it instead of [played]. */
    playedBrush: Brush? = null,
) {
    val bar = step * 2f / 3f * widen
    val flat = 2.dp.toPx()
    val playedUntil = progress * size.width
    for (c in heights.indices) {
        val x = c * step + (step * 2f / 3f - bar) / 2f
        val h = (if (heights[c] > 0f) max(flat, heights[c] * size.height) else flat) * grow
        val isPlayed = x + bar / 2f <= playedUntil
        val color = if (isPlayed) played else rest
        if (color.alpha == 0f) continue
        val top = if (mirror) (size.height - h) / 2f else size.height - h
        if (isPlayed && playedBrush != null) drawRoundRect(playedBrush, Offset(x, top), Size(bar, h), CornerRadius(bar / 2f, bar / 2f))
        else drawRoundRect(color, Offset(x, top), Size(bar, h), CornerRadius(bar / 2f, bar / 2f))
    }
}

private fun DrawScope.drawLine(heights: FloatArray, step: Float, progress: Float, played: Color, rest: Color) {
    fun outline(until: Int): Path = Path().apply {
        moveTo(0f, size.height)
        for (c in 0 until until) lineTo(c * step + step / 2f, size.height - max(2.dp.toPx(), heights[c] * size.height))
        lineTo(max(0, until - 1) * step + step / 2f, size.height)
        close()
    }
    drawPath(outline(heights.size), rest)
    val playedColumns = (progress * heights.size).toInt().coerceIn(0, heights.size)
    if (playedColumns > 0) drawPath(outline(playedColumns), played)
}
