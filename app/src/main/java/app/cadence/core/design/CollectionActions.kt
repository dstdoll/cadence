package app.cadence.core.design

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.R
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.DownloadKind
import app.cadence.backend.links.AppleLinks
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.TrackInfo
import app.cadence.feature.download.DownloadRequests

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheetShell(
    title: String,
    subtitle: String,
    artwork: @Composable () -> Unit,
    rows: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                artwork()
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
}

private fun shareLink(context: Context, subject: String, link: String?) {
    if (link == null) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "$subject\n$link")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
    context.startActivity(Intent.createChooser(send, null))
}

private fun enqueueDownloads(tracks: List<TrackInfo>, kind: String, collectionTitle: String? = null) {
    DownloadRequests.request(
        tracks.map { t ->
            DownloadEngine.DownloadItem(
                trackId = t.id,
                title = t.track.title,
                artist = t.artist.name,
                albumTitle = t.album.title,
                collectionTitle = collectionTitle.orEmpty(),
                hlsUrl = t.track.audio,
                artworkTemplate = coverTemplate(t.album.cover),
                kind = kind,
                releaseDate = t.album.released,
                genre = t.album.genre,
                trackNumber = t.track.number,
                trackTotal = tracks.size,
                durationMs = t.track.durationMs,
            )
        },
    )
}

@Composable
fun AlbumActionSheet(album: Album, artistName: String, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val tracks = remember(album.id) { c.repository.tracksOfAlbum(album.id) }
    val accountTracks = c.repository.accountLibrary.trackIds
    val inLibrary = album.id in lib.savedAlbums || album.id in c.repository.accountLibrary.albumIds
    val pinned = "al:${album.id}" in lib.pinned
    var verifyAlac by remember { mutableStateOf(false) }
    var saveArt by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) { block(); onDismiss() }
    fun go(block: () -> Unit) { onDismiss(); block() }

    val rows = buildList {
        if (tracks.isNotEmpty()) {
            add(SheetAction(stringResource(R.string.cd_play), Icons.Rounded.PlayArrow) { act { c.player.play(tracks, 0, contextTitle = album.title) } })
            add(SheetAction(stringResource(R.string.cd_shuffle), Icons.Rounded.Shuffle) { act { c.player.play(tracks.shuffled(), 0, contextTitle = album.title) } })
            add(SheetAction(stringResource(R.string.track_play_next), Icons.AutoMirrored.Rounded.PlaylistPlay) { act { tracks.asReversed().forEach { c.player.playNext(it) } } })
            add(SheetAction(stringResource(R.string.track_play_last), Icons.AutoMirrored.Rounded.QueueMusic) { act { tracks.forEach { c.player.addToQueue(it) } } })
            add(SheetAction(stringResource(R.string.cd_download_album), Icons.Rounded.Download) { act { enqueueDownloads(tracks, DownloadKind.ALBUM) } })
        }
        add(SheetAction(if (inLibrary) stringResource(R.string.detail_remove_album) else stringResource(R.string.detail_add_album), if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add) {
            act {
                c.library.update { l ->
                    if (inLibrary) tracks.fold(l) { acc, t -> acc.removeFromLibrary(t.id, accountTracks) }.copy(savedAlbums = l.savedAlbums - album.id)
                    else l.addToLibrary(tracks.map { it.id }, albumId = album.id)
                }
            }
        })
        add(SheetAction(if (pinned) stringResource(R.string.detail_unpin_album) else stringResource(R.string.detail_pin_album), if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin) {
            act { c.library.update { it.togglePin("al:${album.id}") } }
        })
        if (album.artistId.isNotBlank()) {
            add(SheetAction(stringResource(R.string.action_open_artist), Icons.Rounded.Person) { go { nav.artist(album.artistId) } })
        }
        AppleLinks.album(c.repository.storefront, album.id)?.let { link ->
            add(SheetAction(stringResource(R.string.cd_share_album), Icons.Rounded.Share) { act { shareLink(context, "${album.title} — $artistName", link) } })
            add(SheetAction(stringResource(R.string.track_copy_link), Icons.Rounded.Link) { act { copyLink(context, link) } })
        }
        if (album.cover != null || album.motion != null) {
            add(SheetAction(stringResource(R.string.artwork_save_title), Icons.Rounded.Image) { saveArt = true })
        }
        if (tracks.isNotEmpty()) {
            add(SheetAction(stringResource(R.string.track_verify_alac), Icons.Rounded.Verified) { verifyAlac = true })
        }
    }

    ActionSheetShell(
        title = album.title,
        subtitle = artistName,
        artwork = { Artwork(album.id, album.colors, Modifier.size(64.dp), RoundedCornerShape(14.dp), coverUrl = album.cover) },
        rows = rows,
        onDismiss = onDismiss,
    )
    if (saveArt) SaveArtworkDialog(album.title, album.id, album.cover, album.motion) { saveArt = false; onDismiss() }
    if (verifyAlac) AlacCheckDialog(album.title, tracks) { verifyAlac = false; onDismiss() }
}

