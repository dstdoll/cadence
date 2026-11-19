package app.cadence.feature.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.cadence.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.cadence.LocalContainer
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.SectionHeader
import app.cadence.state.settings.ColorSource
import app.cadence.state.settings.DarkMode
import app.cadence.state.settings.LyricsAlign

/** One page in the local Settings stack. Root is the only one with the profile card. */
enum class SettingsPage {
    Root,
    Appearance,
    Playback,
    Sound,
    Streaming,
    Lyrics,
    Downloads,
    DownloadFiles,
    DownloadNaming,
    DownloadAudio,
    DownloadTags,
    Library,
    About;

    /** Where the back arrow goes. Download pages return to the Downloads hub. */
    val parent: SettingsPage
        get() = when (this) {
            Root -> Root
            DownloadFiles, DownloadNaming, DownloadAudio, DownloadTags -> Downloads
            else -> Root
        }
}

@Composable
fun SettingsScreen() {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    BackHandler(enabled = page != SettingsPage.Root) { page = page.parent }
    val highlight = androidx.compose.runtime.remember { SettingsHighlight() }
    // A target that never shows up (the option moved, or the user left the page) is dropped quietly.
    androidx.compose.runtime.LaunchedEffect(highlight.target) {
        if (highlight.target != null) {
            kotlinx.coroutines.delay(6_000)
            highlight.target = null
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsHighlight provides highlight) { SettingsPages(page) { page = it } }
}

@Composable
private fun SettingsPages(page: SettingsPage, go: (SettingsPage) -> Unit) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 14 else -it / 14 }) togetherWith
                (fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { if (forward) -it / 20 else it / 20 })
        },
        label = "settings-page",
    ) { current ->
        when (current) {
            SettingsPage.Root -> SettingsRoot { go(it) }
            SettingsPage.Appearance -> AppearancePanel { go(SettingsPage.Root) }
            SettingsPage.Playback -> PlaybackPanel { go(SettingsPage.Root) }
            SettingsPage.Sound -> SoundPanel { go(SettingsPage.Root) }
            SettingsPage.Streaming -> StreamingPanel { go(SettingsPage.Root) }
            SettingsPage.Lyrics -> LyricsPanel { go(SettingsPage.Root) }
            SettingsPage.Downloads -> DownloadsPanel(
                onBack = { go(SettingsPage.Root) },
                onOpen = { go(it) },
            )
            SettingsPage.DownloadFiles -> DownloadFilesPanel { go(SettingsPage.Downloads) }
            SettingsPage.DownloadNaming -> DownloadNamingPanel { go(SettingsPage.Downloads) }
            SettingsPage.DownloadAudio -> DownloadAudioPanel { go(SettingsPage.Downloads) }
            SettingsPage.DownloadTags -> DownloadTagsPanel { go(SettingsPage.Downloads) }
            SettingsPage.Library -> LibraryPanel { go(SettingsPage.Root) }
            SettingsPage.About -> AboutPanel { go(SettingsPage.Root) }
        }
    }
}

