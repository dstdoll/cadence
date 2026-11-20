package app.cadence.feature.nowplaying

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cadence.playback.WaveHub
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The neon tube: a line of light hugging the artwork's outline, from which waves grow
 * that follow the real audio. Each of the analyser's eight bands is one wave along the contour
 * (bass = long slow swells, treble = fine fast ripples) whose height is that band's energy; bass
 * hits flash it and the overall level opens the halo. Ported from `neon.js`: the fragment shader
 * is the same maths in AGSL, and the per-frame smoothing below is its `tick`.
 *
 * Two ways of drawing it, one effect. From Android 13 it is the AGSL shader: one pass, the maths
 * above per pixel. Below that `RuntimeShader` does not exist, so [drawNeonFallback] walks the
 * outline and strokes the wave as a path, widening and fading it to stand in for the halo. It is
 * the same contour, the same eight bands and the same smoothing -- only the halo is coarser,
 * because a stroke cannot be a true gaussian.
 *
 * It used to be gated to Android 13 and simply absent below, which also left the setting offering
 * something that did nothing.
 */
@Composable
fun Modifier.neonTube(enabled: Boolean, hub: WaveHub, playing: Boolean, palette: List<Color>, cornerRadius: Dp): Modifier {
    if (!enabled) return this
    val state = remember { NeonState() }
    var frame by remember { mutableLongStateOf(0L) }
    // The loop starts once; it reads the play state through this so a later play/pause reaches it.
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { nanos -> state.tick(nanos, hub.levels.value, isPlaying); frame = nanos }
    }
    val colors = remember(palette) { List(3) { palette[it % palette.size.coerceAtLeast(1)] } }
    return this.drawBehind {
        @Suppress("UNUSED_EXPRESSION") frame
        val margin = 28.dp.toPx()
        val full = Size(size.width + 2 * margin, size.height + 2 * margin)
        val shader = state.shader
        val brush = state.brush
        if (shader != null && brush != null) {
            state.bind(shader, full, margin, size, cornerRadius.toPx(), density, colors)
            translate(-margin, -margin) { drawRect(brush, size = full) }
        } else {
            drawNeonFallback(state, size, cornerRadius.toPx(), density, colors)
        }
    }
}

