package app.cadence.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.cadence.R
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.TrackInfo

/** A library section. [title] is the stable English name (logs, test hooks); [labelRes] is what the UI shows. */
enum class LibraryKind(val title: String, @androidx.annotation.StringRes val labelRes: Int) {
    Playlists("Playlists", R.string.kind_playlists), Artists("Artists", R.string.kind_artists), Albums("Albums", R.string.kind_albums),
    Songs("Songs", R.string.kind_songs), Favorites("Favorites", R.string.kind_favorites),
    MadeForYou("Made For You", R.string.kind_made_for_you), Genres("Genres", R.string.kind_genres),
}

/** Navigation actions any screen may trigger, without threading lambdas through every layer. */
interface Navigator {
    fun album(id: String)
    fun artist(id: String)
    fun playlist(id: String)
    fun library(kind: LibraryKind)
    fun settings()
    fun credits(trackId: String)
    fun genre(name: String)
    fun editLibrary()
    fun equalizer()
    fun toneControls()
    fun replayGain()
    fun balance()
    fun dither()
    fun profile()
    fun back()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

/** Screens can ask the shell to hide navigation chrome (e.g. focused search, like a full-screen text field). */
class ChromeState {
    var immersive by mutableStateOf(false)
}

val LocalChrome = staticCompositionLocalOf { ChromeState() }

/** Extra bottom space so content can scroll clear of the mini player. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** Localized "%d songs/albums/artists/genres/playlists/hours/minutes" for the given [word]. */
@Composable
fun plural(n: Int, word: String): String = pluralStringResource(
    when (word) {
        "song" -> R.plurals.songs
        "album" -> R.plurals.albums
        "artist" -> R.plurals.artists
        "genre" -> R.plurals.genres
        "playlist" -> R.plurals.playlists
        "hour" -> R.plurals.hours
        "minute" -> R.plurals.minutes
        else -> R.plurals.songs
    },
    n, n,
)

fun totalMinutes(tracks: List<TrackInfo>) = (tracks.sumOf { it.track.durationMs } / 60_000).toInt().coerceAtLeast(1)

/** "48 minutes" for short releases, "1 hour, 12 minutes" once an album crosses an hour. */
@Composable
fun runtimeLine(tracks: List<TrackInfo>): String {
    val mins = totalMinutes(tracks)
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0 -> plural(m, "minute")
        m == 0 -> plural(h, "hour")
        else -> "${plural(h, "hour")}, ${plural(m, "minute")}"
    }
}

/** ISO date ("2022-06-10") -> a readable footer line ("June 10, 2022"); null/blank -> null. */
fun releaseDateLine(iso: String?): String? {
    val d = iso?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val date = java.time.LocalDate.parse(d.take(10))
        val month = date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        "$month ${date.dayOfMonth}, ${date.year}"
    }.getOrDefault(d)
}

@Composable
fun rememberHaptic(): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    val enabled = LocalContainer.current.settings.state.collectAsStateWithLifecycle().value.haptics
    return { type -> if (enabled) haptics.performHapticFeedback(type) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, inset: Dp = 20.dp, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = inset, end = (inset - 12.dp).coerceAtLeast(0.dp), top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun <T> TileRow(items: List<T>, key: (T) -> String, content: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) { items(items, key = key) { content(it) } }
}

/** Tiles round their bottom corners less than the cover, so the ripple clip never bites into caption glyphs. */
private val TileShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp)

@Composable
fun AlbumTile(album: Album, subtitle: String, width: Dp = 156.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(TileShape).semantics(mergeDescendants = true) {}.clickable(onClick = onClick).alpha(if (album.available) 1f else 0.38f).padding(bottom = 8.dp)) {
        Artwork(album.id, album.colors, Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), coverUrl = album.cover)
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistTile(playlist: Playlist, width: Dp = 156.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(TileShape).semantics(mergeDescendants = true) {}.clickable(onClick = onClick).alpha(if (playlist.available) 1f else 0.38f).padding(bottom = 8.dp)) {
        Artwork(playlist.id, playlist.colors, Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), coverUrl = playlist.cover)
        Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        // Recommendation/radio cards carry no tracklist until opened, so a song count would read "0".
        // Show the curator/description instead and only count songs once they're actually loaded.
        val subtitle = if (playlist.trackIds.isNotEmpty()) plural(playlist.trackIds.size, "song")
            else playlist.description.ifBlank { stringResource(R.string.components_playlist_fallback) }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Artists get a cookie-shaped avatar: a small expressive accent that also reads as "person, not album". */
