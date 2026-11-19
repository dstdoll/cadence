package app.cadence.feature.settings

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.R
import app.cadence.state.ReleaseInfo
import app.cadence.state.UpdateState
import kotlin.math.roundToInt

@Composable
fun UpdateDialog() {
    val c = LocalContainer.current
    val state by c.updates.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    when (val s = state) {
        is UpdateState.Available -> {
            val r = s.release
            AlertDialog(
                onDismissRequest = { c.updates.dismiss() },
                title = {
                    Text(
                        text = stringResource(if (r.isChannelSwitch) R.string.update_switch_title else R.string.update_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        ReleaseMetaRow(r)
                        Spacer(Modifier.height(10.dp))
                        if (r.isDowngrade) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.update_downgrade_warn),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = r.notes.ifBlank { stringResource(R.string.update_no_notes) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!r.isChannelSwitch) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = { c.updates.skipVersion(r.tag) },
                                modifier = Modifier.align(Alignment.Start),
                            ) {
                                Text(
                                    text = stringResource(R.string.update_action_skip),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (r.apkUrl != null) {
                        TextButton(onClick = { c.updates.startDownload(r) }) {
                            Text(stringResource(R.string.update_action_update), maxLines = 1, softWrap = false)
                        }
                    } else if (r.htmlUrl.isNotBlank()) {
                        TextButton(onClick = {
                            c.updates.dismiss()
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.htmlUrl)))
                        }) {
                            Text(stringResource(R.string.update_action_browser), maxLines = 1, softWrap = false)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { c.updates.dismiss() }) {
                        Text(stringResource(R.string.update_action_later), maxLines = 1, softWrap = false)
                    }
                },
            )
        }

        is UpdateState.Downloading -> {
            val r = s.release
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        text = stringResource(R.string.update_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        ReleaseMetaRow(r)
                        Spacer(Modifier.height(14.dp))
                        if (s.progress != null) {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            val pct = (s.progress * 100f).roundToInt().coerceIn(0, 100)
                            val done = Formatter.formatShortFileSize(ctx, s.downloadedBytes)
                            val total = Formatter.formatShortFileSize(ctx, s.totalBytes)
                            Text(
                                text = stringResource(R.string.update_downloading, pct, done, total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            val done = Formatter.formatShortFileSize(ctx, s.downloadedBytes)
                            Text(
                                text = stringResource(R.string.update_downloading_unknown, done),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { c.updates.cancelDownload() }) {
                        Text(stringResource(R.string.update_action_cancel), maxLines = 1, softWrap = false)
                    }
                },
            )
        }

        is UpdateState.ReadyToInstall -> {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, s) {
                val obs = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        c.updates.refreshInstallPermission(s)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            AlertDialog(
                onDismissRequest = { c.updates.dismiss() },
                title = {
                    Text(
                        text = stringResource(R.string.update_ready),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        ReleaseMetaRow(s.release)
                        if (s.needsInstallPermission) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.update_permission_warn),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { c.updates.installOrRequestPermission(s.apkFile) }) {
                        Text(stringResource(R.string.update_action_install), maxLines = 1, softWrap = false)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { c.updates.dismiss() }) {
                        Text(stringResource(R.string.update_action_later), maxLines = 1, softWrap = false)
                    }
                },
            )
        }

        else -> Unit
    }
}

@Composable
private fun ReleaseMetaRow(release: ReleaseInfo) {
    val ctx = LocalContext.current
    val channelLabel = stringResource(
        if (release.prerelease) R.string.set_update_channel_dev else R.string.set_update_channel_release,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = channelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val sizeText = if (release.apkSizeBytes > 0) {
            " • ${Formatter.formatShortFileSize(ctx, release.apkSizeBytes)}"
        } else {
            ""
        }
        Text(
            text = "${release.tag}$sizeText",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
