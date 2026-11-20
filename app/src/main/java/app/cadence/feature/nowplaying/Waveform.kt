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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
    val neon = style == WaveStyle.Neon
    // The neon bar reacts to the music every frame, through the same smoothing as the neon tube,
    // rather than stepping with each analyser block. `frame` is written by the loop and read in
    // the draw lambdas, so a new frame redraws the bar without recomposing it (a recomposition
    // also rebuilt the blur's RenderEffect).
    val realBlur = remember { app.cadence.core.theme.ApiCompat.supportsRenderEffect() }
    val pulse = remember { WavePulse() }
    var frame by remember { mutableLongStateOf(0L) }
    val playingNow by rememberUpdatedState(isPlaying)
    LaunchedEffect(neon) {
        if (!neon) return@LaunchedEffect
        while (true) withFrameNanos { nanos -> pulse.tick(nanos, hub.levels.value, playingNow); frame = nanos }
    }
    val cache = remember { BarCache() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    val currentFraction by rememberUpdatedState(fraction)
    // The blurred layer is the expensive pass. It only changes when another bar becomes played (or
    // with the levels), so it reads this count instead of the playhead, which moves every frame.
    val playedBars by remember(stepPx) { derivedStateOf { playedBarCount(currentFraction().coerceIn(0f, 1f), widthPx, stepPx) } }

    Box(modifier.fillMaxWidth().height(40.dp).graphicsLayer().onSizeChanged { widthPx = it.width.toFloat() }) {
        if (neon && realBlur) {
            // The light: the played part drawn wide and blurred, breathing with level and bass hits.
            Canvas(Modifier.matchParentSize().blur(9.dp, BlurredEdgeTreatment.Unbounded)) {
                @Suppress("UNUSED_EXPRESSION") frame
                val glow = if (isPlaying) (0.45f + 0.55f * pulse.level + 0.6f * pulse.hit).coerceIn(0f, 1.4f) else 0.35f
                val step = 3.dp.toPx()
                val heights = cache.heights(hub.waveLevels, version, max(1, (size.width / step).toInt()))
                drawBars(heights, step, playedProgress(playedBars, size.width, step), accent.copy(alpha = (0.55f * glow).coerceIn(0f, 1f)), Color.Transparent, widen = 2.4f, mirror = true, grow = 1.25f)
            }
        } else if (neon) {
            // The same light without `Modifier.blur`, which is Android 12+ and a silent no-op below:
            // the wide bars used to land unblurred, as hard blocks behind the waveform.
            //
            // The played bars are one cached path, stroked three times -- wider and fainter each
            // time -- so the edges fall off instead of stopping. A shadow layer on that path was
            // the obvious way and looked best, but this Mali GPU renders path shadows in software
            // every frame: it took the median frame from 7 to 18 ms. Stacked strokes cost the GPU
            // next to nothing.
            Canvas(Modifier.matchParentSize()) {
                @Suppress("UNUSED_EXPRESSION") frame
                val glow = if (isPlaying) (0.45f + 0.55f * pulse.level + 0.6f * pulse.hit).coerceIn(0f, 1.4f) else 0.35f
                val step = 3.dp.toPx()
                val heights = cache.heights(hub.waveLevels, version, max(1, (size.width / step).toInt()))
                val path = cache.glowPath(heights, step, playedBars, size, version, 2.dp.toPx())
                for ((width, alpha) in GLOW_PASSES) {
                    drawPath(
                        path, accent, alpha = (alpha * glow).coerceIn(0f, 1f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
                drawPath(path, accent, alpha = (0.32f * glow).coerceIn(0f, 1f))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") if (neon) frame
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
                    playedBrush = cache.playedBrush(lerpToWhite(accent, 0.25f * pulse.hit), progress * size.width),
                )
                WaveStyle.Mirror -> drawBars(heights, step, progress, played, rest, widen = 1f, mirror = true)
                else -> drawBars(heights, step, progress, played, rest, widen = 1f, mirror = false)
            }
        }
    }
}

/**
 * The bar's level and bass hit, advanced once per frame.
 *
 * The analyser delivers a block about 43 times a second, so reading its values straight into the
 * drawing makes the flash step at 43 Hz on a 60 Hz panel: it reads as the bar lagging, even when
 * every frame is drawn on time. This is the same attack/release smoothing the neon tube already
 * does in `NeonState.tick`, so both react to the music the same way and both move at frame rate.
 */
private class WavePulse {
    var level = 0f
        private set
    var hit = 0f
        private set
    private var lastNanos = 0L

    fun tick(nanos: Long, levels: app.cadence.playback.WaveLevels, playing: Boolean) {
        val dt = if (lastNanos == 0L) 1f / 60f else min(0.05f, (nanos - lastNanos) / 1e9f)
        lastNanos = nanos
        // Asymmetric, as the neon tube's bands are: quick to rise so a beat lands on the beat,
        // slower to fall so the bar settles instead of twitching. One shared time constant for
        // both directions made the rise as slow as the fall, which read as the bar lagging.
        val target = if (playing) levels.level else 0f
        val k = if (target > level) LEVEL_ATTACK_S else LEVEL_RELEASE_S
        level += (target - level) * (1f - exp(-dt / k))
        // The hit rises fast but not in one frame -- an instant jump is a step, and at 43 blocks a
        // second the step is what reads as choppy -- and falls away quickly so the flash is crisp.
        val hitTarget = if (playing) levels.hit else 0f
        val kh = if (hitTarget > hit) HIT_ATTACK_S else HIT_RELEASE_S
        hit += (hitTarget - hit) * (1f - exp(-dt / kh))
    }

    private companion object {
        /**
         * The attack is long enough to still be moving when the next analyser block arrives
         * (one every ~23 ms), so the motion carries across blocks instead of stopping on each.
         */
        const val LEVEL_ATTACK_S = 0.035f
        const val LEVEL_RELEASE_S = 0.18f
        const val HIT_ATTACK_S = 0.025f
        const val HIT_RELEASE_S = 0.12f
    }
}

/** The glow's strokes below Android 12: (width in dp, alpha), widest and faintest first. */
private val GLOW_PASSES = listOf(9f to 0.10f, 6f to 0.16f, 3.5f to 0.24f)

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

    /**
     * [neonPlayedBrush], rebuilt only when its appearance would actually change.
     *
     * The key is **quantised**, and that is the whole point. Both inputs move every frame while a
     * track plays -- the accent with each bass hit, the playhead continuously -- so an exact key
     * never matched and every single frame built a new gradient, which means a new native shader
     * and a new Paint for each of the ~90 played bars. It showed up as the bar stuttering exactly
     * when it flashed, because that is when the accent moves fastest.
     *
     * Neither input needs more resolution than this: a 1/32 step per colour channel and four
     * pixels of playhead are both well under what anyone can see on a 40.dp bar.
     */
    fun playedBrush(accent: Color, playedUntil: Float): Brush {
        val key = Color(
            quantise(accent.red), quantise(accent.green), quantise(accent.blue), quantise(accent.alpha),
        )
        val until = (playedUntil / BRUSH_STEP_PX).toInt() * BRUSH_STEP_PX
        brush?.let { if (key == brushAccent && until == brushUntil) return it }
        return neonPlayedBrush(key, until).also { brush = it; brushAccent = key; brushUntil = until }
    }

    private val glow = Path()
    private var glowVersion = -1
    private var glowPlayed = -1
    private var glowSize = Size.Zero

    /**
     * The played bars as one path, for the stroked glow below Android 12. Rebuilt only when
     * another bar becomes played, the waveform changes or the bar is resized -- not per frame,
     * which is when only its colour and blur radius move.
     */
    fun glowPath(heights: FloatArray, step: Float, played: Int, size: Size, version: Int, flat: Float): Path {
        if (played == glowPlayed && version == glowVersion && size == glowSize) return glow
        glow.rewind()
        val bar = step * 2f / 3f * GLOW_WIDEN
        for (c in 0 until played.coerceAtMost(heights.size)) {
            val x = c * step + (step * 2f / 3f - bar) / 2f
            val h = (if (heights[c] > 0f) max(flat, heights[c] * size.height) else flat) * GLOW_GROW
            val top = (size.height - h) / 2f
            glow.addRoundRect(
                androidx.compose.ui.geometry.RoundRect(x, top, x + bar, top + h, CornerRadius(bar / 2f, bar / 2f)),
            )
        }
        glowPlayed = played; glowVersion = version; glowSize = size
        return glow
    }

    private companion object {
        /** The glow's bars are a little wider and taller than the bars they light. */
        const val GLOW_WIDEN = 1.6f
        const val GLOW_GROW = 1.15f
        /** Colour channels to 1/32: finer than the panel shows on a bar this size. */
        fun quantise(v: Float): Float = kotlin.math.round(v * 32f) / 32f
        /** Playhead to four pixels, which is under half a bar. */
        const val BRUSH_STEP_PX = 4f
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
