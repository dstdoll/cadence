package app.cadence.feature.settings

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/**
 * One option in Settings, with the page that holds it and other words people may search for. [via] is
 * the row on that page that leads to the option when it lives one screen further (a sub-screen or card).
 */
internal data class SettingEntry(
    @StringRes val title: Int,
    val page: SettingsPage,
    val keywords: String = "",
    @StringRes val via: Int? = null,
) {
    /** The row a search result highlights. */
    @get:StringRes
    val target: Int get() = via ?: title
}

/**
 * Every option in Settings. SettingsIndexTest reads the settings panels and fails when an option is
 * missing here, so a new option is always findable from the search box.
 */
internal val SETTINGS_INDEX: List<SettingEntry> = listOf(
    SettingEntry(R.string.set_sign_out, SettingsPage.Root, "account log out logout"),
    SettingEntry(R.string.set_token_title, SettingsPage.Root, "media user token personalised personalized recommendations for you library manual paste"),
    SettingEntry(R.string.set_token_add, SettingsPage.Root, "media user token paste manual", via = R.string.set_token_title),
    SettingEntry(R.string.set_export_session, SettingsPage.Root, "session file transfer move backup account", via = R.string.set_session),
    SettingEntry(R.string.set_import_session, SettingsPage.Root, "session file transfer restore sign in login account", via = R.string.set_session),

    SettingEntry(R.string.set_color_source, SettingsPage.Appearance, "colour theme dynamic wallpaper album artwork monochrome palette"),
    SettingEntry(R.string.settings_language, SettingsPage.Appearance, "language idioma english spanish espanol locale"),
    SettingEntry(R.string.set_seed_color, SettingsPage.Appearance, "colour accent custom"),
    SettingEntry(R.string.set_theme, SettingsPage.Appearance, "dark light mode night"),
    SettingEntry(R.string.set_contrast, SettingsPage.Appearance, "accessibility readability"),
    SettingEntry(R.string.set_pure_black, SettingsPage.Appearance, "oled amoled dark"),
    SettingEntry(R.string.set_grayscale_artwork, SettingsPage.Appearance, "black and white covers"),
    SettingEntry(R.string.set_motion_covers, SettingsPage.Appearance, "animated video artwork"),
    SettingEntry(R.string.set_show_quality, SettingsPage.Appearance, "badge lossless hi-res"),
    SettingEntry(R.string.set_reduce_motion, SettingsPage.Appearance, "animations accessibility blur"),
    SettingEntry(R.string.set_seek_bar, SettingsPage.Appearance, "waveform neon progress wave style player"),

    SettingEntry(R.string.set_autoplay, SettingsPage.Playback, "queue similar continue radio"),
    SettingEntry(R.string.set_transition_mode, SettingsPage.Playback, "gapless crossfade automix beat blend dj"),
    SettingEntry(R.string.set_crossfade_duration, SettingsPage.Playback, "crossfade automix seconds duration fade"),
    SettingEntry(R.string.set_skip_silence, SettingsPage.Playback, "gaps trim quiet"),
    SettingEntry(R.string.set_resume, SettingsPage.Playback, "restore queue launch start"),
    SettingEntry(R.string.set_haptics, SettingsPage.Playback, "vibration feedback"),
    SettingEntry(R.string.set_equalizer, SettingsPage.Playback, "eq bass treble sound"),
    SettingEntry(R.string.set_tone_controls, SettingsPage.Playback, "bass treble shelf"),
    SettingEntry(R.string.set_auto_headroom, SettingsPage.Playback, "equalizer clipping preamp limiter distortion", via = R.string.set_equalizer),
    SettingEntry(R.string.set_replay_gain, SettingsPage.Playback, "loudness volume normalize normalise preamp level"),
    SettingEntry(R.string.set_balance_width, SettingsPage.Playback, "stereo pan left right mono width"),

    SettingEntry(R.string.set_streaming_quality, SettingsPage.Sound, "lossless alac aac data codec"),
    SettingEntry(R.string.set_hires_output, SettingsPage.Sound, "24-bit float 192 khz resolution"),
    SettingEntry(R.string.set_bit_perfect, SettingsPage.Sound, "dac usb external bitperfect exclusive lossless"),
    SettingEntry(R.string.set_dolby_atmos, SettingsPage.Sound, "atmos spatial surround immersive 3d eac3 binaural headphones"),
    SettingEntry(R.string.set_dither, SettingsPage.Sound, "16-bit noise shaping quantization"),

    SettingEntry(R.string.set_preload_next, SettingsPage.Streaming, "gapless buffer upcoming"),
    SettingEntry(R.string.set_keep_previous, SettingsPage.Streaming, "back previous buffer"),
    SettingEntry(R.string.set_songs_ahead, SettingsPage.Streaming, "window buffer skip"),
    SettingEntry(R.string.set_songs_behind, SettingsPage.Streaming, "window buffer back"),
    SettingEntry(R.string.set_fill_mobile, SettingsPage.Streaming, "cellular metered data wifi"),
    SettingEntry(R.string.set_streaming_cache, SettingsPage.Streaming, "storage size space"),
    SettingEntry(R.string.set_clear_cache, SettingsPage.Streaming, "delete free space storage"),

    SettingEntry(R.string.set_text_size, SettingsPage.Lyrics, "font scale"),
    SettingEntry(R.string.set_lyrics_weight, SettingsPage.Lyrics, "font weight bold regular"),
    SettingEntry(R.string.set_alignment, SettingsPage.Lyrics, "left center centre"),
    SettingEntry(R.string.set_active_line, SettingsPage.Lyrics, "anchor scroll"),
    SettingEntry(R.string.set_word_highlight, SettingsPage.Lyrics, "karaoke sync"),
    SettingEntry(R.string.set_depth_blur, SettingsPage.Lyrics, "blur focus"),
    SettingEntry(R.string.set_lyrics_pulse, SettingsPage.Lyrics, "beat pulse reactive background"),
    SettingEntry(R.string.set_amll_fallback, SettingsPage.Lyrics, "amll ttml db lyrics fallback"),

    SettingEntry(R.string.set_files_folders, SettingsPage.Downloads, "location storage"),
    SettingEntry(R.string.set_naming_dates, SettingsPage.Downloads, "file names templates"),
    SettingEntry(R.string.set_audio_quality, SettingsPage.Downloads, "download quality alac aac lossless"),
    SettingEntry(R.string.set_tags_artwork, SettingsPage.Downloads, "metadata cover"),
    SettingEntry(R.string.set_default_location, SettingsPage.DownloadFiles, "download folder storage music"),
    SettingEntry(R.string.set_albums, SettingsPage.DownloadFiles, "album folder location"),
    SettingEntry(R.string.set_playlists, SettingsPage.DownloadFiles, "playlist folder location"),
    SettingEntry(R.string.set_singles, SettingsPage.DownloadFiles, "single song folder location"),
    SettingEntry(R.string.set_album_folder, SettingsPage.DownloadNaming, "template name"),
    SettingEntry(R.string.set_track_file, SettingsPage.DownloadNaming, "template name file"),
    SettingEntry(R.string.set_playlist_folder, SettingsPage.DownloadNaming, "template name"),
    SettingEntry(R.string.set_playlist_track_file, SettingsPage.DownloadNaming, "template name file"),
    SettingEntry(R.string.set_album_date_tag, SettingsPage.DownloadNaming, "year release date"),
    SettingEntry(R.string.set_dl_quality_mode, SettingsPage.DownloadAudio, "download quality ask prompt every time default choose"),
    SettingEntry(R.string.set_dl_default_quality, SettingsPage.DownloadAudio, "download quality highest lossless hi-res alac aac 128 256 64 he-aac data saver format codec bit depth sample rate khz 16 24 44 48 96 192"),
    SettingEntry(R.string.set_dolby_atmos, SettingsPage.DownloadAudio, "download atmos spatial eac3 binaural surround"),
    SettingEntry(R.string.set_check_alac_download, SettingsPage.DownloadAudio, "alac integrity verify check download"),
    SettingEntry(R.string.set_reuse_cache, SettingsPage.Downloads, "download faster streaming cache"),
    SettingEntry(R.string.set_download_as_you_go, SettingsPage.Downloads, "streaming download segments decrypt faster"),
    SettingEntry(R.string.set_write_tags, SettingsPage.DownloadTags, "metadata"),
    SettingEntry(R.string.set_drop_ep_single, SettingsPage.DownloadTags, "ep single suffix clean album name"),
    SettingEntry(R.string.set_embed_cover, SettingsPage.DownloadTags, "artwork image"),
    SettingEntry(R.string.set_save_cover_file, SettingsPage.DownloadTags, "artwork cover jpg png sidecar folder"),
    SettingEntry(R.string.set_cover_size, SettingsPage.DownloadTags, "artwork resolution"),
    SettingEntry(R.string.set_cover_format, SettingsPage.DownloadTags, "artwork jpeg png"),
    SettingEntry(R.string.set_embed_lyrics, SettingsPage.DownloadTags, "lyrics embedded m4a"),
    SettingEntry(R.string.set_save_lyrics_file, SettingsPage.DownloadTags, "lyrics sidecar separate file lrc srt txt ttml"),
    SettingEntry(R.string.set_lyrics_format, SettingsPage.DownloadTags, "lrc ttml srt txt synced"),

    SettingEntry(R.string.set_add_favorites, SettingsPage.Library, "favourites hearts"),
    SettingEntry(R.string.set_add_playlist_songs, SettingsPage.Library, "playlists"),
    SettingEntry(R.string.set_listening_history, SettingsPage.Library, "history recently played"),
    SettingEntry(R.string.set_edit_categories, SettingsPage.Library, "sections order tabs"),
    SettingEntry(R.string.set_library_export, SettingsPage.Library, "backup json save"),
    SettingEntry(R.string.set_library_import, SettingsPage.Library, "restore json load"),

    SettingEntry(R.string.set_about_cadence, SettingsPage.About, "version"),
    SettingEntry(R.string.set_update_channel, SettingsPage.About, "release dev beta channel"),
    SettingEntry(R.string.set_update_auto, SettingsPage.About, "automatic updates launch"),
    SettingEntry(R.string.set_update_check, SettingsPage.About, "update download version"),
    SettingEntry(R.string.set_diagnostics, SettingsPage.About, "log debug"),
    SettingEntry(R.string.set_export_logs, SettingsPage.About, "log debug save"),
    SettingEntry(R.string.set_share_logs, SettingsPage.About, "log debug send"),
    SettingEntry(R.string.set_licenses, SettingsPage.About, "open source fonts"),
)

