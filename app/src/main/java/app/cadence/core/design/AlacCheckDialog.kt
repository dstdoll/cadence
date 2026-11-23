package app.cadence.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.R
import app.cadence.backend.model.AlacCheckReport
import app.cadence.backend.model.AlacCheckStatus
import app.cadence.backend.model.TrackInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs an on-demand ALAC escape-frame integrity check for [tracks] and displays a compact summary
 * without adding clutter to track rows.
 */
@Composable
fun AlacCheckDialog(
    title: String,
    tracks: List<TrackInfo>,
    onDismiss: () -> Unit,
) {
    val repo = LocalContainer.current.repository
    var doneCount by remember { mutableStateOf(0) }
    var reports by remember { mutableStateOf<List<Pair<TrackInfo, AlacCheckReport>>>(emptyList()) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(tracks) {
        running = true
        doneCount = 0
        val out = ArrayList<Pair<TrackInfo, AlacCheckReport>>(tracks.size)
        for (t in tracks) {
            val report = repo.checkAlacIntegrity(t.id) ?: AlacCheckReport(AlacCheckStatus.UNAVAILABLE)
            out += t to report
            doneCount++
            reports = out.toList()
        }
        running = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1) },
        text = {
            if (running && reports.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.alac_check_running))
                }
            } else if (tracks.size == 1 && reports.size == 1) {
                SingleTrackReport(reports.first().second)
            } else {
                MultiTrackReport(reports, running, doneCount, tracks.size)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SingleTrackReport(r: AlacCheckReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(r.status)
        if (r.totalFrames > 0) {
            KeyValueRow(stringResource(R.string.alac_check_frames), "${r.totalFrames}")
            KeyValueRow(stringResource(R.string.alac_check_escape), "${r.escapeFrames}")
            if (r.missingEndFrames > 0) {
                KeyValueRow(stringResource(R.string.alac_check_missing_end), "${r.missingEndFrames}")
            }
            if (r.dirtyPaddingFrames > 0) {
                KeyValueRow(stringResource(R.string.alac_check_dirty_pad), "${r.dirtyPaddingFrames}")
            }
            if (r.bits > 0 && r.sampleRate > 0) {
                KeyValueRow(stringResource(R.string.quality_format), "${r.bits}-bit / ${r.sampleRate} Hz")
            }
            r.packDateMs?.let { ms ->
                val formatted = remember(ms) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms)) }
                KeyValueRow(stringResource(R.string.alac_check_pack_date), formatted)
            }
            if (r.software.isNotBlank()) {
                KeyValueRow(stringResource(R.string.alac_check_encoder), r.software)
            }
        }
    }
}

@Composable
private fun MultiTrackReport(
    reports: List<Pair<TrackInfo, AlacCheckReport>>,
    running: Boolean,
    done: Int,
    total: Int,
) {
    val clean = reports.count { it.second.status == AlacCheckStatus.CLEAN }
    val affected = reports.count { it.second.status == AlacCheckStatus.MISSING_END || it.second.status == AlacCheckStatus.DIRTY_PADDING }
    val unavailable = reports.count { it.second.status == AlacCheckStatus.NOT_ALAC || it.second.status == AlacCheckStatus.UNAVAILABLE }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("$done / $total", style = MaterialTheme.typography.bodyMedium)
            }
        }
        KeyValueRow(stringResource(R.string.alac_status_clean), "$clean")
        KeyValueRow(stringResource(R.string.alac_check_affected), "$affected")
        if (unavailable > 0) {
            KeyValueRow(stringResource(R.string.alac_status_unavailable), "$unavailable")
        }
        val bad = reports.filter { it.second.status == AlacCheckStatus.MISSING_END || it.second.status == AlacCheckStatus.DIRTY_PADDING }
        if (bad.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            bad.take(6).forEach { (t, r) ->
                val count = r.missingEndFrames + r.dirtyPaddingFrames
                Text("${t.track.title}: $count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusPill(status: AlacCheckStatus) {
    val (labelRes, bg, fg) = when (status) {
        AlacCheckStatus.CLEAN -> Triple(R.string.alac_status_clean, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        AlacCheckStatus.MISSING_END -> Triple(R.string.alac_status_missing_end, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        AlacCheckStatus.DIRTY_PADDING -> Triple(R.string.alac_status_dirty_pad, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        AlacCheckStatus.NOT_ALAC -> Triple(R.string.alac_status_not_alac, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        AlacCheckStatus.UNAVAILABLE -> Triple(R.string.alac_status_unavailable, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            stringResource(labelRes),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
