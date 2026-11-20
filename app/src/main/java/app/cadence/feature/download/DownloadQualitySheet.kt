package app.cadence.feature.download

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.R
import app.cadence.backend.engine.media.QualityOption
import app.cadence.backend.engine.media.RenditionSpec
import app.cadence.core.design.qualityHint
import app.cadence.core.design.qualityLabel
import app.cadence.core.design.qualityLabelForSpec
import java.text.DateFormat
import java.util.Date

/** The sheet every download goes through (see [DownloadRequests]); nothing is shown when no download is being requested. */
@Composable
fun DownloadRequestHost() {
    val request by DownloadRequests.state.collectAsStateWithLifecycle()
    val r = request ?: return
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = DownloadRequests::cancel, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.dq_title), style = MaterialTheme.typography.titleLarge)
            Text(subtitle(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            when (r) {
                is DownloadRequest.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.dq_checking))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = DownloadRequests::cancel) { Text(stringResource(R.string.dq_cancel)) }
                }
                is DownloadRequest.Choose -> Chooser(r)
                is DownloadRequest.Confirm -> {
                    ExistingNotice(summarizeExisting(r.items, r.existing))
                    Buttons(download = R.string.dq_download_again)
                }
            }
        }
    }
}

@Composable
private fun subtitle(r: DownloadRequest): String =
    if (r.items.size == 1) listOf(r.items[0].title, r.items[0].artist).filter { it.isNotBlank() }.joinToString(" · ")
    else pluralStringResource(R.plurals.dq_songs, r.items.size, r.items.size)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chooser(r: DownloadRequest.Choose) {
    val ctx = LocalContext.current
    val several = r.items.size > 1
    val existing = summarizeExisting(r.items, r.existing)
    if (r.probe.options.isEmpty()) {
        Text(stringResource(R.string.dq_unchecked_all), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
    } else {
        var open by remember { mutableStateOf(false) }
        val selected = r.option
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = selected?.let { optionTitle(it) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.dq_field_label)) },
                supportingText = selected?.let { o -> optionDetail(o, several, ctx)?.let { d -> { Text(d) } } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposedDropdownSize()) {
                r.probe.options.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = { Column { Text(optionTitle(o)); optionDetail(o, several, ctx)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
                        onClick = { DownloadRequests.select(i); open = false },
                    )
                }
            }
        }
        if (r.probe.unchecked > 0) {
            Text(pluralStringResource(R.plurals.dq_unchecked_some, r.probe.unchecked, r.probe.unchecked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(r.makeDefault, role = Role.Checkbox, onValueChange = DownloadRequests::setMakeDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(r.makeDefault, onCheckedChange = null)
            Text(stringResource(R.string.dq_make_default), modifier = Modifier.padding(start = 12.dp))
        }
    }
    if (existing.songs > 0) ExistingNotice(existing)
    Buttons(download = if (existing.songs > 0) R.string.dq_download_again else R.string.dq_download)
    Text(stringResource(R.string.dq_change_in_settings), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Buttons(download: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = DownloadRequests::cancel) { Text(stringResource(R.string.dq_cancel)) }
        Spacer(Modifier.size(8.dp))
        Button(onClick = DownloadRequests::confirm) { Text(stringResource(download)) }
    }
}

/** "Already downloaded as Lossless · 24-bit / 44.1 kHz · 18 Nov 2026. Downloading again adds a second file." */
@Composable
private fun ExistingNotice(e: ExistingSummary) {
    if (e.songs == 0) return
    val text = if (e.total == 1) {
        val named = e.specs.filter { it.isNotBlank() }
        if (named.isEmpty()) stringResource(R.string.dq_already_unknown)
        else stringResource(R.string.dq_already_one, named.map { qualityLabelForSpec(it) }.joinToString(", "), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.newest?.atMs ?: 0L)))
    } else pluralStringResource(R.plurals.dq_already_many, e.songs, e.songs, e.total)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun optionTitle(o: QualityOption): String = when (o.kind) {
    "atmos" -> stringResource(R.string.dq_atmos, o.atmosKbps)
    "spatial" -> stringResource(R.string.dq_spatial)
    else -> qualityLabel(o.spec ?: RenditionSpec.BEST)
}

/** The second line of a row: the estimated size, how many songs have it (several songs), and any caveat. */
@Composable
private fun optionDetail(o: QualityOption, several: Boolean, ctx: android.content.Context): String? {
    val parts = ArrayList<String>()
    if (o.bytes > 0) parts += stringResource(R.string.dq_size, Formatter.formatShortFileSize(ctx, o.bytes))
    if (several && !o.highestAvailable && o.fallbackTracks > 0) parts += stringResource(R.string.dq_coverage, o.tracksWith, o.tracks)
    // "Highest available" says what it will do: the best lossless any song reaches, and on how many songs.
    if (o.highestAvailable && o.topSpec != null) parts += stringResource(R.string.dq_highest_top, qualityLabel(o.topSpec!!), o.topTracks, o.tracks)
    when (o.kind) {
        "atmos" -> parts += stringResource(R.string.dq_atmos_hint)
        "spatial" -> parts += stringResource(R.string.dq_spatial_hint)
        // The generic "best lossless a song has" hint is dropped when the row already says exactly what it reaches.
        else -> if (o.topSpec == null) o.spec?.let { qualityHint(it) }?.let { parts += it }
    }
    return parts.joinToString(" · ").ifBlank { null }
}
