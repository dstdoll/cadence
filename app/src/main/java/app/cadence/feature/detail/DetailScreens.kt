package app.cadence.feature.detail

import app.cadence.core.design.plural
import app.cadence.core.design.releaseDateLine
import app.cadence.core.design.runtimeLine
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PersonAddAlt
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.AlbumTile
import app.cadence.core.design.ArtistAvatar
import app.cadence.core.design.PlaylistTile
import app.cadence.core.design.Artwork
import app.cadence.core.design.coverTemplate
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackMenuButton
import app.cadence.core.design.SheetAction
import app.cadence.core.design.TrackRow
import app.cadence.core.design.totalMinutes
import app.cadence.core.design.CoverArt
import app.cadence.core.design.hiResCover
import app.cadence.core.design.DownloadStatusIcon
import app.cadence.backend.model.AudioQuality
import app.cadence.feature.nowplaying.QualityBadge
import app.cadence.core.design.rememberReorderState
import app.cadence.core.design.rememberHaptic
import app.cadence.backend.engine.DownloadEngine
import kotlinx.coroutines.launch
import app.cadence.core.design.reorderItem
import app.cadence.core.design.reorderOnLongPress
import androidx.compose.foundation.background
import app.cadence.backend.model.TrackInfo

@Composable
private fun PlayShuffleRow(tracks: List<TrackInfo>, contextTitle: String) {
    val p = LocalContainer.current.player
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { p.play(tracks, contextTitle = contextTitle) }, enabled = tracks.isNotEmpty(),
            shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp),
        ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.cd_play), style = MaterialTheme.typography.titleMedium) }
        FilledTonalButton(
            onClick = { p.play(tracks, shuffle = true, contextTitle = contextTitle) }, enabled = tracks.isNotEmpty(),
            shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp),
        ) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.cd_shuffle), style = MaterialTheme.typography.titleMedium) }
    }
}

