@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.cadence.core.design

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.cadence.LocalContainer
import app.cadence.core.theme.LocalMonochrome
import app.cadence.core.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cover that can move. With a [motion] video it loops that (muted); without one it animates the
 * procedural artwork itself. Falls back to the static image when motion covers are off, motion is
 * reduced, or [animate] is false. The static image always sits underneath, so there is never a blank frame.
 */
@Composable
fun CoverArt(
    key: String,
    colors: List<String>,
    motion: String?,
    animate: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    coverUrl: String? = null,
) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val moving = settings.motionCovers && !LocalReduceMotion.current
    Box(modifier.aspectRatio(1f).clip(shape)) {
        // Real cover art when the backend has one; procedural art is the base/fallback.
        Artwork(key, colors, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, coverUrl = coverUrl)
        if (!moving) return@Box
        val uri = c.repository.motionUri(motion)
        if (uri != null) VideoCover(uri, playing = animate, Modifier.fillMaxSize())
        // Don't animate procedural shapes on top of a real photo.
        else if (coverUrl == null) ProceduralCover(key, colors, running = animate, Modifier.fillMaxSize())
    }
}

/** Muted looping video on a TextureView (so the monochrome filter can apply to it). Fades in on first frame. */
@Composable
private fun VideoCover(uri: String, playing: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var ready by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onRenderedFirstFrame() { ready = true } }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(500), label = "video")
    val mono = LocalMonochrome.current
    PlayerSurface(
        player = player,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            renderEffect = if (mono) grayscaleEffect else null
        },
    )
}

/** The grayscale effect for monochrome mode; null before Android 12 (no RenderEffect), where covers stay in colour. */
private val grayscaleEffect by lazy {
    if (app.cadence.core.theme.ApiCompat.supportsRenderEffect()) {
        val m = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m)).asComposeRenderEffect()
    } else null
}

/**
 * The procedural cover, alive: the gradient slowly turns and the two shapes drift, breathe and spin.
 * One Canvas redraws per frame from a single clock; nothing recomposes.
 */
@Composable
private fun ProceduralCover(key: String, colors: List<String>, running: Boolean, modifier: Modifier) {
    val specs = remember(key) { ArtworkRenderer.specs(key) }
    val paths = remember(key) { specs.map { ArtworkRenderer.unitPath(it.shape).asComposePath() } }
    val pal = remember(colors) { ArtworkRenderer.palette(colors).map(::Color) }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) time.floatValue += (now - last) / 1e9f
            last = now
        }
    }
    val mono = LocalMonochrome.current
    Canvas(modifier.graphicsLayer { renderEffect = if (mono) grayscaleEffect else null }) {
        val t = time.floatValue
        val s = size.minDimension
        // Gradient direction orbits once every ~40 s.
        val a = (t / 40f) * 2 * PI.toFloat()
        val dx = cos(a) * s / 2
        val dy = sin(a) * s / 2
        drawRect(Brush.linearGradient(listOf(pal[0], pal[1]), Offset(s / 2 - dx, s / 2 - dy), Offset(s / 2 + dx, s / 2 + dy)))
        specs.forEachIndexed { i, spec ->
            val phase = t * (0.25f + 0.1f * i) + i * 1.7f
            val drift = s * 0.035f
            translate(s * spec.cx + sin(phase) * drift, s * spec.cy + cos(phase * 0.8f) * drift) {
                rotate(spec.rotation + t * (if (i == 0) 6f else -9f), pivot = Offset.Zero) {
                    val breathe = 1f + 0.04f * sin(phase * 1.3f)
                    scale(s * spec.scale * breathe, pivot = Offset.Zero) {
                        drawPath(paths[i], pal[spec.colorIndex].copy(alpha = spec.alpha / 255f))
                    }
                }
            }
        }
        drawRect(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.27f), Color.Transparent), Offset(s * 0.2f, s * 0.1f), s * 0.9f),
        )
    }
}
