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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cadence.playback.WaveHub
import kotlin.math.PI
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
 * Needs `RuntimeShader` (Android 13). On Android 12 nothing is drawn here and the glowing seek
 * bar carries the effect alone.
 */
@Composable
fun Modifier.neonTube(enabled: Boolean, hub: WaveHub, playing: Boolean, palette: List<Color>, cornerRadius: Dp): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val state = remember { NeonState() }
    var frame by remember { mutableLongStateOf(0L) }
    // The loop starts once; it reads the play state through this so a later play/pause reaches it.
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        if (state.shader == null) return@LaunchedEffect // nothing to animate without the shader
        while (true) withFrameNanos { nanos -> state.tick(nanos, hub.levels.value, isPlaying); frame = nanos }
    }
    val colors = remember(palette) { List(3) { palette[it % palette.size.coerceAtLeast(1)] } }
    return this.drawBehind {
        @Suppress("UNUSED_EXPRESSION") frame
        val margin = 28.dp.toPx()
        val full = Size(size.width + 2 * margin, size.height + 2 * margin)
        val shader = state.shader ?: return@drawBehind
        val brush = state.brush ?: return@drawBehind
        state.bind(shader, full, margin, size, cornerRadius.toPx(), density, colors)
        translate(-margin, -margin) { drawRect(brush, size = full) }
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
    private val amp = FloatArray(8)
    private val phase = FloatArray(8)
    private val uAmp = FloatArray(8)
    private var glow = 0.16f
    private var flash = 0f
    private var ignite = 0f
    private var lastNanos = 0L
    private var seconds = 0f

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

    private companion object {
        val COLOR_UNIFORMS = arrayOf("u_c1", "u_c2", "u_c3")
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
