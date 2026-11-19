package app.cadence.core.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.cadence.backend.engine.DownloadEngine

/**
 * One combined 0..1 progress for a job across both phases, so the UI can show a single ring:
 * the download fills the first half, the decrypt the second.
 */
fun DownloadEngine.Job.combinedProgress(): Float = when (phase) {
    "downloading" -> fraction.coerceIn(0f, 1f) * 0.5f
    "decrypting" -> 0.5f + fraction.coerceIn(0f, 1f) * 0.5f
    "done" -> 1f
    else -> 0f
}

private enum class DlState { Idle, Queued, Active, Done }

/**
 * The download affordance used everywhere. States, in Material 3 Expressive style:
 *  - idle: a plain download glyph
 *  - queued: a dim, full outline ring (waiting its turn in the sequential queue)
 *  - active: a ring that fills with real progress, arrow inside
 *  - done: a check that springs in
 * [jobs] can be one track or every job of an album/playlist, where the ring shows their mean progress.
 */
@Composable
fun DownloadStatusIcon(
    jobs: List<DownloadEngine.Job>,
    contentDescription: String,
    modifier: Modifier = Modifier.size(24.dp),
    completed: Boolean = jobs.isNotEmpty() && jobs.all { it.phase == "done" },
) {
    val active = jobs.filter { it.active }
    val inFlight = active.filter { it.phase == "downloading" || it.phase == "decrypting" }
    val state = when {
        completed -> DlState.Done
        inFlight.isNotEmpty() -> DlState.Active
        active.isNotEmpty() -> DlState.Queued
        else -> DlState.Idle
    }
    val target = if (inFlight.isNotEmpty()) inFlight.map { it.combinedProgress() }.average().toFloat() else 0f
    val progress by animateFloatAsState(target, tween(400), label = "dlProgress")

    AnimatedContent(
        targetState = state,
        transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
        label = "dlIcon",
    ) { s ->
        when (s) {
            DlState.Idle -> Icon(Icons.Rounded.Download, contentDescription, modifier)
            DlState.Done -> Icon(Icons.Rounded.Check, contentDescription, modifier, tint = MaterialTheme.colorScheme.primary)
            DlState.Queued, DlState.Active -> {
                val ringColor = MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                Box(modifier, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        val inset = stroke.width / 2
                        val arc = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
                        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                        drawArc(trackColor, 0f, 360f, false, topLeft, arc, style = stroke)
                        if (s == DlState.Active) {
                            drawArc(ringColor, -90f, 360f * progress.coerceIn(0.02f, 1f), false, topLeft, arc, style = stroke)
                        }
                    }
                    Icon(
                        Icons.Rounded.ArrowDownward, null,
                        Modifier.size(12.dp),
                        tint = if (s == DlState.Active) ringColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
