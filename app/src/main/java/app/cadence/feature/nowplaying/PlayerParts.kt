package app.cadence.feature.nowplaying

import androidx.compose.material3.SliderState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import app.cadence.core.design.formatTime
import app.cadence.core.design.rememberHaptic
import app.cadence.core.theme.LocalReduceMotion
import app.cadence.playback.PlayerState
import app.cadence.playback.Repeat
import androidx.compose.material3.toPath
import kotlin.math.cos
import kotlin.math.sin

/**
 * One vertical gutter for every element on the player, so the cover, title, scrubber, times, controls and
 * volume all share the same left and right edges. Icon buttons are 48dp around a 24dp glyph, so rows of
 * them sit [IconInset] further out to put the *glyph* on the gutter line.
 */
val PlayerGutter = 24.dp
val IconInset = 12.dp

/** Playback position that advances every frame while playing. */
@Composable
fun rememberPlaybackClock(isPlaying: Boolean, position: () -> Long): State<Long> {
    val clock = remember { mutableLongStateOf(position()) }
    LaunchedEffect(isPlaying) {
        clock.longValue = position()
        while (isPlaying) withFrameMillis { clock.longValue = position() }
    }
    return clock
}

/** Slowly drifting color fields behind the player, drawn from the active theme (so monochrome stays gray). */
@Composable
fun PlayerBackground(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val t by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "t",
    )
    val blobs = listOf(cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer)
    // Its own layer: the drift redraws this canvas every frame, and without a layer that re-recorded
    // the whole player sheet (and everything drawn over it) every frame too.
    Canvas(modifier.fillMaxSize().graphicsLayer().background(cs.surfaceContainerLowest)) {
        val phase = if (reduce) 0f else t
        blobs.forEachIndexed { i, c ->
            val a = phase + i * 2.1f
            val center = Offset(
                size.width * (0.5f + 0.35f * cos(a + i)),
                size.height * (0.35f + 0.3f * sin(a * 0.8f + i * 1.3f)),
            )
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0f)), center, size.maxDimension * 0.55f),
                radius = size.maxDimension * 0.55f, center = center,
            )
        }
        drawRect(cs.surfaceContainerLowest.copy(alpha = 0.25f))
    }
}

/** Shape backed by a [Morph] between two Material shapes, optionally spun. */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val m = Matrix()
        m.translate(size.width / 2, size.height / 2)
        m.rotateZ(rotation)
        m.scale(size.width, size.height)
        m.translate(-0.5f, -0.5f)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/** Big play button: a calm circle when paused, a slowly turning scalloped cookie while music plays. */
@Composable
fun MorphPlayButton(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val morph = remember { Morph(MaterialShapes.Circle.normalized(), MaterialShapes.Cookie9Sided.normalized()) }
    val reduce = LocalReduceMotion.current
    val progress by animateFloatAsState(if (isPlaying) 1f else 0f, spring(0.55f, Spring.StiffnessMediumLow), label = "morph")
    val spinState = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "r")
    val spin by spinState
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(0.4f, Spring.StiffnessMedium), label = "press")
    val haptic = rememberHaptic()
    val shape = remember(progress) { MorphShape(morph, progress) }
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, indication = null) { haptic(HapticFeedbackType.ToggleOn); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // The scalloped body turns on its own layer; the icon stays upright and nothing recomposes per frame.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = if (reduce || !isPlaying) 0f else spin }
                .clip(shape)
                .background(color),
        )
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
fun TransportControls(state: PlayerState, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit) {
    val haptic = rememberHaptic()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PlayerGutter - IconInset),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(state.shuffle, { onShuffle() }) {
            Icon(Icons.Rounded.Shuffle, stringResource(R.string.cd_shuffle), tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledIconButton(
            onClick = { haptic(HapticFeedbackType.SegmentTick); onPrev() },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(64.dp),
        ) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.cd_previous), Modifier.size(32.dp)) }
        MorphPlayButton(state.isPlaying, onToggle)
        FilledIconButton(
            onClick = { haptic(HapticFeedbackType.SegmentTick); onNext() },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(64.dp),
        ) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.cd_next), Modifier.size(32.dp)) }
        IconToggleButton(state.repeat != Repeat.Off, { onRepeat() }) {
            Icon(
                if (state.repeat == Repeat.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.cd_repeat),
                tint = if (state.repeat != Repeat.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Wavy while playing, flat when paused; drag or tap anywhere on it to scrub. */
@Composable
fun SeekBar(
    clock: State<Long>, durationMs: Long, isPlaying: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
    waveStyle: app.cadence.state.settings.WaveStyle = app.cadence.state.settings.WaveStyle.Material,
    /** The cover's accent for the neon bar; the theme's tertiary colour until it is known. */
    neonAccent: Color? = null,
    center: @Composable () -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val dur = durationMs.coerceAtLeast(1)
    val fraction = { if (dragging) dragFraction else (clock.value.toFloat() / dur).coerceIn(0f, 1f) }
    val amplitude by animateFloatAsState(if (isPlaying && !dragging && !LocalReduceMotion.current) 1f else 0f, tween(450), label = "amp")
    val thickness by animateFloatAsState(if (dragging) 1.6f else 1f, spring(0.6f), label = "thick")
    val haptic = rememberHaptic()

    Column(modifier.fillMaxWidth().padding(horizontal = PlayerGutter)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp) // full touch-target height even though the line itself is thin
                .pointerInput(dur) {
                    width = size.width.toFloat()
                    detectTapGestures { o -> onSeek((o.x / size.width * dur).toLong()); haptic(HapticFeedbackType.SegmentTick) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f); haptic(HapticFeedbackType.GestureThresholdActivate) },
                        onDragEnd = { onSeek((dragFraction * dur).toLong()); dragging = false; haptic(HapticFeedbackType.GestureEnd) },
                        onDragCancel = { dragging = false },
                    ) { change, dx ->
                        change.consume()
                        dragFraction = (dragFraction + dx / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (waveStyle == app.cadence.state.settings.WaveStyle.Material) LinearWavyProgressIndicator(
                progress = fraction,
                amplitude = { amplitude },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleY = thickness },
            ) else WaveformBar(
                style = waveStyle, isPlaying = isPlaying, fraction = fraction,
                played = MaterialTheme.colorScheme.primary,
                rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                accent = neonAccent ?: MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val second by remember { derivedStateOf { clock.value / 1000 } }
            val shown = if (dragging) (dragFraction * dur).toLong() else second * 1000
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center() }
            Text("-" + formatTime(dur - shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** System media volume, kept in sync with hardware buttons. */
@Composable
fun VolumeSlider(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max }
        }
        context.registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = PlayerGutter), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        // The state follows the system volume (the hardware keys move it too); drags set it.
        val state = remember { SliderState(volume, 0, 0f..1f) }
        LaunchedEffect(volume) { if (state.value != volume) state.value = volume }
        Slider(
            state = state,
            onValueChange = {
                state.value = it
                volume = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt(), 0)
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