@Composable
fun PlaylistActionSheet(playlist: Playlist, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val tracks = remember(playlist.id, playlist.trackIds) { playlist.trackIds.mapNotNull { c.repository.track(it) } }
    val pinned = "p:${playlist.id}" in lib.pinned
    var renaming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable(playlist.id) { mutableStateOf(playlist.title) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var verifyAlac by remember { mutableStateOf(false) }
    var saveArt by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) { block(); onDismiss() }

    val rows = buildList {
        if (tracks.isNotEmpty()) {
            add(SheetAction(stringResource(R.string.cd_play), Icons.Rounded.PlayArrow) { act { c.player.play(tracks, 0, contextTitle = playlist.title) } })
            add(SheetAction(stringResource(R.string.cd_shuffle), Icons.Rounded.Shuffle) { act { c.player.play(tracks.shuffled(), 0, contextTitle = playlist.title) } })
            add(SheetAction(stringResource(R.string.track_play_next), Icons.AutoMirrored.Rounded.PlaylistPlay) { act { tracks.asReversed().forEach { c.player.playNext(it) } } })
            add(SheetAction(stringResource(R.string.track_play_last), Icons.AutoMirrored.Rounded.QueueMusic) { act { tracks.forEach { c.player.addToQueue(it) } } })
            add(SheetAction(stringResource(R.string.cd_download_playlist), Icons.Rounded.Download) { act { enqueueDownloads(tracks, DownloadKind.PLAYLIST, playlist.title) } })
        }
        add(SheetAction(if (pinned) stringResource(R.string.detail_unpin_playlist) else stringResource(R.string.detail_pin_playlist), if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin) {
            act { c.library.update { it.togglePin("p:${playlist.id}") } }
        })
        if (playlist.trackIds.isNotEmpty()) {
            val copyLabel = stringResource(R.string.playlist_copy_suffix)
            add(SheetAction(stringResource(R.string.playlist_duplicate), Icons.Rounded.ContentCopy) {
                act { c.library.update { it.duplicatePlaylist(playlist, copyLabel).first } }
            })
        }
        if (playlist.userCreated) {
            add(SheetAction(stringResource(R.string.detail_rename_playlist), Icons.Rounded.Edit) { renaming = true })
            add(SheetAction(stringResource(R.string.cd_delete_playlist), Icons.Rounded.Delete) { deleting = true })
        }
        AppleLinks.playlist(c.repository.storefront, playlist.id)?.let { link ->
            add(SheetAction(stringResource(R.string.action_share), Icons.Rounded.Share) { act { shareLink(context, playlist.title, link) } })
            add(SheetAction(stringResource(R.string.track_copy_link), Icons.Rounded.Link) { act { copyLink(context, link) } })
        }
        if (playlist.cover != null || playlist.motion != null) {
            add(SheetAction(stringResource(R.string.artwork_save_title), Icons.Rounded.Image) { saveArt = true })
        }
        if (tracks.isNotEmpty()) {
            add(SheetAction(stringResource(R.string.track_verify_alac), Icons.Rounded.Verified) { verifyAlac = true })
        }
    }

    ActionSheetShell(
        title = playlist.title,
        subtitle = if (playlist.trackIds.isNotEmpty()) plural(playlist.trackIds.size, "song") else playlist.description,
        artwork = { Artwork(playlist.id, playlist.colors, Modifier.size(64.dp), RoundedCornerShape(14.dp), coverUrl = playlist.cover) },
        rows = rows,
        onDismiss = onDismiss,
    )
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.detail_rename_playlist)) },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val trimmed = newName.trim()
                    c.library.update { it.editPlaylist(playlist.id) { p -> p.copy(title = trimmed) } }
                    renaming = false
                    onDismiss()
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.detail_delete_confirm, playlist.title)) },
            text = { Text(stringResource(R.string.detail_delete_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    c.library.update { it.deletePlaylist(playlist.id) }
                    deleting = false
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (saveArt) SaveArtworkDialog(playlist.title, playlist.id, playlist.cover, playlist.motion) { saveArt = false; onDismiss() }
    if (verifyAlac) AlacCheckDialog(playlist.title, tracks) { verifyAlac = false; onDismiss() }
}

@Composable
fun ArtistActionSheet(artist: Artist, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val topTracks = remember(artist.id) { c.repository.tracks.filter { it.artist.id == artist.id } }
    val shape: Shape = MaterialShapes.Cookie9Sided.toShape()
    var saveArt by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) { block(); onDismiss() }
    fun go(block: () -> Unit) { onDismiss(); block() }

    val rows = buildList {
        if (topTracks.isNotEmpty()) {
            add(SheetAction(stringResource(R.string.cd_play), Icons.Rounded.PlayArrow) { act { c.player.play(topTracks, 0, contextTitle = artist.name) } })
            add(SheetAction(stringResource(R.string.track_create_station), Icons.Rounded.Radio) { act { c.player.startArtistStation(artist.id, topTracks.first()) } })
        }
        add(SheetAction(stringResource(R.string.action_open_artist), Icons.Rounded.Person) { go { nav.artist(artist.id) } })
        if (artist.cover != null) {
            add(SheetAction(stringResource(R.string.artwork_save_title), Icons.Rounded.Image) { saveArt = true })
        }
        AppleLinks.artist(c.repository.storefront, artist.id)?.let { link ->
            add(SheetAction(stringResource(R.string.action_share), Icons.Rounded.Share) { act { shareLink(context, artist.name, link) } })
            add(SheetAction(stringResource(R.string.track_copy_link), Icons.Rounded.Link) { act { copyLink(context, link) } })
        }
    }

    ActionSheetShell(
        title = artist.name,
        subtitle = stringResource(R.string.detail_subtitle_artist),
        artwork = { Artwork(artist.id, listOf(artist.color, "#1B1B1F", artist.color), Modifier.size(64.dp), shape, coverUrl = artist.cover) },
        rows = rows,
        onDismiss = onDismiss,
    )
    if (saveArt) SaveArtworkDialog(artist.name, artist.id, artist.cover, null) { saveArt = false; onDismiss() }
}
