package app.cadence.feature.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.cadence.R
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.backend.engine.PathTemplate
import app.cadence.state.settings.AlbumDateStyle
import app.cadence.state.settings.UserSettings

private const val DEFAULT_ALBUM_FOLDER = "{artist}/{album}"
private const val DEFAULT_TRACK_FILE = "{track:02d} {title}"
private const val DEFAULT_PLAYLIST_FOLDER = "{playlist}"
private const val DEFAULT_PLAYLIST_FILE = "{track:02d} {artist} - {title}"

@Composable
internal fun DownloadsPanel(onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()

    SettingsPanel(stringResource(R.string.set_page_downloads), onBack) {
        item(key = "hub") {
            SettingsGroup {
                ChevronRow(stringResource(R.string.set_files_folders), folderDigest(s), Icons.Rounded.FolderOpen) { onOpen(SettingsPage.DownloadFiles) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ChevronRow(
                    stringResource(R.string.set_naming_dates), "${s.albumFolderTemplate}  ·  ${s.trackFileTemplate}", Icons.Rounded.TextFields,
                    info = stringResource(R.string.set_dl_templates_hint),
                ) { onOpen(SettingsPage.DownloadNaming) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ChevronRow(stringResource(R.string.set_audio_quality), audioDigest(s), Icons.Rounded.HighQuality) { onOpen(SettingsPage.DownloadAudio) }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                ChevronRow(stringResource(R.string.set_tags_artwork), tagsDigest(s), Icons.Rounded.Sell) { onOpen(SettingsPage.DownloadTags) }
            }
        }
        item(key = "auto") {
            SettingsGroup {
                ListItem(
                    content = { TitleInfo(stringResource(R.string.set_download_as_you_go), stringResource(R.string.set_dl_as_you_go_sub)) },
                    leadingContent = { Icon(Icons.Rounded.Check, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_download_as_you_go)),
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SwitchRow(
                    stringResource(R.string.set_reuse_cache),
                    stringResource(R.string.set_reuse_cache_sub),
                    s.shareDownloadCache,
                ) { v -> store.update { it.copy(shareDownloadCache = v) } }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun folderDigest(s: UserSettings): String {
    val overrides = listOfNotNull(s.albumTreeUri, s.playlistTreeUri, s.singleTreeUri).size
    val base = friendlyTree(s.downloadTreeUri) ?: "Music/Cadence"
    return if (overrides == 0) base else pluralStringResource(R.plurals.set_digest_overrides, overrides, base, overrides)
}

@Composable
private fun audioDigest(s: UserSettings): String =
    "ALAC · ${s.bitDepth}-bit · ${rateLabel(s.maxSampleRate)}"

@Composable
private fun rateLabel(rate: Int) = when (rate) {
    0 -> stringResource(R.string.set_auto_rate)
    44100 -> "44.1 kHz"
    48000 -> "48 kHz"
    96000 -> "96 kHz"
    else -> "${rate / 1000} kHz"
}

@Composable
private fun tagsDigest(s: UserSettings): String {
    val parts = ArrayList<String>()
    if (s.writeMetadata) parts += stringResource(R.string.set_digest_tags)
    if (s.embedCover) parts += stringResource(R.string.set_digest_cover, s.coverSize.take(4))
    if (s.embedLyrics) parts += stringResource(R.string.set_digest_lyrics, s.lyricsFormat.uppercase())
    return if (parts.isEmpty()) stringResource(R.string.set_digest_none) else parts.joinToString(" · ")
}

@Composable
internal fun DownloadFilesPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()

    SettingsPanel(stringResource(R.string.set_files_folders), onBack) {
        item(key = "default") {
            SettingsGroup {
                FolderRow(
                    title = stringResource(R.string.set_default_location),
                    value = s.downloadTreeUri,
                    fallback = "Music/Cadence",
                    info = stringResource(R.string.set_dl_default_hint),
                ) { uri -> store.update { it.copy(downloadTreeUri = uri) } }
            }
        }
        item(key = "albums") {
            SettingsGroup {
                FolderRow(stringResource(R.string.set_albums), s.albumTreeUri, stringResource(R.string.set_same_as_default)) { uri ->
                    store.update { it.copy(albumTreeUri = uri) }
                }
                FolderRow(stringResource(R.string.set_playlists), s.playlistTreeUri, stringResource(R.string.set_same_as_default)) { uri ->
                    store.update { it.copy(playlistTreeUri = uri) }
                }
                FolderRow(stringResource(R.string.set_singles), s.singleTreeUri, stringResource(R.string.set_same_as_default)) { uri ->
                    store.update { it.copy(singleTreeUri = uri) }
                }
            }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.set_dl_folder_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun FolderRow(title: String, value: String?, fallback: String, info: String? = null, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onPick(uri.toString())
        }
    }
    val label = friendlyTree(value) ?: fallback
    ListItem(
        content = { TitleInfo(title, info) },
        supportingContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Rounded.Folder, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker.launch(null) }) { Text(stringResource(if (value == null) R.string.set_choose else R.string.set_change)) }
                if (value != null) {
                    IconButton(onClick = { onPick(null) }) { Icon(Icons.Rounded.Close, stringResource(R.string.set_use_default)) }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = rememberHighlight(title).clickable { picker.launch(null) },
    )
}

/** `content://.../tree/primary%3AMusic%2FCadence` -> "Music/Cadence". */
@Composable
internal fun friendlyTree(uri: String?): String? {
    uri ?: return null
    val decoded = android.net.Uri.decode(uri)
    val tree = decoded.substringAfter("/tree/", decoded)
    val volume = tree.substringBefore(':', "")
    val path = tree.substringAfter(':', "")
    return when {
        path.isBlank() && volume == "primary" -> stringResource(R.string.set_internal_storage)
        path.isBlank() -> volume.ifBlank { stringResource(R.string.set_selected_folder) }
        volume == "primary" -> path
        else -> "$volume · $path"
    }
}

@Composable
internal fun DownloadNamingPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPanel(stringResource(R.string.set_naming_dates), onBack) {
        item(key = "folders") {
            SettingsGroup {
                TemplateRow(stringResource(R.string.set_album_folder), s.albumFolderTemplate, isFolder = true) { editing = "albumFolder" }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                TemplateRow(stringResource(R.string.set_track_file), s.trackFileTemplate, isFolder = false) { editing = "trackFile" }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                TemplateRow(stringResource(R.string.set_playlist_folder), s.playlistFolderTemplate, isFolder = true) { editing = "playlistFolder" }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                TemplateRow(stringResource(R.string.set_playlist_track_file), s.playlistTrackFileTemplate, isFolder = false) { editing = "playlistFile" }
            }
        }
        item(key = "date") {
            SettingsGroup {
                Label(stringResource(R.string.set_album_date_tag), "`©day` atom: the full release date, the year only, or omitted.")
                Connected(AlbumDateStyle.entries, s.albumDateTag, { stringResource(it.labelRes) }) { v ->
                    store.update { it.copy(albumDateTag = v) }
                }
            }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.set_dl_preview_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }

    when (editing) {
        "albumFolder" -> TemplateSheet(stringResource(R.string.set_album_folder), s.albumFolderTemplate, isFolder = true, isPlaylist = false, DEFAULT_ALBUM_FOLDER,
            onDismiss = { editing = null }) {
            store.update { st -> st.copy(albumFolderTemplate = it) }; editing = null
        }
        "trackFile" -> TemplateSheet(stringResource(R.string.set_track_file), s.trackFileTemplate, isFolder = false, isPlaylist = false, DEFAULT_TRACK_FILE,
            onDismiss = { editing = null }) {
            store.update { st -> st.copy(trackFileTemplate = it) }; editing = null
        }
        "playlistFolder" -> TemplateSheet(stringResource(R.string.set_playlist_folder), s.playlistFolderTemplate, isFolder = true, isPlaylist = true, DEFAULT_PLAYLIST_FOLDER,
            onDismiss = { editing = null }) {
            store.update { st -> st.copy(playlistFolderTemplate = it) }; editing = null
        }
        "playlistFile" -> TemplateSheet(stringResource(R.string.set_playlist_track_file), s.playlistTrackFileTemplate, isFolder = false, isPlaylist = true, DEFAULT_PLAYLIST_FILE,
            onDismiss = { editing = null }) {
            store.update { st -> st.copy(playlistTrackFileTemplate = it) }; editing = null
        }
    }
}

@Composable
private fun TemplateRow(title: String, value: String, isFolder: Boolean, onClick: () -> Unit) {
    ListItem(
        content = { Text(title) },
        supportingContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(if (isFolder) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.Notes, null) },
        trailingContent = { TextButton(onClick = onClick) { Text(stringResource(R.string.set_edit)) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = rememberHighlight(title).clickable(onClick = onClick),
    )
}

/** Context-correct quick-pick templates: folders never carry per-track tokens, files always do. */
@Composable
private fun presetsFor(isFolder: Boolean, isPlaylist: Boolean): List<Pair<String, String>> = when {
    isFolder && isPlaylist -> listOf(
        stringResource(R.string.set_preset_playlist) to "{playlist}",
        stringResource(R.string.set_preset_playlist_album) to "{playlist}/{album}",
    )
    isFolder -> listOf(
        stringResource(R.string.set_preset_artist_album) to "{albumartist}/{album}",
        stringResource(R.string.set_preset_year) to "{albumartist}/{album} ({year})",
        stringResource(R.string.set_preset_year_quality) to "{albumartist}/{album} ({year}) {quality}",
        stringResource(R.string.set_preset_codec) to "{albumartist}/{album} ({year}) [{codec}]",
    )
    isPlaylist -> listOf(
        stringResource(R.string.set_preset_num_artist_title) to "{track:02d} {artist} - {title}",
        stringResource(R.string.set_preset_artist_title) to "{artist} - {title}",
    )
    else -> listOf(
        stringResource(R.string.set_preset_num_title) to "{track:02d} {title}",
        stringResource(R.string.set_preset_num_title_quality) to "{track:02d} {title} {quality}",
        stringResource(R.string.set_preset_disc_track_title) to "{disc}-{track:02d} {title}",
    )
}

/** Bottom-sheet template editor with token chips and a live preview. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TemplateSheet(
    title: String,
    initial: String,
    isFolder: Boolean,
    isPlaylist: Boolean,
    default: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.set_tokens_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                minLines = 2,
                label = { Text(stringResource(if (isFolder) R.string.set_folder_template else R.string.set_file_template)) },
                shape = RoundedCornerShape(18.dp),
            )
            // Presets: context-correct full templates for this component (one tap replaces the text).
            Text(
                stringResource(R.string.set_presets), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presetsFor(isFolder, isPlaylist).forEach { (label, template) ->
                    SuggestionChip(onClick = { text = template }, label = { Text(label) })
                }
            }
            // Tokens: only the ones that make sense here - folders never offer per-track tokens,
            // and {playlist} appears only in a playlist context.
            Text(
                stringResource(R.string.set_tokens), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PathTemplate.TOKENS
                    .filter { (!isFolder || it !in PathTemplate.TRACK_ONLY_TOKENS) && (isPlaylist || it !in PathTemplate.PLAYLIST_ONLY_TOKENS) }
                    .forEach { token -> SuggestionChip(onClick = { text += token }, label = { Text(token) }) }
            }
            val preview = if (isFolder) {
                PathTemplate.folderPath(text, PathTemplate.SAMPLE)
            } else {
                PathTemplate.fileName(text, PathTemplate.SAMPLE)
            }
            val fullExample = run {
                val folder = if (isFolder) preview else PathTemplate.folderPath(DEFAULT_ALBUM_FOLDER, PathTemplate.SAMPLE)
                val file = if (isFolder) PathTemplate.fileName(DEFAULT_TRACK_FILE, PathTemplate.SAMPLE) else preview
                if (folder.isBlank()) file else "$folder/$file"
            }
            Surface(
                shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.set_preview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (preview.isBlank()) "(empty)" else preview,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (fullExample.endsWith(".m4a")) fullExample else "$fullExample.m4a",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { text = default }) { Text(stringResource(R.string.set_reset_default)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(text.trim().ifBlank { default }) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
internal fun DownloadAudioPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()

    SettingsPanel(stringResource(R.string.set_audio_quality), onBack) {
        item(key = "format") {
            SettingsGroup {
                Label(stringResource(R.string.set_format), stringResource(R.string.set_dl_format_hint))
                Connected(listOf("alac", "aac"), s.downloadCodec, { codecLabel(it) }) { v -> store.update { it.copy(downloadCodec = v) } }
                Label(
                    stringResource(R.string.set_dolby_atmos),
                    when (s.downloadAtmos) {
                        "atmos" -> stringResource(R.string.set_dl_atmos_hint_atmos)
                        "spatial" -> stringResource(R.string.set_dl_atmos_hint_spatial)
                        else -> stringResource(R.string.set_dl_atmos_hint_off)
                    },
                )
                Connected(listOf("off", "spatial", "atmos"), s.downloadAtmos, { atmosLabel(it) }) { v -> store.update { it.copy(downloadAtmos = v) } }
            }
        }
        item(key = "depth") {
            SettingsGroup {
                Label(stringResource(R.string.set_bit_depth), stringResource(R.string.set_dl_depth_hint))
                Connected(listOf(24, 16), s.bitDepth, { "$it-bit" }) { v ->
                    store.update { it.copy(bitDepth = v) }
                }
            }
        }
        item(key = "rate") {
            SettingsGroup {
                Label(stringResource(R.string.set_max_rate), stringResource(R.string.set_dl_rate_hint))
                Connected(listOf(0, 44100, 48000, 96000, 192000), s.maxSampleRate, { rateLabel(it) }) { v ->
                    store.update { it.copy(maxSampleRate = v) }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun DownloadTagsPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()

    SettingsPanel(stringResource(R.string.set_tags_artwork), onBack) {
        item(key = "tags") {
            SettingsGroup {
                SwitchRow(
                    stringResource(R.string.set_write_tags),
                    stringResource(R.string.set_write_tags_sub),
                    s.writeMetadata,
                ) { v -> store.update { it.copy(writeMetadata = v) } }
            }
        }
        item(key = "cover") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_embed_cover), null, s.embedCover) { v ->
                    store.update { it.copy(embedCover = v) }
                }
                if (s.embedCover) {
                    Label(stringResource(R.string.set_cover_size))
                    ChoiceChips(
                        listOf("600x600", "1000x1000", "1400x1400", "3000x3000", "5000x5000", "original"),
                        s.coverSize,
                    ) { v -> store.update { it.copy(coverSize = v) } }
                    Spacer(Modifier.height(8.dp))
                    Label(stringResource(R.string.set_cover_format))
                    Connected(listOf("jpg", "png"), s.coverFormat, { it.uppercase() }) { v ->
                        store.update { it.copy(coverFormat = v) }
                    }
                }
            }
        }
        item(key = "lyrics") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_embed_lyrics), null, s.embedLyrics) { v ->
                    store.update { it.copy(embedLyrics = v) }
                }
                if (s.embedLyrics) {
                    Spacer(Modifier.height(8.dp))
                    Label(stringResource(R.string.set_lyrics_format))
                    Connected(listOf("lrc", "ttml"), s.lyricsFormat, { it.uppercase() }) { v ->
                        store.update { it.copy(lyricsFormat = v) }
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

/** Flow of tonal chips; scales to any number of sizes without clipping labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.clickable { onSelect(option) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Icon(Icons.Rounded.Check, null, Modifier.padding(end = 6.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(
                        option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