private fun LazyListScope.header(
    key: String, colors: List<String>, title: String, subtitle: String, meta: String,
    motion: String? = null,
    cover: String? = null,
    badge: (@Composable () -> Unit)? = null,
    shape: @Composable () -> androidx.compose.ui.graphics.Shape = { RoundedCornerShape(28.dp) },
    onSubtitle: (() -> Unit)? = null,
) {
    item(key = "header") {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverArt(key, colors, motion, animate = true, Modifier.widthIn(max = 280.dp).fillMaxWidth(0.72f), shape(), coverUrl = hiResCover(cover))
            Text(title, style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, start = 20.dp, end = 20.dp))
            Text(
                subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle).padding(4.dp) else Modifier.padding(4.dp),
            )
            Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (badge != null) Box(Modifier.padding(top = 10.dp, bottom = 4.dp)) { badge() }
        }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val album = repo.album(id) ?: return
    val artist = repo.artist(album.artistId)
    // The catalogue cache may be cold when an album is opened (or when the
    // download button is tapped); prepareAlbum loads it and the state keeps the
    // page and the queue in sync.
    var tracks by remember(id) { mutableStateOf(repo.tracksOfAlbum(id)) }
    LaunchedEffect(id) { tracks = repo.prepareAlbum(id) }
    // Apple's own rows for the page arrive after the tracklist and never hold it up.
    var extras by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id) { repo.prepareAlbumExtras(id); extras++ }
    val scope = rememberCoroutineScope()
    val noTracksText = stringResource(R.string.detail_no_tracks)
    val haptic = rememberHaptic()
    val downloadJobs by DownloadEngine.jobs.collectAsStateWithLifecycle()
    val albumTrackIds = tracks.map { it.id }.toSet()
    val albumJobs = downloadJobs.filter { it.trackId in albumTrackIds }
    val albumDone = tracks.isNotEmpty() && tracks.all { t -> downloadJobs.any { it.trackId == t.id && it.phase == "done" } }
    val lib by c.library.state.collectAsStateWithLifecycle()
    val saved = tracks.isNotEmpty() && tracks.all { lib.inLibrary(it.id) }
    val pinned = "al:$id" in lib.pinned
    ListScaffold(
        title = album.title, showBack = true, showSettings = false, largeTitle = false,
        actions = {
            IconButton(onClick = {
                c.library.update { l ->
                    if (saved) tracks.fold(l.copy(savedAlbums = l.savedAlbums - id)) { acc, t -> acc.removeFromLibrary(t.id) }
                    else l.addToLibrary(tracks.map { it.id }, id)
                }
            }) { Icon(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, if (saved) stringResource(R.string.detail_remove_album) else stringResource(R.string.detail_add_album)) }
            IconButton(onClick = {
                haptic(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                if (albumJobs.any { it.active }) {
                    c.downloadsOpen.value = true
                    return@IconButton
                }
                scope.launch {
                    val list = if (tracks.isEmpty()) repo.prepareAlbum(id) else tracks
                    tracks = list
                    if (list.isEmpty()) {
                        android.widget.Toast.makeText(ctx, noTracksText, android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    DownloadEngine.enqueue(
                        ctx,
                        list.map { t ->
                            DownloadEngine.DownloadItem(
                                trackId = t.id,
                                title = t.track.title,
                                artist = t.artist.name,
                                albumTitle = t.album.title,
                                hlsUrl = t.track.audio,
                                artworkTemplate = coverTemplate(t.album.cover),
                                kind = app.cadence.backend.engine.DownloadKind.ALBUM,
                                releaseDate = t.album.released,
                                genre = t.album.genre,
                                trackNumber = t.track.number,
                                trackTotal = list.size,
                            )
                        },
                    )
                }
            }) { DownloadStatusIcon(albumJobs, stringResource(R.string.cd_download_album), completed = albumDone) }
            IconButton(onClick = { c.library.update { it.togglePin("al:$id") } }) {
                Icon(if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (pinned) stringResource(R.string.detail_unpin_album) else stringResource(R.string.detail_pin_album))
            }
        },
    ) {
        // Badge the album by its best track, the way stores label a release by its top available quality.
        val best = tracks.mapNotNull { it.track.format }.minByOrNull { AudioQuality.tier(it).ordinal } // tiers are ordered best-first
        header(
            album.id, album.colors, album.title,
            artist?.name.orEmpty(), "${album.genre} · ${album.year}",
            motion = album.motion,
            cover = album.cover,
            badge = best?.takeIf { AudioQuality.isLossless(it) }?.let { { QualityBadge(it) } },
            onSubtitle = { artist?.let { nav.artist(it.id) } },
        )
        item(key = "play") { PlayShuffleRow(tracks, album.title) }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, onClick = { c.player.play(tracks, i, contextTitle = album.title) }, leadingNumber = t.track.number)
        }
        item(key = "footer") {
            // Album footer, the way a store labels a release: release date, runtime, then the
            // ℗ line and label. Only the lines we actually have are shown.
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                val foot = MaterialTheme.typography.bodyMedium
                val faint = MaterialTheme.colorScheme.onSurfaceVariant
                releaseDateLine(album.released)?.let { Text(it, style = foot, color = faint) }
                Text("${plural(tracks.size, "song")}, ${runtimeLine(tracks)}", style = foot, color = faint)
                album.recordLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = faint, modifier = Modifier.padding(top = 10.dp))
                }
                album.copyright?.let {
                    // Apple's copyright attribute already carries the ℗; add it only if absent.
                    val line = if (it.trimStart().startsWith("℗")) it else "℗ $it"
                    Text(line, style = MaterialTheme.typography.bodySmall, color = faint)
                }
            }
        }
        val versions = extras.let { repo.otherVersions(id) } // reading `extras` re-runs this when the rows arrive
        if (versions.isNotEmpty()) {
            item(key = "versions-h") { SectionHeader(stringResource(R.string.detail_other_versions)) }
            item(key = "versions") { TileRow(versions, { it.id }) { a -> AlbumTile(a, "${a.year}") { nav.album(a.id) } } }
        }
        val more = repo.albumsOfArtist(album.artistId).filter { it.id != id }
        if (more.isNotEmpty()) {
            item(key = "more-h") { SectionHeader(stringResource(R.string.detail_more_by, artist?.name.orEmpty())) }
            item(key = "more") { TileRow(more, { it.id }) { a -> AlbumTile(a, "${a.year}") { nav.album(a.id) } } }
        }
        // You might also like: the catalogue's related albums; until they arrive (or when it has
        // none), same-genre albums by other artists from the cache stand in.
        val alsoLike = repo.relatedAlbums(id).ifEmpty { repo.albums.filter { it.genre == album.genre && it.artistId != album.artistId && it.id != id }.take(10) }
        if (alsoLike.isNotEmpty()) {
            item(key = "also-h") { SectionHeader(stringResource(R.string.detail_you_might_like)) }
            item(key = "also") { TileRow(alsoLike, { it.id }) { a -> AlbumTile(a, repo.artist(a.artistId)?.name.orEmpty()) { nav.album(a.id) } } }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val artist = repo.artist(id) ?: return
    // Start with what is cached, then load the full discography: an artist reached from
    // search has only the albums that happened to be in those results.
    var albumsAll by remember(id) { mutableStateOf(repo.albumsOfArtist(id)) }
    LaunchedEffect(id) { albumsAll = repo.prepareArtist(id) }
    // Read after `albumsAll`: that load also fills the top songs, biography and related lists.
    val songs = remember(id, albumsAll) { repo.topSongs(id) }
    val featured = remember(id, albumsAll) { repo.artistPlaylists(id) }
    val facts = listOf(artist.genres.firstOrNull().orEmpty(), artist.origin, artist.formed).filter { it.isNotBlank() }
    val following = id in lib.followedArtists
    // Discography split: Apple's isSingle, or small track counts, are singles/EPs; the rest albums.
    // BACKEND: real albumType split (albums / singles / appears-on) once the model carries it.
    val singles = albumsAll.filter { it.isSingle || it.trackCount in 1..6 }.sortedByDescending { it.released ?: "" }
    val fullAlbums = (albumsAll - singles.toSet()).sortedByDescending { it.released ?: "" }
    val latest = albumsAll.maxByOrNull { it.released ?: "" }
    // Similar artists as the catalogue lists them; only when it has none, other cached artists
    // sharing a genre stand in.
    val genres = albumsAll.map { it.genre }.toSet()
    val similar = remember(id, albumsAll) {
        repo.similarArtists(id).ifEmpty {
            repo.albums.filter { it.genre in genres && it.artistId != id }
                .map { it.artistId }.distinct().mapNotNull { repo.artist(it) }.take(12)
        }
    }

    val artistMeta = "${plural(albumsAll.size, "album")} · ${plural(songs.size, "song")}"
    val artistSubtitle = stringResource(R.string.detail_subtitle_artist)
    ListScaffold(
        title = artist.name, showBack = true, showSettings = false, largeTitle = false,
        actions = {
            IconButton(onClick = { songs.firstOrNull()?.let { c.player.startStation(it) } }) {
                Icon(Icons.Rounded.Radio, stringResource(R.string.cd_play_station, artist.name))
            }
            IconButton(onClick = { c.library.update { it.toggleFollow(id) } }) {
                Icon(if (following) Icons.Rounded.Check else Icons.Rounded.PersonAddAlt, if (following) stringResource(R.string.cd_following) else stringResource(R.string.cd_follow))
            }
        },
    ) {
        header(
            artist.id, listOf(artist.color, "#1B1B1F", artist.color), artist.name, artistSubtitle,
            artistMeta,
            cover = artist.cover,
            shape = { MaterialShapes.Cookie12Sided.toShape() },
        )
        item(key = "play") { PlayShuffleRow(songs, artist.name) }
        if (latest != null) {
            item(key = "latest-h") { SectionHeader(stringResource(R.string.detail_latest_release)) }
            item(key = "latest") {
                ListItem(
                    overlineContent = { Text(releaseDateLine(latest.released) ?: "${latest.year}") },
                    content = { Text(latest.title) },
                    supportingContent = { Text(if (latest.isSingle || latest.trackCount in 1..6) stringResource(R.string.detail_single_ep) else stringResource(R.string.detail_subtitle_album)) },
                    leadingContent = { Artwork(latest.id, latest.colors, Modifier.size(56.dp), RoundedCornerShape(10.dp), coverUrl = latest.cover) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { nav.album(latest.id) },
                )
            }
        }
        item(key = "top-h") { SectionHeader(stringResource(R.string.detail_top_songs)) }
        item(key = "top") {
            // A horizontal carousel of columns, up to five songs each, most popular first; the next
            // column peeks at the edge so it reads as swipeable, and a fling settles on a column.
            val columns = songs.take(20).chunked(5)
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val pageWidth = topSongsColumnWidth(screenWidth, columns.size)
            val carousel = rememberLazyListState()
            LazyRow(
                state = carousel,
                flingBehavior = rememberSnapFlingBehavior(carousel, SnapPosition.Start),
                contentPadding = PaddingValues(start = 16.dp, end = topSongsEndPadding(screenWidth, pageWidth)),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(columns, key = { col -> "top-${col.first().id}" }) { col ->
                    Column(Modifier.width(pageWidth)) {
                        col.forEach { t ->
                            TrackRow(t, onClick = { c.player.play(songs, songs.indexOf(t), contextTitle = artist.name) })
                        }
                    }
                }
            }
        }
        if (fullAlbums.isNotEmpty()) {
            item(key = "albums-h") { SectionHeader(stringResource(R.string.detail_albums)) }
            item(key = "albums") { TileRow(fullAlbums, { it.id }) { a -> AlbumTile(a, "${a.year}") { nav.album(a.id) } } }
        }
        if (singles.isNotEmpty()) {
            item(key = "singles-h") { SectionHeader(stringResource(R.string.detail_singles_eps)) }
            item(key = "singles") { TileRow(singles, { it.id }) { a -> AlbumTile(a, "${a.year}") { nav.album(a.id) } } }
        }
        if (featured.isNotEmpty()) {
            item(key = "featured-h") { SectionHeader(stringResource(R.string.detail_featured_playlists)) }
            item(key = "featured") { TileRow(featured, { it.id }) { p -> PlaylistTile(p) { nav.playlist(p.id) } } }
        }
        if (similar.isNotEmpty()) {
            item(key = "similar-h") { SectionHeader(stringResource(R.string.detail_similar_artists)) }
            item(key = "similar") { TileRow(similar, { it.id }) { a -> ArtistAvatar(a) { nav.artist(a.id) } } }
        }
        item(key = "about-h") { SectionHeader(stringResource(R.string.detail_about)) }
        if (facts.isNotEmpty()) item(key = "facts") {
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        }
        item(key = "about") {
            // A long biography opens at a few lines and expands in place.
            var expanded by rememberSaveable(id) { mutableStateOf(false) }
            var overflows by remember(id) { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 20.dp).animateContentSize()) {
                Text(
                    artist.bio.ifBlank { stringResource(R.string.detail_no_biography) },
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
                if (overflows || expanded) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) stringResource(R.string.action_less) else stringResource(R.string.action_more))
                    }
                }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

private val PLAYLIST_ROW = 72.dp

@Composable
fun PlaylistScreen(id: String) {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val playlist = lib.playlists.firstOrNull { it.id == id } ?: repo.playlist(id) ?: return
    // User/library playlists render their ids reactively; a networked recommendation playlist
    // starts empty and its tracklist is fetched once on open.
    val derived = playlist.trackIds.mapNotNull(repo::track)
    var loaded by remember(id) { mutableStateOf<List<TrackInfo>?>(null) }
    LaunchedEffect(id, derived.isEmpty()) {
        if (derived.isEmpty() && !playlist.userCreated && loaded == null) loaded = repo.preparePlaylist(id)
    }
    val tracks = if (derived.isNotEmpty()) derived else loaded.orEmpty()
    val own = playlist.userCreated
    val playlistSubtitle = if (own) stringResource(R.string.detail_your_playlist) else playlist.curator.ifBlank { stringResource(R.string.detail_curated) }
    val playlistMeta = "${plural(tracks.size, "song")} · ${if (tracks.isEmpty()) 0 else totalMinutes(tracks)} min"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptic = rememberHaptic()
    val downloadJobs by DownloadEngine.jobs.collectAsStateWithLifecycle()
    val playlistTrackIds = tracks.map { it.id }.toSet()
    val playlistJobs = downloadJobs.filter { it.trackId in playlistTrackIds }
    val playlistDone = tracks.isNotEmpty() && tracks.all { t -> downloadJobs.any { it.trackId == t.id && it.phase == "done" } }
    // Reorder over positions (a playlist may hold the same song twice).
    val reorder = rememberReorderState<Int>(PLAYLIST_ROW)
    reorder.sync(tracks.indices.toList())
    val commit: (Int, Int) -> Unit = { from, to ->
        c.library.update { l -> l.editPlaylist(id) { p -> p.copy(trackIds = p.trackIds.toMutableList().apply { add(to, removeAt(from)) }) } }
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    ListScaffold(
        title = playlist.title, showBack = true, showSettings = false, largeTitle = false,
        actions = {
            val pinnedP = "p:$id" in lib.pinned
            IconButton(
                enabled = tracks.isNotEmpty(),
                onClick = {
                    haptic(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                    if (playlistJobs.any { it.active }) {
                        c.downloadsOpen.value = true
                        return@IconButton
                    }
                    app.cadence.backend.engine.DownloadEngine.downloadAll(
                        ctx,
                        tracks.mapIndexed { index, t ->
                            app.cadence.backend.engine.DownloadEngine.DownloadItem(
                                trackId = t.id,
                                title = t.track.title,
                                artist = t.artist.name,
                                albumTitle = t.album.title,
                                hlsUrl = t.track.audio,
                                artworkTemplate = coverTemplate(t.album.cover),
                                kind = app.cadence.backend.engine.DownloadKind.PLAYLIST,
                                collectionTitle = playlist.title,
                                releaseDate = t.album.released,
                                genre = t.album.genre,
                                trackNumber = index + 1,
                                trackTotal = tracks.size,
                            )
                        },
                    )
                },
            ) { DownloadStatusIcon(playlistJobs, stringResource(R.string.cd_download_playlist), completed = playlistDone) }
            IconButton(onClick = { c.library.update { it.togglePin("p:$id") } }) {
                Icon(if (pinnedP) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (pinnedP) stringResource(R.string.detail_unpin_playlist) else stringResource(R.string.detail_pin_playlist))
            }
            if (own) {
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.cd_add_songs)) }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, stringResource(R.string.cd_rename)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.cd_delete_playlist)) }
            }
        },
    ) {
        header(motion = playlist.motion, cover = playlist.cover, key = playlist.id, colors = playlist.colors, title = playlist.title, subtitle = playlistSubtitle, meta = playlistMeta)
        if (playlist.description.isNotBlank()) item(key = "desc") {
            Text(
                playlist.description, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        item(key = "play") { PlayShuffleRow(tracks, playlist.title) }
        if (own && tracks.size > 1) item(key = "hint") {
            Text(
                stringResource(R.string.detail_reorder_hint), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (tracks.isEmpty()) item(key = "empty") {
            TextButton(onClick = { adding = true }, modifier = Modifier.padding(20.dp)) { Text(stringResource(R.string.detail_add_some_songs)) }
        }
        itemsIndexed(reorder.order, key = { _, at -> "p$at-${tracks.getOrNull(at)?.id}" }) { i, at ->
            val t = tracks.getOrNull(at) ?: return@itemsIndexed
            TrackRow(
                t, onClick = { c.player.play(tracks, at, contextTitle = playlist.title) },
                modifier = Modifier
                    .height(PLAYLIST_ROW)
                    .reorderItem(reorder, i, Modifier.animateItem())
                    .reorderOnLongPress(reorder, at, enabled = own, onMove = commit)
                    .background(if (reorder.isDragging(i)) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                trailing = if (!own) null else ({
                    fun edit(f: (MutableList<String>) -> Unit) =
                        c.library.update { l -> l.editPlaylist(id) { p -> p.copy(trackIds = p.trackIds.toMutableList().also(f)) } }
                    TrackMenuButton(
                        t,
                        extra = buildList {
                            if (at > 0) add(SheetAction(stringResource(R.string.detail_move_up), Icons.Rounded.KeyboardArrowUp) { edit { it.add(at - 1, it.removeAt(at)) } })
                            if (at < tracks.lastIndex) add(SheetAction(stringResource(R.string.detail_move_down), Icons.Rounded.KeyboardArrowDown) { edit { it.add(at + 1, it.removeAt(at)) } })
                            add(SheetAction(stringResource(R.string.detail_remove_from_playlist), Icons.Rounded.RemoveCircleOutline) { edit { it.removeAt(at) } })
                        },
                    )
                }),
            )
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.detail_rename_playlist)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { c.library.update { it.editPlaylist(id) { p -> p.copy(title = name.ifBlank { p.title }) } }; renaming = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_confirm, playlist.title)) },
            text = { Text(stringResource(R.string.detail_delete_hint)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; nav.back(); c.library.update { it.deletePlaylist(id) } }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (adding) {
        val chosen = rememberSaveable { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.cd_add_songs)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(380.dp)) {
                    items(repo.tracks.size) { idx ->
                        val t = repo.tracks[idx]
                        val on = t.id in chosen.value
                        ListItem(
                            content = { Text(t.track.title) },
                            supportingContent = { Text(t.artist.name) },
                            leadingContent = { Artwork(t.album.id, t.album.colors, Modifier.size(40.dp), RoundedCornerShape(8.dp), coverUrl = t.album.cover) },
                            trailingContent = { Checkbox(on, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { chosen.value = if (on) chosen.value - t.id else chosen.value + t.id },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val add = repo.tracks.map { it.id }.filter { it in chosen.value }
                    c.library.update { it.editPlaylist(id) { p -> p.copy(trackIds = p.trackIds + add) } }
                    adding = false
                }) { Text(chosen.value.size.let { n -> if (n > 0) stringResource(R.string.detail_add_n, n) else stringResource(R.string.detail_add) }) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Lines of an artist biography shown before "More". */
private const val BIO_COLLAPSED_LINES = 6

/**
 * Width of one Top Songs column: one column fills the row (nothing to swipe to); several leave the
 * next one peeking at the edge, capped so tablets and landscape show more than one column.
 */
internal fun topSongsColumnWidth(screenWidth: Dp, columns: Int): Dp =
    if (columns <= 1) screenWidth - 32.dp else (screenWidth - 52.dp).coerceAtMost(400.dp)

/**
 * Space after the last column, so it can scroll to the same start edge as every other column
 * instead of stopping flush with the right side (snapping then lands every column in one place).
 */
internal fun topSongsEndPadding(screenWidth: Dp, columnWidth: Dp): Dp =
    (screenWidth - columnWidth - 16.dp).coerceAtLeast(16.dp)