@Composable
private fun SettingsRoot(onOpen: (SettingsPage) -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val highlight = LocalSettingsHighlight.current
    val res = androidx.compose.ui.platform.LocalResources.current
    ListScaffold(title = stringResource(R.string.set_page_root), showBack = true, showSettings = false, listState = rememberSettingsListState()) {
        item(key = "search") { SettingsSearchField(query) { query = it } }
        if (query.isNotBlank()) {
            val hits = searchSettings(query, res::getString)
            if (hits.isEmpty()) item(key = "none") {
                Text(
                    stringResource(R.string.set_no_match, query),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            hits.forEach { e ->
                item(key = "hit-${e.page}-${e.title}") {
                    SettingsSearchResult(e) { query = ""; highlight.open(res.getString(e.target)); onOpen(e.page) }
                }
            }
            item(key = "pad-search") { Spacer(Modifier.height(32.dp)) }
            return@ListScaffold
        }
        item(key = "account") { AccountCard() }
        item(key = "session") { SessionTransferCard() }
        item(key = "mediatoken") { MediaTokenCard() }
        item(key = "prefs-h") { SectionHeader(stringResource(R.string.set_preferences)) }
        item(key = "appearance") {
            SettingsNavCard(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.set_page_appearance),
                summary = "${sourceLabel(s.colorSource)} · ${themeLabel(s.darkMode)}",
            ) { onOpen(SettingsPage.Appearance) }
        }
        item(key = "playback") {
            SettingsNavCard(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.set_page_playback),
                summary = stringResource(R.string.set_sum_playback, onOff(s.autoplay), onOff(s.dspEq.enabled)),
            ) { onOpen(SettingsPage.Playback) }
        }
        item(key = "sound") {
            SettingsNavCard(
                icon = Icons.Rounded.HighQuality,
                title = stringResource(R.string.set_page_sound),
                summary = stringResource(R.string.set_sum_sound, codecLabel(s.streamCodec), onOff(s.hiResOutput), onOff(s.bitPerfectUsb)),
            ) { onOpen(SettingsPage.Sound) }
        }
        item(key = "streaming") {
            SettingsNavCard(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.set_page_streaming),
                summary = stringResource(R.string.set_sum_streaming, preloadLabel(s.streamPreloadNextSec), cacheLabel(s.streamCacheMb)),
            ) { onOpen(SettingsPage.Streaming) }
        }
        item(key = "lyrics") {
            SettingsNavCard(
                icon = Icons.Rounded.FormatQuote,
                title = stringResource(R.string.set_page_lyrics),
                summary = stringResource(
                    R.string.set_sum_lyrics,
                    if (s.lyricsAlign == LyricsAlign.Center) stringResource(R.string.set_align_centered) else stringResource(R.string.set_align_left),
                    (s.lyricsScale * 100).toInt(), onOff(s.karaoke),
                ),
            ) { onOpen(SettingsPage.Lyrics) }
        }
        item(key = "downloads") {
            val custom = listOfNotNull(s.albumTreeUri, s.playlistTreeUri, s.singleTreeUri).size
            SettingsNavCard(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.set_page_downloads),
                summary = "${folderSummary(s.downloadTreeUri, custom)} · ALAC ${s.bitDepth}-bit",
            ) { onOpen(SettingsPage.Downloads) }
        }
        item(key = "library") {
            val rules = listOf(s.addFavoritesToLibrary, s.addPlaylistSongsToLibrary, s.listeningHistory).count { it }
            SettingsNavCard(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.set_page_library),
                summary = pluralStringResource(R.plurals.set_sum_library, rules, rules),
            ) { onOpen(SettingsPage.Library) }
        }
        item(key = "about-h") { SectionHeader(stringResource(R.string.detail_about)) }
        item(key = "about") {
            SettingsNavCard(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.set_page_about),
                summary = stringResource(R.string.set_sum_about, "0.1"),
            ) { onOpen(SettingsPage.About) }
        }
        item(key = "pad") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun onOff(v: Boolean) = stringResource(if (v) R.string.set_on else R.string.set_off)

@Composable
private fun themeLabel(m: DarkMode) = stringResource(
    when (m) {
        DarkMode.System -> R.string.set_theme_system
        DarkMode.Light -> R.string.set_theme_light
        DarkMode.Dark -> R.string.set_theme_dark
    },
)

@Composable
internal fun sourceLabel(s: ColorSource) = stringResource(
    when (s) {
        ColorSource.Wallpaper -> R.string.set_source_wallpaper
        ColorSource.Artwork -> R.string.set_source_album
        ColorSource.Custom -> R.string.set_source_custom
        ColorSource.Monochrome -> R.string.set_source_mono
    },
)

@Composable
private fun folderSummary(tree: String?, custom: Int): String = when {
    tree != null -> friendlyTree(tree) ?: stringResource(R.string.set_custom_folder)
    custom > 0 -> pluralStringResource(R.plurals.set_custom_folders, custom, custom)
    else -> "Music/Cadence"
}