@Composable
fun ArtistAvatar(artist: Artist, size: Dp = 112.dp, onClick: () -> Unit) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Column(Modifier.width(size).semantics(mergeDescendants = true) {}.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(artist.id, listOf(artist.color, "#1B1B1F", artist.color), Modifier.size(size), shape, coverUrl = artist.cover)
        Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Animated bars marking the playing row. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val color = MaterialTheme.colorScheme.primary
    Row(modifier.size(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(420, 300, 520).forEach { period ->
            val h by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "bar")
            Box(
                Modifier.weight(1f).fillMaxHeight(if (playing) h else 0.3f)
                    .clip(RoundedCornerShape(2.dp)).background(color),
            )
        }
    }
}

/** Enqueues one song for download with the size-agnostic artwork template. The single place that
 *  builds a per-song download request, reused by every download affordance. */
fun downloadSong(@Suppress("UNUSED_PARAMETER") context: android.content.Context, info: TrackInfo) {
    // Through the request flow: it may ask which quality to use, or warn that the song is already downloaded.
    app.cadence.feature.download.DownloadRequests.request(
        listOf(
            app.cadence.backend.engine.DownloadEngine.DownloadItem(
                trackId = info.id,
                title = info.track.title,
                artist = info.artist.name,
                albumTitle = info.album.title,
                hlsUrl = info.track.audio,
                artworkTemplate = coverTemplate(info.album.cover),
                kind = app.cadence.backend.engine.DownloadKind.SINGLE,
                releaseDate = info.album.released,
                genre = info.album.genre,
                trackNumber = info.track.number,
                durationMs = info.track.durationMs,
            )
        )
    )
}

/** Download icon-button + progress ring for a single song. Used by rows, the player and sheets. */
@Composable
fun TrackDownloadButton(info: TrackInfo) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val downloadJobs by app.cadence.backend.engine.DownloadEngine.jobs.collectAsStateWithLifecycle()
    val downloadJob = downloadJobs.firstOrNull { it.trackId == info.id }
    val haptic = rememberHaptic()
    if (downloadJob?.active == true) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            DownloadStatusIcon(listOf(downloadJob), stringResource(R.string.cd_downloading_named, info.track.title))
        }
    } else {
        IconButton(onClick = {
            haptic(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
            downloadSong(ctx, info)
        }) {
            DownloadStatusIcon(if (downloadJob != null) listOf(downloadJob) else emptyList(), stringResource(R.string.cd_download_song))
        }
    }
}

@Composable
fun TrackRow(
    info: TrackInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingNumber: Int? = null,
    /** Drawn highlighted (a song opened from a link); not played. */
    selected: Boolean = false,
    showArtwork: Boolean = leadingNumber == null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsStateWithLifecycle()
    val lib by c.library.state.collectAsStateWithLifecycle()
    val isCurrent = state.current?.id == info.id
    val isFav = lib.isFavorite(info.id, c.repository.favoriteTrackIds)
    val available = info.track.available
    ListItem(
        modifier = modifier
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(enabled = available, onClick = onClick)
            .alpha(if (available) 1f else 0.38f),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            Box(Modifier.size(if (showArtwork) 48.dp else 28.dp), contentAlignment = Alignment.Center) {
                if (showArtwork) Artwork(info.album.id, info.album.colors, Modifier.size(48.dp), RoundedCornerShape(10.dp), coverUrl = info.album.cover)
                if (isCurrent) {
                    if (showArtwork) Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)))
                    EqualizerBars(state.isPlaying)
                } else if (!showArtwork && leadingNumber != null) {
                    Text("$leadingNumber", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        content = {
            Text(
                info.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                if (showArtwork) "${info.artist.name} · ${info.album.title}" else "${info.artist.name} · ${formatTime(info.track.durationMs)}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFav) Icon(Icons.Rounded.Favorite, stringResource(R.string.cd_favorited), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                (trailing ?: { TrackMenuButton(info) })()
                if (available) TrackDownloadButton(info)
            }
        },
    )
}

@Composable
fun AddToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.components_add_to_playlist)) },
        text = {
            Column {
                LazyColumn(Modifier.height((lib.playlists.size.coerceIn(0, 5) * 56).dp)) {
                    items(lib.playlists, key = { it.id }) { p ->
                        ListItem(
                            content = { Text(p.title) },
                            supportingContent = { Text("${plural(p.trackIds.size, "song")}") },
                            leadingContent = { Artwork(p.id, p.colors, Modifier.size(40.dp), RoundedCornerShape(8.dp), coverUrl = p.cover) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable {
                                val toLibrary = c.settings.state.value.addPlaylistSongsToLibrary
                                c.library.update { l ->
                                    l.editPlaylist(p.id) { it.copy(trackIds = it.trackIds + trackIds) }.let { if (toLibrary) it.addToLibrary(trackIds) else it }
                                }
                                onDismiss()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.library_new_playlist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val toLibrary = c.settings.state.value.addPlaylistSongsToLibrary
                c.library.update { it.createPlaylist(newName, trackIds, toLibrary).first }
                onDismiss()
            }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
