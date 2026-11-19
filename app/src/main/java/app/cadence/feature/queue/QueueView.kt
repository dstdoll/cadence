package app.cadence.feature.queue

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.TrackRow
import app.cadence.core.design.rememberReorderState
import app.cadence.core.design.reorderHandle
import app.cadence.core.design.reorderItem
import app.cadence.core.design.reorderOnLongPress

private val ROW_HEIGHT = 72.dp

/**
 * Up-next list. Reorder by long-pressing anywhere on a row (or grabbing the handle) when not
 * shuffling; swipe a row left to remove it.
 */
@Composable
private fun QueueToggle(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ToggleButton(
        checked = on, onCheckedChange = { onClick() }, modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(" $label", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun QueueView(modifier: Modifier = Modifier) {
    val p = LocalContainer.current.player
    val state by p.state.collectAsStateWithLifecycle()
    val c = LocalContainer.current
    val repo = c.repository
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val lib by c.library.state.collectAsStateWithLifecycle()
    val reorder = rememberReorderState<Int>(ROW_HEIGHT)
    reorder.sync(state.upNext)
    val canReorder = !state.shuffle
    val commit: (Int, Int) -> Unit = { from, to -> val base = state.index + 1; p.moveInQueue(base + from, base + to) }

    LazyColumn(modifier.fillMaxSize()) {
        item(key = "toggles") {
            // Three equal toggles, the queue's main controls.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueToggle(stringResource(R.string.queue_shuffle), Icons.Rounded.Shuffle, state.shuffle, Modifier.weight(1f)) { p.toggleShuffle() }
                QueueToggle(
                    stringResource(if (state.repeat == app.cadence.playback.Repeat.One) R.string.queue_repeat_one else R.string.queue_repeat),
                    if (state.repeat == app.cadence.playback.Repeat.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    state.repeat != app.cadence.playback.Repeat.Off, Modifier.weight(1f),
                ) { p.cycleRepeat() }
                QueueToggle(stringResource(R.string.queue_autoplay), Icons.Rounded.AllInclusive, settings.autoplay, Modifier.weight(1f)) { p.setAutoplay(!settings.autoplay) }
            }
        }
        val history = lib.recentTracks.filter { it != state.current?.id }.take(8).mapNotNull(repo::track)
        if (history.isNotEmpty()) {
            item(key = "history-h") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.queue_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { lib.let { c.library.update { it.clearHistory() } } }) { Text(stringResource(R.string.action_clear)) }
                }
            }
            items(history, key = { "h-${it.id}" }) { t ->
                TrackRow(
                    t, onClick = { p.playNext(t); p.next() },
                    modifier = Modifier.padding(horizontal = 8.dp).graphicsLayer { alpha = 0.72f },
                )
            }
        }
        item(key = "upnext-h") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (reorder.order.isNotEmpty()) TextButton(onClick = { p.clearUpNext() }) { Text(stringResource(R.string.action_clear)) }
            }
        }
        if (reorder.order.isEmpty()) item(key = "empty") {
            Text(
                if (settings.autoplay) stringResource(R.string.queue_empty_autoplay) else stringResource(R.string.queue_empty_default),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else if (canReorder) item(key = "hint") {
            Text(
                stringResource(R.string.queue_reorder_hint), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        itemsIndexed(reorder.order, key = { _, qi -> "q$qi-${state.queue.getOrNull(qi)?.id}" }) { pos, qi ->
            val info = state.queue.getOrNull(qi) ?: return@itemsIndexed
            val lifted = reorder.isDragging(pos)
            if (state.autoplayFrom >= 0 && qi == state.autoplayFrom && !lifted) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AllInclusive, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("  Autoplay \u00b7 similar to what's playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            val dismiss = rememberSwipeToDismissBoxState()
            if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) LaunchedEffect(Unit) { p.removeFromQueue(qi) }
            SwipeToDismissBox(
                state = dismiss,
                enableDismissFromStartToEnd = false,
                gesturesEnabled = reorder.dragging < 0,
                backgroundContent = {
                    if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) { Icon(Icons.Rounded.Delete, stringResource(R.string.cd_remove), tint = MaterialTheme.colorScheme.onErrorContainer) }
                },
                modifier = Modifier.height(ROW_HEIGHT).reorderItem(reorder, pos, Modifier.animateItem()),
            ) {
                TrackRow(
                    info,
                    onClick = { p.skipTo(qi) },
                    // Opaque only while lifted or swiped, so the player backdrop shows through at rest.
                    modifier = Modifier
                        .reorderOnLongPress(reorder, qi, enabled = canReorder, onMove = commit)
                        .padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp))
                        .background(
                            if (lifted || dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                        ),
                    trailing = if (!canReorder) null else ({
                        // The whole 48dp box is the handle, not just the glyph.
                        Box(Modifier.size(48.dp).reorderHandle(reorder, qi, commit), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DragHandle, stringResource(R.string.cd_reorder))
                        }
                    }),
                )
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}