/** Large, tonal navigation card used on the Settings root. */
@Composable
private fun SettingsNavCard(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sub-page scaffold: compact bar, back to the Settings root, no profile card. */
@Composable
internal fun SettingsPanel(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    ListScaffold(
        title = title, showBack = true, showSettings = false, onBack = onBack,
        listState = rememberSettingsListState(), content = content,
    )
}

/** Rounded card that groups related rows inside a sub-page. */
@Composable
internal fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** A group heading; [info], when given, sits behind a small info button instead of under it. */
@Composable
internal fun Label(text: String, info: String? = null) {
    if (info.isNullOrBlank()) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            modifier = rememberHighlight(text).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        )
    } else {
        Row(rememberHighlight(text).padding(start = 20.dp, end = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            InfoButton(text, info)
        }
    }
}

/**
 * A small info button for a setting that needs explaining: the row keeps only its name and the
 * explanation opens in a dialog, so the screens are not walls of text.
 */
@Composable
internal fun InfoButton(title: String, info: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Outlined.Info, stringResource(R.string.set_info_about, title),
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** A row's name, followed by an [InfoButton] when [info] is given. */
@Composable
internal fun TitleInfo(title: String, info: String?) {
    if (info.isNullOrBlank()) {
        Text(title)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f, fill = false))
            InfoButton(title, info)
        }
    }
}

/** Material 3 Expressive connected button group (single choice). */
@Composable
internal fun <T> Connected(options: List<T>, selected: T, label: @Composable (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, o ->
            ToggleButton(
                checked = o == selected,
                onCheckedChange = { onSelect(o) },
                enabled = enabled,
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Selection is carried by the morphing shape and fill; no checkmark eating label width.
                // Shrinks (never clips) when large system font sizes would overflow the segment.
                BasicText(
                    label(o), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.labelLarge.copy(color = LocalContentColor.current),
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

/**
 * A switch with its name. [info] (what it does) sits behind the info button; [note] stays visible, for
 * what the user has to see at a glance (a conflict with another setting).
 */
@Composable
internal fun SwitchRow(title: String, info: String?, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        content = { TitleInfo(title, info) },
        supportingContent = note?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = rememberHighlight(title).clickable { onChange(!checked) },
    )
}

/** A row that opens a page; [subtitle] is its current value, [info] an explanation behind the info button. */
@Composable
internal fun ChevronRow(title: String, subtitle: String? = null, icon: ImageVector? = null, info: String? = null, onClick: () -> Unit) {
    ListItem(
        content = { TitleInfo(title, info) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = rememberHighlight(title).clickable(onClick = onClick),
    )
}

/**
 * A settings slider: its state follows [value] (the stored setting, which can also change elsewhere)
 * and every drag is reported through [onChange].
 */
@Composable
internal fun SettingSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val state = androidx.compose.runtime.remember(steps, valueRange) { androidx.compose.material3.SliderState(value, steps, valueRange) }
    androidx.compose.runtime.LaunchedEffect(value) { if (state.value != value) state.value = value }
    androidx.compose.material3.Slider(state = state, onValueChange = { state.value = it; onChange(it) }, modifier = modifier)
}

/** Seed swatches; the selected one morphs from a circle into a scalloped cookie. */
@Composable
internal fun SeedPicker(selected: Long, onPick: (Long) -> Unit) {
    val seeds = listOf(0xFF6750A4, 0xFFB3261E, 0xFFE8A33D, 0xFF386A20, 0xFF006A6A, 0xFF0061A4, 0xFF8E4585, 0xFF5D5F5F)
    // Spoken names, since a swatch is only a colour to a screen reader otherwise.
    val names = listOf(
        R.string.set_color_purple, R.string.set_color_red, R.string.set_color_amber, R.string.set_color_green,
        R.string.set_color_teal, R.string.set_color_blue, R.string.set_color_magenta, R.string.set_color_gray,
    ).map { stringResource(it) }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        items(seeds.size) { i ->
            val seed = seeds[i]
            val on = seed == selected
            val scale by animateFloatAsState(if (on) 1.1f else 1f, spring(0.4f, Spring.StiffnessMedium), label = "seed")
            Box(
                Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(if (on) MaterialShapes.Cookie7Sided.toShape() else MaterialShapes.Circle.toShape())
                    .background(Color(seed))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(seed) })
                    .semantics { contentDescription = names[i] },
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White) }
        }
    }
}

