package app.cadence.core.design

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.TrackInfo
import kotlinx.coroutines.launch

/** Extra, context-specific rows (e.g. "Move up" inside your own playlist). */
data class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The ⋮ on any song row. Opens the full action sheet. */
@Composable
fun TrackMenuButton(info: TrackInfo, extra: List<SheetAction> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_options_for, info.track.title)) }
    if (open) TrackActionsSheet(info, onDismiss = { open = false }, extra = extra)
}

/**
 * Everything you can do with a song, in one bottom sheet: a header you can play from or jump to the
 * artist/album, then library, queue, share, credits, station, pin, favorite and "suggest less".
 * [fromPlayer] adds player-only rows (sleep timer); [beforeNavigate] lets the player collapse first.
 */
@Composable
fun TrackActionsSheet(
    info: TrackInfo,
    onDismiss: () -> Unit,
    extra: List<SheetAction> = emptyList(),
    fromPlayer: Boolean = false,
    beforeNavigate: () -> Unit = {},
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    var pickPlaylist by remember { mutableStateOf(false) }
    var shareLyrics by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    val hasLyrics by produceState(false, info.id) { value = c.repository.lyrics(info) !is Lyrics.None }

    val accountLibrary = c.repository.accountLibrary.trackIds
    val inLibrary = lib.inLibrary(info.id, accountLibrary)
    val accountFavorites = c.repository.favoriteTrackIds
    val fav = lib.isFavorite(info.id, accountFavorites)
    val pinned = "t:${info.id}" in lib.pinned
    val less = info.id in lib.suggestLess
    val downloadJobs by app.cadence.backend.engine.DownloadEngine.jobs.collectAsStateWithLifecycle()
    val dl = downloadJobs.firstOrNull { it.trackId == info.id }
    val downloaded = dl?.phase == "done"
    val downloading = dl?.active == true
    fun go(block: () -> Unit) { onDismiss(); beforeNavigate(); block() }
    fun act(block: () -> Unit) { block(); onDismiss() }

    val rows = buildList {
        add(SheetAction(if (inLibrary) stringResource(R.string.track_remove_from_library) else stringResource(R.string.track_add_to_library), if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add) {
            act { c.library.update { if (inLibrary) it.removeFromLibrary(info.id, accountLibrary) else it.addToLibrary(listOf(info.id)) } }
        })
        add(SheetAction(stringResource(R.string.track_add_to_playlist), Icons.AutoMirrored.Rounded.PlaylistAdd) { pickPlaylist = true })
        add(SheetAction(
            when { downloaded -> stringResource(R.string.track_downloaded); downloading -> stringResource(R.string.track_downloading); else -> stringResource(R.string.track_download) },
            if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
        ) { if (!downloaded && !downloading) act { downloadSong(context, info) } else onDismiss() })
        add(SheetAction(stringResource(R.string.track_play_next), Icons.AutoMirrored.Rounded.PlaylistPlay) { act { c.player.playNext(info) } })
        add(SheetAction(stringResource(R.string.track_play_last), Icons.AutoMirrored.Rounded.QueueMusic) { act { c.player.addToQueue(info) } })
        add(SheetAction(stringResource(R.string.track_share_song), Icons.Rounded.Share) { act { shareSong(context, info, c.repository.storefront) } })
        add(SheetAction(stringResource(R.string.track_copy_link), Icons.Rounded.Link) { act { copySongLink(context, info, c.repository.storefront) } })
        if (hasLyrics) add(SheetAction(stringResource(R.string.track_share_lyrics), Icons.Rounded.FormatQuote) { shareLyrics = true })
        add(SheetAction(stringResource(R.string.track_view_credits), Icons.Rounded.Info) { go { nav.credits(info.id) } })
        add(SheetAction(stringResource(R.string.track_create_station), Icons.Rounded.Radio) { act { c.player.startStation(info) } })
        add(SheetAction(if (pinned) stringResource(R.string.track_unpin) else stringResource(R.string.track_pin), if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin) {
            act { c.library.update { it.togglePin("t:${info.id}") } }
        })
        if (fromPlayer) add(SheetAction(stringResource(R.string.track_sleep_timer), Icons.Outlined.Bedtime) { sleep = true })
        add(SheetAction(if (fav) stringResource(R.string.track_undo_favorite) else stringResource(R.string.track_favorite), if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) {
            act {
                // Local toggle is instant (and the offline source of truth); push the same change to
                // the real account so it reaches the profile's Favourite Songs.
                c.library.update { it.toggleFavorite(info.id, settings.addFavoritesToLibrary, accountFavorites) }
                c.scope.launch { c.repository.setFavorite(info.id, !fav) }
            }
        })
        add(SheetAction(stringResource(if (less) R.string.track_undo_suggest_less else R.string.track_suggest_less), if (less) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt) {
            act { c.library.update { it.toggleSuggestLess(info.id) } }
        })
        extra.forEach { e -> add(e.copy(onClick = { act(e.onClick) })) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Tapping the cover plays the song right away, the fastest way out of the sheet.
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).clickable { act { c.player.play(listOf(info), contextTitle = info.album.title) } }) {
                    Artwork(info.album.id, info.album.colors, Modifier.size(72.dp), RoundedCornerShape(14.dp), coverUrl = info.album.cover)
                    Box(Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.cd_play_named, info.track.title), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(info.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        info.artist.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { go { nav.artist(info.artist.id) } }.padding(vertical = 2.dp),
                    )
                    Text(
                        info.album.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { go { nav.album(info.album.id) } }.padding(vertical = 2.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            rows.forEach { r ->
                ListItem(
                    content = { Text(r.label) },
                    leadingContent = { Icon(r.icon, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = r.onClick),
                )
            }
        }
    }
    if (pickPlaylist) AddToPlaylistDialog(listOf(info.id)) { pickPlaylist = false; onDismiss() }
    if (shareLyrics) ShareLyricsSheet(info) { shareLyrics = false; onDismiss() }
    if (sleep) SleepTimerDialog(onDismiss = { sleep = false; onDismiss() })
}

/** The text a shared song carries: its title, artist and album, then the Apple Music link that Cadence (or anything else) can open. */
fun songShareText(info: TrackInfo, storefront: String): String {
    val line = "“${info.track.title}” by ${info.artist.name}, from ${info.album.title}"
    val link = app.cadence.backend.links.AppleLinks.song(storefront, info.id)
    return if (link == null) line else "$line\n$link"
}

fun shareSong(context: android.content.Context, info: TrackInfo, storefront: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, songShareText(info, storefront))
        .putExtra(Intent.EXTRA_SUBJECT, info.track.title)
    context.startActivity(Intent.createChooser(send, null))
}

/** Puts just the song's Apple Music link on the clipboard (Cadence opens it when pasted into Search). */
fun copySongLink(context: android.content.Context, info: TrackInfo, storefront: String) {
    val link = app.cadence.backend.links.AppleLinks.song(storefront, info.id) ?: return
    copyLink(context, link)
}

fun copyLink(context: android.content.Context, link: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Apple Music link", link))
    // Android 13+ shows its own "copied" confirmation; earlier versions need ours.
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, context.getString(app.cadence.R.string.link_copied), android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val p = LocalContainer.current.player
    val state by p.state.collectAsStateWithLifecycle()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column {
                val options = listOf(
                    stringResource(R.string.player_sleep_end_of_song) to 0,
                    pluralStringResource(R.plurals.minutes, 5, 5) to 5,
                    pluralStringResource(R.plurals.minutes, 15, 15) to 15,
                    pluralStringResource(R.plurals.minutes, 30, 30) to 30,
                    stringResource(R.string.sleep_1_hour) to 60,
                )
                options.forEach { (label, m) ->
                    ListItem(
                        content = { Text(label) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { p.sleepIn(m); onDismiss() },
                    )
                }
                if (state.sleepAtMs != null) ListItem(
                    content = { Text(stringResource(R.string.player_sleep_off), color = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { p.sleepIn(null); onDismiss() },
                )
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
