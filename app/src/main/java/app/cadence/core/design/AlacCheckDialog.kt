package app.cadence.core.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.R
import app.cadence.backend.model.AlacCheckReport
import app.cadence.backend.model.AlacCheckStatus
import app.cadence.backend.model.TrackInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatAlacReportText(
    title: String,
    storefront: String,
    reports: List<Pair<TrackInfo, AlacCheckReport>>,
    checkedAtMs: Long,
): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildString {
        appendLine("ALAC Check: $title")
        appendLine("Storefront: ${storefront.ifBlank { "us" }} | Checked: ${fmt.format(Date(checkedAtMs))}")
        for ((t, r) in reports) {
            append("- ${t.track.title} [${t.id}]: ${r.status}")
            if (r.totalFrames > 0) {
                append(" | ${r.bits}b/${r.sampleRate}Hz | frames=${r.totalFrames} esc=${r.escapeFrames}")
                if (r.missingEndFrames > 0) append(" missingEnd=${r.missingEndFrames}")
                if (r.dirtyPaddingFrames > 0) append(" dirtyPad=${r.dirtyPaddingFrames}")
                if (r.effectiveBits != null) append(" effBits=${r.effectiveBits}")
                if (r.bandwidthHz != null) append(" bw=${r.bandwidthHz}Hz")
                if (r.upsampledSuspect) append(" upsampled=true")
                if (r.software.isNotBlank()) append(" sw=${r.software}")
            }
            appendLine()
        }
    }.trimEnd()
}

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
    val container = LocalContainer.current
    val repo = container.repository
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val storefront = repo.storefront
    var analyzePcm by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var reports by remember { mutableStateOf<List<Pair<TrackInfo, AlacCheckReport>>>(emptyList()) }
    var running by remember { mutableStateOf(true) }
    var checkedAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var repairNonce by remember { mutableStateOf(0) }

    LaunchedEffect(tracks, analyzePcm, repairNonce) {
        running = true
        doneCount = 0
        reports = emptyList()
        val out = ArrayList<Pair<TrackInfo, AlacCheckReport>>(tracks.size)
        for (t in tracks) {
            val report = repo.checkAlacIntegrity(t.id, analyzePcm = analyzePcm)
                ?: AlacCheckReport(AlacCheckStatus.UNAVAILABLE)
            out += t to report
            doneCount++
            reports = out.toList()
        }
        checkedAtMs = System.currentTimeMillis()
        running = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.alac_check_analyze_pcm),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = analyzePcm, onCheckedChange = { analyzePcm = it })
                }
                if (running && reports.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.alac_check_running))
                    }
                } else if (tracks.size == 1 && reports.size == 1) {
                    SingleTrackReport(reports.first().first, reports.first().second, storefront, checkedAtMs)
                } else {
                    MultiTrackReport(reports, running, doneCount, tracks.size, storefront)
                }
            }
        },
        dismissButton = {
            if (!running && reports.isNotEmpty()) {
                val repairable = if (tracks.size == 1 && reports.size == 1) {
                    val r = reports.first().second
                    r.source == "local" && (r.status == AlacCheckStatus.MISSING_END || r.status == AlacCheckStatus.DIRTY_PADDING)
                } else false
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repairable) {
                        TextButton(
                            onClick = {
                                running = true
                                val trackId = tracks.first().id
                                scope.launch {
                                    repo.repairLocalAlac(trackId)
                                    repairNonce++
                                    Toast.makeText(context, context.getString(R.string.alac_check_repaired), Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.alac_check_repair))
                        }
                    }
                    TextButton(
                        onClick = {
                            val text = formatAlacReportText(title, storefront, reports, checkedAtMs)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ALAC Check", text))
                            Toast.makeText(context, context.getString(R.string.alac_check_copied), Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.alac_check_copy_report))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SingleTrackReport(
    track: TrackInfo,
    r: AlacCheckReport,
    storefront: String,
    checkedAtMs: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(r.status)
        KeyValueRow(stringResource(R.string.alac_check_id), track.id)
        KeyValueRow(stringResource(R.string.alac_check_region), storefront.uppercase(Locale.US))
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
            r.effectiveBits?.let { eff ->
                val effText = if (eff < r.bits) "$eff-bit (in ${r.bits}-bit)" else "$eff-bit"
                KeyValueRow(stringResource(R.string.alac_check_effective_bits), effText)
            }
            r.bandwidthHz?.let { bw ->
                val khz = String.format(Locale.US, "%.1f kHz", bw / 1000.0)
                val bwText = if (r.upsampledSuspect) "$khz (${stringResource(R.string.alac_check_upsampled)})" else khz
                KeyValueRow(stringResource(R.string.alac_check_bandwidth), bwText)
            }
            r.packDateMs?.let { ms ->
                val formatted = remember(ms) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms)) }
                KeyValueRow(stringResource(R.string.alac_check_pack_date), formatted)
            }
            if (r.software.isNotBlank()) {
                KeyValueRow(stringResource(R.string.alac_check_encoder), r.software)
            }
        }
        val checkedText = remember(checkedAtMs) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(checkedAtMs)) }
        KeyValueRow(stringResource(R.string.alac_check_time), checkedText)
    }
}

@Composable
private fun MultiTrackReport(
    reports: List<Pair<TrackInfo, AlacCheckReport>>,
    running: Boolean,
    done: Int,
    total: Int,
    storefront: String,
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
        KeyValueRow(stringResource(R.string.alac_check_region), storefront.uppercase(Locale.US))
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