/** The smoothed amplitudes, phases, glow and flash of one tube, advanced once per frame. */
private class NeonState {
    val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(NEON_AGSL) }
                .onFailure { app.cadence.backend.engine.CadenceLog.e("neon", "tube shader did not compile", it) }
                .getOrNull()
        } else null
    /** One brush for the lifetime of the shader; its uniforms change, the brush does not. */
    val brush: ShaderBrush? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.let { ShaderBrush(it) } else null
    private var boundGeometry = FloatArray(0)
    private var boundColors: List<Color>? = null
    // Internal, not private: the fallback draw reads the same smoothed state the shader binds,
    // so both ways of drawing animate identically.
    val amp = FloatArray(8)
    val phase = FloatArray(8)
    private val uAmp = FloatArray(8)
    var glow = 0.16f
        private set
    var flash = 0f
        private set
    private var ignite = 0f
    private var lastNanos = 0L
    var seconds = 0f
        private set

    fun tick(nanos: Long, levels: app.cadence.playback.WaveLevels, playing: Boolean) {
        val dt = if (lastNanos == 0L) 1f / 60f else min(0.05f, (nanos - lastNanos) / 1e9f)
        lastNanos = nanos
        seconds = (seconds + dt) % 25f
        ignite = if (playing) min(1f, ignite + dt / IGNITE_S) else max(0f, ignite - dt / IGNITE_S)
        val attack = 1f - exp(-dt / ATTACK_S)
        val release = 1f - exp(-dt / RELEASE_S)
        for (k in 0 until 8) {
            val target = if (playing) levels.bands[k] * ignite else 0f
            amp[k] += (target - amp[k]) * (if (target > amp[k]) attack else release)
            phase[k] = ((phase[k] + dt * SPEED[k] * (0.6f + 1.4f * amp[k])) % TWO_PI + TWO_PI) % TWO_PI
        }
        val glowTarget = if (playing) 0.25f + 0.75f * levels.level.pow(1.2f) * ignite else 0.16f
        glow += (glowTarget - glow) * (1f - exp(-dt / 0.12f))
        flash = max(if (playing) levels.hit * ignite else 0f, flash * exp(-dt / 0.16f))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun bind(shader: RuntimeShader, full: Size, margin: Float, card: Size, radius: Float, scale: Float, colors: List<Color>) {
        var total = 0f
        for (k in 0 until 8) total += amp[k] * AMP_DP[k]
        // A compressor on the sum of the waves: loud passages surge without turning into a scribble.
        val gain = if (total > KNEE_DP) (KNEE_DP + (total - KNEE_DP) * RATIO) / total else 1f
        for (k in 0 until 8) uAmp[k] = amp[k] * AMP_DP[k] * gain * scale
        // Geometry and colours rarely change; each uniform is a JNI call, so they are sent only then.
        val geometry = floatArrayOf(full.width, full.height, margin, card.width, card.height, radius, scale)
        if (!geometry.contentEquals(boundGeometry)) {
            shader.setFloatUniform("u_size", full.width, full.height)
            shader.setFloatUniform("u_rect", margin, margin, card.width, card.height)
            shader.setFloatUniform("u_radius", radius)
            shader.setFloatUniform("u_gap", GAP_DP * scale)
            shader.setFloatUniform("u_scale", scale)
            shader.setFloatUniform("u_cycles", CYCLES)
            boundGeometry = geometry
        }
        if (colors != boundColors) {
            colors.forEachIndexed { i, c -> shader.setFloatUniform(COLOR_UNIFORMS[i], c.red, c.green, c.blue) }
            boundColors = colors
        }
        shader.setFloatUniform("u_time", seconds)
        shader.setFloatUniform("u_amp", uAmp)
        shader.setFloatUniform("u_phase", phase)
        shader.setFloatUniform("u_glow", glow)
        shader.setFloatUniform("u_flash", flash)
    }

    companion object {
        private val COLOR_UNIFORMS = arrayOf("u_c1", "u_c2", "u_c3")
        const val TWO_PI = (2.0 * PI).toFloat()
        // Whole waves around the contour per band (integers: the wave closes without a seam) and
        // their base speed in rad/s, alternating direction so the waves cross instead of spinning.
        val CYCLES = floatArrayOf(2f, 3f, 5f, 7f, 11f, 16f, 23f, 31f)
        val SPEED = floatArrayOf(0.55f, -0.85f, 1.25f, -1.7f, 2.3f, -3.0f, 3.8f, -4.7f)
        // Peak height of each band in dp. The desktop values, scaled up because phone artwork is
        // about twice the size of its desktop card.
        val AMP_DP = floatArrayOf(3.3f, 3.3f, 3.9f, 3.9f, 3.6f, 3.2f, 2.7f, 2.2f).map { it * 1.8f }.toFloatArray()
        const val KNEE_DP = 14f * 1.8f
        const val RATIO = 0.4f
        const val GAP_DP = 1.5f
        const val ATTACK_S = 0.035f
        const val RELEASE_S = 0.2f
        const val IGNITE_S = 0.6f
    }
}


/**
 * The neon drawn without a shader, for Android below 13.
 *
 * It reproduces what the AGSL does, in the order the shader builds it up:
 *
 *  1. **the body** -- the lit area between the artwork's edge and the crest of the wave, filled
 *     at low alpha, which is the shader's `fill`;
 *  2. **the halo** -- six strokes on the crest, each wider and fainter, approximating the
 *     exponential falloff of `exp(-|e1| / sigma)`;
 *  3. **the tube** -- the line hugging the outline itself, which never moves;
 *  4. **the crest** -- the bright edge riding on top of the wave;
 *  5. **the flash** -- a white pass on a bass hit, the shader's `mix(col, white, ...)`.
 *
 * Three things the shader has that a stroke cannot: a true gaussian halo, per-pixel colour, and
 * the halo dying instantly inside the card. The halo is approximated by stacking passes, colour
 * comes from a sweep gradient that turns with time, and the body is clipped to the outside of the
 * artwork so the cover stays clean.
 *
 * `Modifier.blur` and `RenderEffect` would be the obvious way to do the halo and are Android 12+,
 * which does not help here; a `BlurMaskFilter` would force the whole card into a software layer.
 */
