package app.cadence.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.LogExport
import app.cadence.backend.engine.stream.StreamPlayback
import app.cadence.core.design.LocalNavigator
import app.cadence.state.settings.ColorSource
import app.cadence.state.settings.ContrastLevel
import app.cadence.state.settings.DarkMode
import app.cadence.state.settings.LyricsAlign
import app.cadence.state.settings.UserSettings
import app.cadence.state.settings.WaveStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppearancePanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()
    fun set(f: (UserSettings) -> UserSettings) = store.update(f)

    SettingsPanel(stringResource(R.string.set_page_appearance), onBack) {
        item(key = "preview") { ThemePreview() }
        item(key = "source") {
            SettingsGroup {
                Label(
                    stringResource(R.string.set_color_source),
                    when (s.colorSource) {
                        ColorSource.Wallpaper -> stringResource(R.string.set_source_hint_wallpaper)
                        ColorSource.Artwork -> stringResource(R.string.set_source_hint_album)
                        ColorSource.Custom -> null // the seed picker right below says it
                        ColorSource.Monochrome -> stringResource(R.string.set_source_hint_mono)
                    },
                )
                Connected(
                    ColorSource.entries, s.colorSource,
                    { sourceLabel(it) },
                ) { v -> set { it.copy(colorSource = v) } }
            }
        }
        if (s.colorSource == ColorSource.Custom || s.colorSource == ColorSource.Artwork) item(key = "seeds") {
            SettingsGroup {
                Label(stringResource(R.string.set_seed_color))
                SeedPicker(s.customSeed) { v -> set { it.copy(customSeed = v) } }
            }
        }
        item(key = "theme") {
            SettingsGroup {
                Label(stringResource(R.string.set_theme))
                Connected(DarkMode.entries, s.darkMode, { themeOption(it) }) { v -> set { it.copy(darkMode = v) } }
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.set_contrast), stringResource(R.string.set_contrast_hint))
                Connected(
                    ContrastLevel.entries, s.contrast, { contrastOption(it) },
                    enabled = s.colorSource != ColorSource.Wallpaper,
                ) { v -> set { it.copy(contrast = v) } }
            }
        }
        item(key = "language") {
            SettingsGroup {
                Label(stringResource(R.string.settings_language))
                val labels = mapOf(
                    LanguageOption.System to stringResource(R.string.language_system),
                    LanguageOption.English to "English",
                    LanguageOption.Spanish to "Español",
                )
                Connected(LanguageOption.entries, currentLanguageOption(), { labels.getValue(it) }) { applyLanguage(it) }
            }
        }
        item(key = "look") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_pure_black), stringResource(R.string.set_pure_black_sub), s.pureBlack) { v -> set { it.copy(pureBlack = v) } }
                SwitchRow(stringResource(R.string.set_grayscale_artwork), null, s.grayscaleArtwork) { v -> set { it.copy(grayscaleArtwork = v) } }
                SwitchRow(stringResource(R.string.set_motion_covers), stringResource(R.string.set_motion_covers_sub), s.motionCovers) { v -> set { it.copy(motionCovers = v) } }
                SwitchRow(stringResource(R.string.set_show_quality), stringResource(R.string.set_show_quality_sub), s.showQuality) { v -> set { it.copy(showQuality = v) } }
                SwitchRow(stringResource(R.string.set_reduce_motion), stringResource(R.string.set_reduce_motion_sub), s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
            }
        }
        item(key = "wave") {
            SettingsGroup {
                Label(
                    stringResource(R.string.set_seek_bar),
                    when (s.waveStyle) {
                        WaveStyle.Material -> stringResource(R.string.set_wave_hint_material)
                        WaveStyle.Bars -> stringResource(R.string.set_wave_hint_bars)
                        WaveStyle.Mirror -> stringResource(R.string.set_wave_hint_mirror)
                        WaveStyle.Line -> stringResource(R.string.set_wave_hint_line)
                        WaveStyle.Neon -> stringResource(R.string.set_wave_hint_neon)
                    },
                )
                Connected(WaveStyle.entries, s.waveStyle, { stringResource(it.labelRes) }) { v -> set { it.copy(waveStyle = v) } }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun PlaybackPanel(onBack: () -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    fun set(f: (UserSettings) -> UserSettings) = c.settings.update(f)

    SettingsPanel(stringResource(R.string.set_page_playback), onBack) {
        item(key = "behaviour") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_autoplay), stringResource(R.string.set_autoplay_sub), s.autoplay) { v -> c.player.setAutoplay(v) }
                SwitchRow(
                    stringResource(R.string.set_skip_silence), null, s.skipSilence,
                    note = if (s.hiResOutput) stringResource(R.string.set_skip_silence_sub_hires) else null,
                ) { v -> set { it.copy(skipSilence = v) } }
                SwitchRow(stringResource(R.string.set_resume), null, s.resumeOnLaunch) { v -> set { it.copy(resumeOnLaunch = v) } }
                SwitchRow(stringResource(R.string.set_haptics), null, s.haptics) { v -> set { it.copy(haptics = v) } }
            }
        }
        item(key = "eq") {
            SettingsGroup {
                ListItem(
                    content = { Text(stringResource(R.string.set_equalizer)) },
                    supportingContent = { Text(onOffState(s.dspEq.enabled)) },
                    leadingContent = { Icon(Icons.Rounded.Equalizer, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_equalizer)).clickable { nav.equalizer() },
                )
                ListItem(
                    content = { Text(stringResource(R.string.set_tone_controls)) },
                    supportingContent = { Text(onOffState(s.dspTone.enabled)) },
                    leadingContent = { Icon(Icons.Rounded.Tune, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_tone_controls)).clickable { nav.toneControls() },
                )
                ListItem(
                    content = { Text(stringResource(R.string.set_replay_gain)) },
                    supportingContent = { Text(onOffState(s.dspReplayGain.enabled)) },
                    leadingContent = { Icon(Icons.Rounded.GraphicEq, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_replay_gain)).clickable { nav.replayGain() },
                )
                ListItem(
                    content = { Text(stringResource(R.string.set_balance_width)) },
                    supportingContent = { Text(onOffState(s.dspChannel.enabled)) },
                    leadingContent = { Icon(Icons.Rounded.SurroundSound, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_balance_width)).clickable { nav.balance() },
                )
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun SoundPanel(onBack: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    fun set(f: (UserSettings) -> UserSettings) = c.settings.update(f)

    SettingsPanel(stringResource(R.string.set_page_sound), onBack) {
        item(key = "quality") {
            SettingsGroup {
                Label(stringResource(R.string.set_streaming_quality), stringResource(R.string.set_streaming_quality_hint))
                Connected(listOf("alac", "aac"), s.streamCodec, { codecLabel(it) }) { v -> set { it.copy(streamCodec = v) } }
                Label(
                    stringResource(R.string.set_dolby_atmos),
                    when (s.streamAtmos) {
                        "atmos" -> stringResource(R.string.set_atmos_hint_atmos)
                        "spatial" -> stringResource(R.string.set_atmos_hint_spatial)
                        else -> stringResource(R.string.set_atmos_hint_off)
                    },
                )
                Connected(listOf("off", "spatial", "atmos"), s.streamAtmos, { atmosLabel(it) }) { v -> set { it.copy(streamAtmos = v) } }
                SwitchRow(
                    stringResource(R.string.set_hires_output), stringResource(R.string.set_hires_sub), s.hiResOutput,
                    note = if (s.skipSilence) stringResource(R.string.set_hires_sub_skip) else null,
                ) { v -> set { it.copy(hiResOutput = v) } }
                SwitchRow(
                    stringResource(R.string.set_bit_perfect),
                    stringResource(R.string.set_bit_perfect_sub),
                    s.bitPerfectUsb,
                ) { v -> set { it.copy(bitPerfectUsb = v) } }
            }
        }
        item(key = "dither") {
            SettingsGroup {
                ChevronRow(
                    stringResource(R.string.set_dither),
                    if (s.dspDither.enabled) stringResource(s.dspDither.type.labelRes) else stringResource(R.string.set_dither_off_sub),
                    Icons.Rounded.GraphicEq,
                ) { nav.dither() }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun StreamingPanel(onBack: () -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    fun set(f: (UserSettings) -> UserSettings) = c.settings.update(f)

    SettingsPanel(stringResource(R.string.set_page_streaming), onBack) {
        item(key = "window") {
            SettingsGroup {
                Label(stringResource(R.string.set_preload_next), stringResource(R.string.set_preload_hint))
                Connected(listOf(0, 15, 30, 60, -1), s.streamPreloadNextSec, { preloadLabel(it) }) { v -> set { it.copy(streamPreloadNextSec = v) } }
                Label(stringResource(R.string.set_keep_previous), stringResource(R.string.set_keep_previous_hint))
                Connected(listOf(0, 15, 30), s.streamPreloadPreviousSec, { preloadLabel(it) }) { v -> set { it.copy(streamPreloadPreviousSec = v) } }
                Label(stringResource(R.string.set_songs_ahead), stringResource(R.string.set_window_hint))
                Connected(listOf(0, 100, 250, 500, 1024), s.streamAheadMb, { windowLabel(it) }) { v -> set { it.copy(streamAheadMb = v) } }
                Label(stringResource(R.string.set_songs_behind))
                Connected(listOf(0, 100, 250, 500, 1024), s.streamBehindMb, { windowLabel(it) }) { v -> set { it.copy(streamBehindMb = v) } }
                SwitchRow(
                    stringResource(R.string.set_fill_mobile),
                    stringResource(R.string.set_fill_mobile_sub),
                    s.streamWindowOnMobile,
                ) { v -> set { it.copy(streamWindowOnMobile = v) } }
                Label(stringResource(R.string.set_streaming_cache), stringResource(R.string.set_cache_hint))
                Connected(listOf(256, 512, 1024, 2048, 4096), s.streamCacheMb, { cacheLabel(it) }) { v -> set { it.copy(streamCacheMb = v) } }
                StreamCacheRow()
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun codecLabel(codec: String) = if (codec == "aac") "AAC 256 kbps" else stringResource(R.string.set_lossless)

@Composable
internal fun atmosLabel(mode: String) = stringResource(
    when (mode) {
        "atmos" -> R.string.set_dolby_atmos
        "spatial" -> R.string.set_atmos_spatial
        else -> R.string.set_off_short
    },
)

@Composable
internal fun preloadLabel(seconds: Int) = when {
    seconds == 0 -> stringResource(R.string.set_off_short)
    seconds < 0 -> stringResource(R.string.set_whole)
    else -> "$seconds s"
}

@Composable
private fun windowLabel(megabytes: Int) = if (megabytes == 0) stringResource(R.string.set_off_short) else cacheLabel(megabytes)

/** "On" / "Off" under a row that opens a sub-screen. */
@Composable
private fun onOffState(on: Boolean) = stringResource(if (on) R.string.set_state_on else R.string.set_state_off)

@Composable
private fun themeOption(m: DarkMode) = stringResource(
    when (m) {
        DarkMode.System -> R.string.set_theme_system
        DarkMode.Light -> R.string.set_theme_light
        DarkMode.Dark -> R.string.set_theme_dark
    },
)

@Composable
private fun contrastOption(c: ContrastLevel) = stringResource(
    when (c) {
        ContrastLevel.Standard -> R.string.set_contrast_standard
        ContrastLevel.Medium -> R.string.set_contrast_medium
        ContrastLevel.High -> R.string.set_contrast_high
    },
)

internal fun cacheLabel(megabytes: Int) = if (megabytes >= 1024) "${megabytes / 1024} GB" else "$megabytes MB"

/** Shows how much decrypted audio is cached and clears it on tap. */
@Composable
private fun StreamCacheRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { StreamPlayback.cache(context).sizeOnDisk() } }
    ChevronRow(stringResource(R.string.set_clear_cache), subtitle = bytes?.let { stringResource(R.string.set_cache_in_use, it / (1024 * 1024)) } ?: stringResource(R.string.set_measuring)) {
        scope.launch {
            withContext(Dispatchers.IO) { StreamPlayback.cache(context).clear() }
            bytes = 0
        }
    }
}

@Composable
internal fun LyricsPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val s by store.state.collectAsStateWithLifecycle()
    fun set(f: (UserSettings) -> UserSettings) = store.update(f)

    SettingsPanel(stringResource(R.string.set_page_lyrics), onBack) {
        item(key = "layout") {
            SettingsGroup {
                Label("${stringResource(R.string.set_text_size)}  ·  ${(s.lyricsScale * 100).toInt()}%")
                SettingSlider(s.lyricsScale, 0.75f..1.5f, steps = 5, modifier = Modifier.padding(horizontal = 20.dp)) { v -> set { it.copy(lyricsScale = v) } }
                Label(stringResource(R.string.set_alignment))
                Connected(LyricsAlign.entries, s.lyricsAlign, { stringResource(if (it == LyricsAlign.Center) R.string.set_align_center else R.string.set_align_start) }) { v -> set { it.copy(lyricsAlign = v) } }
                Label("${stringResource(R.string.set_active_line)}  ·  ${stringResource(R.string.set_from_top, (s.lyricsAnchor * 100).toInt())}")
                SettingSlider(s.lyricsAnchor, 0.15f..0.5f, modifier = Modifier.padding(horizontal = 20.dp)) { v -> set { it.copy(lyricsAnchor = v) } }
            }
        }
        item(key = "effects") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_word_highlight), stringResource(R.string.set_word_highlight_sub), s.karaoke) { v -> set { it.copy(karaoke = v) } }
                SwitchRow(stringResource(R.string.set_depth_blur), stringResource(R.string.set_depth_blur_sub), s.lyricsBlur) { v -> set { it.copy(lyricsBlur = v) } }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun LibraryPanel(onBack: () -> Unit) {
    val store = LocalContainer.current.settings
    val nav = LocalNavigator.current
    val s by store.state.collectAsStateWithLifecycle()
    fun set(f: (UserSettings) -> UserSettings) = store.update(f)

    SettingsPanel(stringResource(R.string.set_page_library), onBack) {
        item(key = "rules") {
            SettingsGroup {
                SwitchRow(stringResource(R.string.set_add_favorites), null, s.addFavoritesToLibrary) { v -> set { it.copy(addFavoritesToLibrary = v) } }
                SwitchRow(stringResource(R.string.set_add_playlist_songs), null, s.addPlaylistSongsToLibrary) { v -> set { it.copy(addPlaylistSongsToLibrary = v) } }
                SwitchRow(stringResource(R.string.set_listening_history), stringResource(R.string.set_listening_history_sub), s.listeningHistory) { v -> set { it.copy(listeningHistory = v) } }
            }
        }
        item(key = "edit") {
            SettingsGroup {
                ChevronRow(stringResource(R.string.set_edit_categories), icon = Icons.Rounded.LibraryMusic) { nav.editLibrary() }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun AboutPanel(onBack: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    var diagnostics by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    fun exportAndShare(share: Boolean) {
        if (exporting) return
        exporting = true
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LogExport.export(ctx)
            }
            exporting = false
            if (result == null) {
                android.widget.Toast.makeText(ctx, res.getString(R.string.set_logs_failed), android.widget.Toast.LENGTH_LONG).show()
            } else if (share) {
                LogExport.share(ctx, result)
            } else {
                android.widget.Toast.makeText(ctx, res.getString(R.string.set_logs_saved, result.displayPath), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    SettingsPanel(stringResource(R.string.detail_about), onBack) {
        item(key = "card") {
            Surface(
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Cadence", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.set_about_desc),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.set_about_version, "0.1"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item(key = "diag") {
            SettingsGroup {
                ChevronRow(stringResource(R.string.set_diagnostics), stringResource(R.string.set_diag_sub, "files/logs/cadence.log"), Icons.Rounded.BugReport) { diagnostics = true }
                ChevronRow(stringResource(R.string.set_export_logs), stringResource(R.string.set_export_logs_sub, "Download/Cadence"), Icons.Rounded.Download) { exportAndShare(false) }
                ChevronRow(stringResource(R.string.set_share_logs), icon = Icons.Rounded.Share) { exportAndShare(true) }
                androidx.compose.material3.ListItem(
                    content = { Text(stringResource(R.string.set_licenses)) },
                    supportingContent = { Text("Roboto Flex (SIL OFL 1.1)") },
                    leadingContent = { Icon(Icons.Rounded.Description, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rememberHighlight(stringResource(R.string.set_licenses)),
                )
            }
        }
        item(key = "pad") { Spacer(Modifier.height(28.dp)) }
    }
}