/** A miniature of the player in the current scheme, so changes are visible instantly. */
@Composable
internal fun ThemePreview() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primary))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Golden Hour Again", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Every shadow knows my name", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(cs.primary, cs.secondary, cs.tertiary, cs.primaryContainer, cs.surfaceContainerHighest).forEach {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(it).border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp)))
                    }
                }
            }
            Box(Modifier.width(4.dp))
        }
    }
}

/**
 * Moving the account to another install: export the session to a file, or import one. The file
 * gives full access to the account, so exporting asks first. Importing restarts the app so every
 * component starts from the new session.
 */
@Composable
private fun SessionTransferCard() {
    val res = androidx.compose.ui.platform.LocalResources.current
    val c = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session by c.backend.auth.session.collectAsStateWithLifecycle()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.cadence.backend.engine.SessionTransfer.export(context, uri) }
            message = r.fold({ res.getString(R.string.set_session_saved, it.entries) }, { res.getString(R.string.set_export_failed, it.message.orEmpty()) })
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.cadence.backend.engine.SessionTransfer.import(context, uri) }
            r.onSuccess { c.player.pause(); restartApp(context) }.onFailure { message = res.getString(R.string.set_import_failed, it.message.orEmpty()) }
        }
    }
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(rememberHighlight(stringResource(R.string.set_session)).padding(16.dp)) {
            Text(stringResource(R.string.set_session), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.set_session_desc),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session != null) androidx.compose.material3.OutlinedButton(onClick = { confirmExport = true }) { Text(stringResource(R.string.set_export)) }
                androidx.compose.material3.OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.set_import)) }
            }
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmExport) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text(stringResource(R.string.set_export_title)) },
        text = { Text(stringResource(R.string.set_export_warning)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { confirmExport = false; exporter.launch("cadence-session.zip") }) { Text(stringResource(R.string.set_export)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The account token that personalises Home and the library. Without it the catalogue runs on the
 * developer token (general picks), so this says which one is in use, asks Apple for it again, and
 * takes one pasted by hand as a last resort. Shown only while signed in.
 */
@Composable
private fun MediaTokenCard() {
    val res = androidx.compose.ui.platform.LocalResources.current
    val c = LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session by c.backend.auth.session.collectAsStateWithLifecycle()
    val s = session ?: return
    var busy by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val present = s.accessToken.isNotBlank()
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(rememberHighlight(stringResource(R.string.set_token_title)).padding(16.dp)) {
            Text(stringResource(R.string.set_token_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (present) R.string.set_token_active else R.string.set_token_missing),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!present) androidx.compose.material3.OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            c.backend.auth.retryMediaToken().fold(
                                { message = res.getString(R.string.set_token_obtained) },
                                { message = res.getString(R.string.set_token_failed) },
                            )
                            busy = false
                        }
                    },
                ) { Text(stringResource(if (busy) R.string.set_token_trying else R.string.set_token_retry)) }
                androidx.compose.material3.OutlinedButton(onClick = { message = null; adding = true }) { Text(stringResource(R.string.set_token_add)) }
            }
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!working) adding = false },
            title = { Text(stringResource(R.string.set_token_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.set_token_dialog_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it; error = null },
                        label = { Text(stringResource(R.string.set_token_field)) },
                        minLines = 2, maxLines = 4, isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = text.isNotBlank() && !working,
                    onClick = {
                        working = true
                        scope.launch {
                            c.backend.auth.importSession(text.trim()).fold(
                                { adding = false; message = res.getString(R.string.set_token_obtained) },
                                { e -> error = res.getString(if (e is app.cadence.backend.api.AuthError.Network) R.string.set_token_network else R.string.set_token_rejected) },
                            )
                            working = false
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { adding = false }, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Starts the app again from scratch, so the backend and the native engine load a new session. */
internal fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

/** Who's signed in, and the way out. Root page only. */
@Composable
private fun AccountCard() {
    val c = LocalContainer.current
    val nav = app.cadence.core.design.LocalNavigator.current
    val session by c.backend.auth.session.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val s = session ?: return
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { nav.profile() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text(s.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary) }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(s.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.set_sign_out)) }
        }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.set_sign_out_title)) },
        text = { Text(stringResource(R.string.set_sign_out_desc)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirm = false
                c.player.pause()
                scope.launch { c.backend.auth.signOut() }
            }) { Text(stringResource(R.string.set_sign_out)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
