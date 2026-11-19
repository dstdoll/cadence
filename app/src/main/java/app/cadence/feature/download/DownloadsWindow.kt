package app.cadence.feature.download

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.cadence.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.backend.engine.DownloadEngine
import app.cadence.core.design.Artwork
import app.cadence.core.design.DownloadStatusIcon
import app.cadence.core.design.combinedProgress
import kotlin.math.roundToInt

/**
 * The downloads window: a sequential queue. One track at a time runs its encrypted fetch then its
 * decrypt (shown as a single combined bar); the next queued track (#2, #3...) starts when it finishes.
 * A sheet, so the feed stays untouched while jobs run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsWindow(jobs: List<DownloadEngine.Job>, onDismiss: () -> Unit) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val ordered = DownloadEngine.ordered(jobs)
    val inFlight = ordered.filter { it.phase == "downloading" || it.phase == "decrypting" }
    val queued = ordered.filter { it.phase == "queued" }
    val done = ordered.count { it.phase == "done" }
    val failed = ordered.count { it.phase == "error" }
    val total = ordered.size
    val overall = if (inFlight.isNotEmpty()) inFlight.map { it.combinedProgress() }.average().toFloat() else 0f

    // Sequential position, counting the one in flight as #1.
    val positions = remember(ordered) {
        buildMap { var n = 1; inFlight.forEach { put(it.trackId, n++) }; queued.forEach { put(it.trackId, n++) } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cd_downloads), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            inFlight.isNotEmpty() && queued.isNotEmpty() -> stringResource(R.string.downloads_now_queue, queued.size)
                            inFlight.isNotEmpty() -> stringResource(R.string.downloads_in_progress)
                            queued.isNotEmpty() -> stringResource(R.string.downloads_queued_starting, queued.size)
                            failed > 0 -> stringResource(R.string.downloads_done_failed, done, failed)
                            total > 0 -> pluralStringResource(R.plurals.downloads_ready_offline, done, done)
                            else -> stringResource(R.string.downloads_empty_sub)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed > 0 && inFlight.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ordered.any { !it.active }) {
                    TextButton(onClick = DownloadEngine::clearFinished) { Text(stringResource(R.string.action_clear)) }
                }
            }

            // Overall progress + count while anything is in flight.
            if (inFlight.isNotEmpty() || queued.isNotEmpty()) {
                val animated by animateFloatAsState(
                    if (total == 0) 0f else (done + overall) / total.coerceAtLeast(1),
                    tween(400), label = "overall",
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(
                        "$done / $total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (ordered.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.CloudDownload, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.downloads_none), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.downloads_none_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(ordered, key = { it.trackId }) { job -> JobRow(job, positions[job.trackId]) }
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: DownloadEngine.Job, position: Int?) {
    val cs = MaterialTheme.colorScheme
    val repo = LocalContainer.current.repository
    // Real cover for the track, looked up through the public catalog (frontend data only).
    val track = remember(job.trackId) { repo.track(job.trackId) }
    val active = job.phase == "downloading" || job.phase == "decrypting"
    val failed = job.phase == "error"

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Thumbnail with a dim scrim + position number while queued.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (track != null) {
                Artwork(track.album.id, track.album.colors, Modifier.size(48.dp), RoundedCornerShape(10.dp), coverUrl = track.album.cover)
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHighest))
            }
            if (job.phase == "queued") {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.scrim.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Text("$position", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else if (failed) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.scrim.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp), tint = cs.onError)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (failed && job.message.isNotBlank()) job.message else job.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) cs.error else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                val animated by animateFloatAsState(job.combinedProgress(), tween(300), label = "bar")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest,
                )
            }
        }

        // Trailing: a compact state label, stacked with the live ring for the active row.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (job.phase) {
                "downloading" -> {
                    DownloadStatusIcon(listOf(job), job.title, Modifier.size(22.dp))
                    Text("${(job.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
                "decrypting" -> {
                    DownloadStatusIcon(listOf(job), job.title, Modifier.size(22.dp))
                    Text(stringResource(R.string.downloads_decrypting), style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
                "done" -> Icon(Icons.Rounded.DownloadDone, stringResource(R.string.cd_downloaded), Modifier.size(24.dp), tint = cs.primary)
                "error" -> Text(stringResource(R.string.downloads_retry_pending), style = MaterialTheme.typography.labelSmall, color = cs.error)
                else -> Text(if ((position ?: 2) > 1) stringResource(R.string.queue_up_next) else stringResource(R.string.downloads_queued), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