private fun DrawScope.drawNeonFallback(
    state: NeonState,
    card: Size,
    radius: Float,
    scale: Float,
    colors: List<Color>,
) {
    if (card.minDimension <= 2 * radius || colors.isEmpty()) return
    val r = radius.coerceAtMost(card.minDimension / 2f)
    val straightX = card.width - 2 * r
    val straightY = card.height - 2 * r
    val arc = (PI.toFloat() / 2f) * r
    val perimeter = 2 * straightX + 2 * straightY + 4 * arc
    if (perimeter <= 0f) return

    var total = 0f
    for (k in 0 until 8) total += state.amp[k] * NeonState.AMP_DP[k]
    val gain = if (total > NeonState.KNEE_DP) {
        (NeonState.KNEE_DP + (total - NeonState.KNEE_DP) * NeonState.RATIO) / total
    } else 1f
    val heights = FloatArray(8) { state.amp[it] * NeonState.AMP_DP[it] * gain * scale }
    val gap = NeonState.GAP_DP * scale
    val soft = 0.8f * scale

    // The tube sits on the outline; the crest rides the wave above it. Walking the outline once
    // builds both, and the body is the ring between them.
    val tube = Path()
    val crest = Path()
    val body = Path()
    val back = ArrayList<Offset>(SAMPLES + 1)
    for (i in 0..SAMPLES) {
        val u = i.toFloat() / SAMPLES
        var wave = 0f
        for (k in 0 until 8) wave += heights[k] * sin(TAU * NeonState.CYCLES[k] * u + state.phase[k])
        // Softplus: the same one-sided rectification as the shader, so the wave only grows outwards.
        val h = soft * ln(1f + kotlin.math.exp(wave / soft))
        val (point, normal) = outlinePoint(u * perimeter, card, r, straightX, straightY, arc)
        val bx = point.x + normal.x * gap
        val by = point.y + normal.y * gap
        val cx = point.x + normal.x * (gap + h)
        val cy = point.y + normal.y * (gap + h)
        if (i == 0) {
            tube.moveTo(bx, by); crest.moveTo(cx, cy); body.moveTo(cx, cy)
        } else {
            tube.lineTo(bx, by); crest.lineTo(cx, cy); body.lineTo(cx, cy)
        }
        back.add(Offset(bx, by))
    }
    // Close the body by coming back along the tube, which makes it the ring of lit area.
    for (i in back.indices.reversed()) body.lineTo(back[i].x, back[i].y)
    body.close()
    tube.close()
    crest.close()

    // Colour travels around the contour: a sweep gradient whose stops turn with time, standing in
    // for the shader's palette(fract(u - u_time * 0.04)).
    val shift = (state.seconds * 0.04f) % 1f
    val wheel = List(colors.size + 1) { colors[it % colors.size] }
    val stops = Array(wheel.size) { i ->
        (((i.toFloat() / (wheel.size - 1)) + shift) % 1f) to wheel[i]
    }.sortedBy { it.first }.toTypedArray()
    val brush = Brush.sweepGradient(*stops, center = Offset(card.width / 2f, card.height / 2f))

    val glow = state.glow
    val flash = state.flash
    val core = (0.7f + 0.4f * glow) * scale
    val strength = 0.2f + 0.5f * glow + 0.4f * flash

    // 1) the body of light between the artwork and the crest
    drawPath(body, brush, alpha = (0.14f + 0.36f * glow).coerceIn(0f, 1f))

    // 2) the halo: stacked passes standing in for the gaussian. Widest and faintest first.
    val spread = 1f + 2.6f * glow + 2.2f * flash
    for ((widthFactor, alphaFactor) in HALO) {
        drawPath(
            path = crest,
            brush = brush,
            alpha = (strength * alphaFactor).coerceIn(0f, 1f),
            style = Stroke(core * widthFactor * spread, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // 3) the tube on the outline, which stays put however loud it gets
    drawPath(
        path = tube,
        brush = brush,
        alpha = (0.75f + 0.25f * glow).coerceIn(0f, 1f),
        style = Stroke(core * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // 4) the crest, brighter than the body it caps
    drawPath(
        path = crest,
        brush = brush,
        alpha = (0.6f + 0.4f * glow).coerceIn(0f, 1f),
        style = Stroke(core * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // 5) the white core, and the flash on a bass hit
    drawPath(
        path = tube,
        color = Color.White,
        alpha = (0.22f + 0.5f * flash).coerceIn(0f, 1f),
        style = Stroke(core * 0.55f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    if (flash > 0.01f) {
        drawPath(
            path = crest,
            color = Color.White,
            alpha = (flash * 0.45f).coerceIn(0f, 1f),
            style = Stroke(core * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Point on the rounded outline at arc length [s], and the outward unit normal there. */
private fun outlinePoint(
    s: Float,
    card: Size,
    r: Float,
    straightX: Float,
    straightY: Float,
    arc: Float,
): Pair<Offset, Offset> {
    var d = s
    if (d < straightX) return Offset(r + d, 0f) to UP
    d -= straightX
    if (d < arc) {
        val a = -PI.toFloat() / 2f + d / r
        return Offset(card.width - r + r * cos(a), r + r * sin(a)) to Offset(cos(a), sin(a))
    }
    d -= arc
    if (d < straightY) return Offset(card.width, r + d) to RIGHT
    d -= straightY
    if (d < arc) {
        val a = d / r
        return Offset(card.width - r + r * cos(a), card.height - r + r * sin(a)) to Offset(cos(a), sin(a))
    }
    d -= arc
    if (d < straightX) return Offset(card.width - r - d, card.height) to DOWN
    d -= straightX
    if (d < arc) {
        val a = PI.toFloat() / 2f + d / r
        return Offset(r + r * cos(a), card.height - r + r * sin(a)) to Offset(cos(a), sin(a))
    }
    d -= arc
    if (d < straightY) return Offset(0f, card.height - r - d) to LEFT
    d -= straightY
    val a = PI.toFloat() + (d / r).coerceAtMost(PI.toFloat() / 2f)
    return Offset(r + r * cos(a), r + r * sin(a)) to Offset(cos(a), sin(a))
}

/** Samples around the outline. The fastest band closes 31 waves, so this leaves ~12 points each. */
private const val SAMPLES = 384
private const val TAU = (2.0 * PI).toFloat()
private val UP = Offset(0f, -1f)
private val DOWN = Offset(0f, 1f)
private val LEFT = Offset(-1f, 0f)
private val RIGHT = Offset(1f, 0f)

/**
 * Halo passes: (width multiple, alpha multiple), widest and faintest first.
 *
 * Six of them rather than three: stacking more, fainter passes is what turns a stack of strokes
 * into something that reads as a glow instead of as concentric outlines.
 */
private val HALO = listOf(
    11f to 0.06f,
    8f to 0.09f,
    6f to 0.13f,
    4.5f to 0.18f,
    3.2f to 0.24f,
    2.2f to 0.32f,
)

private const val NEON_AGSL = """
uniform float2 u_size;
uniform float4 u_rect;
uniform float u_radius;
uniform float u_gap;
uniform float u_scale;
uniform float u_time;
uniform float u_amp[8];
uniform float u_phase[8];
uniform float u_cycles[8];
uniform float3 u_c1;
uniform float3 u_c2;
uniform float3 u_c3;
uniform float u_glow;
uniform float u_flash;

float3 palette(float f) {
    float a = f * 3.0;
    if (a < 1.0) return mix(u_c1, u_c2, smoothstep(0.0, 1.0, a));
    if (a < 2.0) return mix(u_c2, u_c3, smoothstep(0.0, 1.0, a - 1.0));
    return mix(u_c3, u_c1, smoothstep(0.0, 1.0, a - 2.0));
}

half4 main(float2 px) {
    float2 hs = u_rect.zw * 0.5;
    float2 p = px - (u_rect.xy + hs);
    float r = u_radius;
    float2 inner = max(hs - float2(r), float2(0.0));
    float2 v = p - clamp(p, -inner, inner);
    float alpha = 0.0;
    float3 rgb = float3(0.0);
    // Signed distance to the rounded outline. Deep under the cover the light is below 1/1000 and past
    // the fade it is exactly 0, so those pixels skip the expensive part (about a third of them).
    float d = length(v) - r;
    if ((v.x != 0.0 || v.y != 0.0) && d > -12.0 * u_scale && d < 0.95 * u_rect.x) {
        // The position along the outline (true arc length).
        float T = 2.0 * inner.x;
        float A = 1.57079633 * r;
        float H = 2.0 * inner.y;
        float L = 2.0 * T + 4.0 * A + 2.0 * H;
        float s;
        if (v.x == 0.0) {
            s = v.y < 0.0 ? p.x + inner.x : T + 2.0 * A + H + (inner.x - p.x);
        } else if (v.y == 0.0) {
            s = v.x > 0.0 ? T + A + (p.y + inner.y) : 2.0 * T + 3.0 * A + H + (inner.y - p.y);
        } else {
            float th = atan(v.y, v.x);
            if (v.x > 0.0 && v.y < 0.0) s = T + r * (th + 1.57079633);
            else if (v.x > 0.0) s = T + A + H + r * th;
            else if (v.y > 0.0) s = 2.0 * T + 2.0 * A + H + r * (th - 1.57079633);
            else s = 2.0 * T + 3.0 * A + 2.0 * H + r * (th + 3.14159265);
        }
        float u = s / L;
        // The wave: the eight bands summed at this point of the outline, softly rectified so it
        // only grows outwards. The tube never leaves the card; the wave is a body of light on it.
        float wave = 0.0;
        for (int k = 0; k < 8; k++) {
            wave += u_amp[k] * sin(6.28318531 * u_cycles[k] * u + u_phase[k]);
        }
        float soft = 0.8 * u_scale;
        float h = soft * log(1.0 + exp(wave / soft));
        float e0 = d - u_gap;
        float e1 = e0 - h;
        float aa = 1.1 * u_scale;
        float w = (0.7 + 0.4 * u_glow) * u_scale;
        float tube = 1.0 - smoothstep(w, w + aa, abs(e0));
        float crest = (1.0 - smoothstep(0.8 * w, 0.8 * w + aa, abs(e1))) * smoothstep(0.5 * aa, 2.0 * aa, h);
        float band = smoothstep(0.0, aa, e0) * (1.0 - smoothstep(-aa, 0.0, e1));
        float fill = band * (0.14 + 0.36 * clamp(e0 / max(h, aa), 0.0, 1.0));
        // Halo: inside the card it dies at once (the cover stays clean); outside it rises from tube and crest.
        float sigma = (4.0 + 9.0 * u_glow + 6.0 * u_flash) * u_scale;
        float halo = e0 < 0.0 ? exp(e0 / (1.6 * u_scale)) : max(exp(-abs(e1) / sigma), exp(-e0 / sigma));
        float strength = 0.2 + 0.5 * u_glow + 0.4 * u_flash;
        float core = max(tube, crest);
        alpha = clamp(core * (0.7 + 0.3 * u_glow) + fill + halo * strength, 0.0, 1.0);
        // The halo fades following the card's shape before the canvas edge: never a clipped box.
        alpha *= 1.0 - smoothstep(0.55 * u_rect.x, 0.95 * u_rect.x, d);
        float3 col = palette(fract(u - u_time * 0.04));
        rgb = mix(col, float3(1.0), core * (0.32 + 0.3 * u_flash));
    }
    return half4(half3(rgb * alpha), half(alpha));
}
"""