/** Where an option lives, as shown under each search result. */
@StringRes
internal fun pageLabelRes(page: SettingsPage): Int = when (page) {
    SettingsPage.Root -> R.string.set_page_root
    SettingsPage.Appearance -> R.string.set_page_appearance
    SettingsPage.Playback -> R.string.set_page_playback
    SettingsPage.Sound -> R.string.set_page_sound
    SettingsPage.Streaming -> R.string.set_page_streaming
    SettingsPage.Lyrics -> R.string.set_page_lyrics
    SettingsPage.Downloads -> R.string.set_page_downloads
    SettingsPage.DownloadFiles -> R.string.set_page_download_files
    SettingsPage.DownloadNaming -> R.string.set_page_download_naming
    SettingsPage.DownloadAudio -> R.string.set_page_download_audio
    SettingsPage.DownloadTags -> R.string.set_page_download_tags
    SettingsPage.Library -> R.string.set_page_library
    SettingsPage.About -> R.string.set_page_about
}

@Composable
internal fun pageLabel(page: SettingsPage): String = stringResource(pageLabelRes(page))

private fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Options matching every word of [query] in their title, keywords or page (accents and case
 * ignored), title matches first. [resolve] turns a string resource into text in the app's language;
 * the keywords stay in English, since most of them are technical words that read the same in both.
 */
internal fun searchSettings(
    query: String,
    resolve: (Int) -> String,
    index: List<SettingEntry> = SETTINGS_INDEX,
): List<SettingEntry> {
    val words = normalize(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val hits = index.filter { e ->
        val text = normalize("${resolve(e.title)} ${e.keywords} ${resolve(pageLabelRes(e.page))}")
        words.all { it in text } || words.joinToString("") in text.replace(" ", "")
    }
    return hits.sortedByDescending { e -> words.all { it in normalize(resolve(e.title)) } }
}

@Composable
internal fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.set_search_placeholder)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_search)) } },
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingsSearchResult(entry: SettingEntry, onOpen: () -> Unit) {
    ListItem(
        content = { Text(stringResource(entry.title)) },
        supportingContent = { Text(pageLabel(entry.page), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onOpen),
    )
}
