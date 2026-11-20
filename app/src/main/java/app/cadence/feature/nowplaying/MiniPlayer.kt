package app.cadence.feature.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.Artwork
import app.cadence.core.design.rememberHaptic
import kotlinx.coroutines.launch

/** Floating pill above the navigation bar. Tap or swipe up to expand; swipe sideways to skip. */
@Composable
fun MiniPlayer(onExpand: () -> Unit, dragModifier: Modifier, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsStateWithLifecycle()
    val info = state.current ?: return
    val clock = rememberPlaybackClock(state.isPlaying, c.player::position)
    val shift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()

    Surface(
        onClick = onExpand,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            // Con el rail lateral (horizontal) nadie consume el inset de abajo y la pildora quedaba
            // debajo de la barra de navegacion del sistema.
            .navigationBarsPadding()
            .widthIn(max = 640.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .then(dragModifier)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = shift.value
                        if (v < -120f) { c.player.next(); haptic(HapticFeedbackType.GestureEnd) }
                        if (v > 120f) { c.player.previous(); haptic(HapticFeedbackType.GestureEnd) }
                        scope.launch { shift.animateTo(0f) }
                    },
                ) { change, dx -> change.consume(); scope.launch { shift.snapTo(shift.value + dx * 0.6f) } }
            },
    ) {
        Column {
            Row(
                Modifier.padding(8.dp).graphicsLayer { translationX = shift.value; alpha = 1f - (kotlin.math.abs(shift.value) / 600f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(info.album.id, info.album.colors, Modifier.size(48.dp), RoundedCornerShape(14.dp), coverUrl = info.album.cover)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(info.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info.artist.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                FilledIconButton(
                    onClick = { c.player.togglePlay() },
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play)) }
                IconButton(onClick = { c.player.next() }) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.cd_next)) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                LinearProgressIndicator(
                    progress = { (clock.value.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    drawStopIndicator = {},
                )
            }
            Box(Modifier.height(6.dp))
        }
    }
}
