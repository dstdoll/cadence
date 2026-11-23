package app.cadence.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.AddToPlaylistDialog
import app.cadence.core.design.AlbumActionSheet
import app.cadence.core.design.AlbumTile
import app.cadence.core.design.ArtistActionSheet
import app.cadence.core.design.Artwork
import app.cadence.core.design.LibraryKind
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.PlaylistActionSheet
import app.cadence.core.design.PlaylistTile
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackRow
import app.cadence.core.design.plural
import app.cadence.core.design.rememberReorderState
import app.cadence.core.design.reorderHandle
import app.cadence.core.design.reorderItem
import app.cadence.backend.api.MusicRepository
import app.cadence.state.library.LibrarySection
import app.cadence.state.library.UserLibrary
import app.cadence.backend.model.Album
import app.cadence.backend.model.TrackInfo

private fun LibrarySection.icon(): ImageVector = when (this) {
    LibrarySection.Pinned -> Icons.Rounded.PushPin
    LibrarySection.Playlists -> Icons.AutoMirrored.Rounded.QueueMusic
    LibrarySection.Artists -> Icons.Rounded.Person
    LibrarySection.Albums -> Icons.Rounded.Album
    LibrarySection.Songs -> Icons.Rounded.MusicNote
    LibrarySection.Favorites -> Icons.Rounded.Favorite
    LibrarySection.MadeForYou -> Icons.Rounded.AutoAwesome
    LibrarySection.Genres -> Icons.Rounded.Category
    LibrarySection.RecentlyAdded -> Icons.Rounded.Schedule
}

private fun LibrarySection.kind(): LibraryKind? = when (this) {
    LibrarySection.Playlists -> LibraryKind.Playlists
    LibrarySection.Artists -> LibraryKind.Artists
    LibrarySection.Albums -> LibraryKind.Albums
    LibrarySection.Songs -> LibraryKind.Songs
    LibrarySection.Favorites -> LibraryKind.Favorites
    LibrarySection.MadeForYou -> LibraryKind.MadeForYou
    LibrarySection.Genres -> LibraryKind.Genres
    LibrarySection.Pinned, LibrarySection.RecentlyAdded -> null
}

/** What's actually "in the library": saved songs, and the albums/artists they belong to. */
/** Songs in the library: saved here, or in the Apple account's library and not removed here. */
internal fun libraryTracks(repo: MusicRepository, lib: UserLibrary): List<TrackInfo> {
    val account = repo.accountLibrary.trackIds
    return repo.tracks.filter { lib.inLibrary(it.id, account) }
}

internal fun libraryAlbums(repo: MusicRepository, lib: UserLibrary): List<Album> {
    val ids = libraryTracks(repo, lib).map { it.album.id }.toSet() + lib.savedAlbums + repo.accountLibrary.albumIds
    return repo.albums.filter { it.id in ids }
}

/**
 * The names a credit stands for: the whole credit plus each artist in it, so a song credited to
 * "A & B" or "A / B / C" counts for A as well. Lowercased for matching.
 */
private val creditSeparators = Regex("\\s*(?:&|,|/|;| x | feat\\.? | ft\\.? | with )\\s*", RegexOption.IGNORE_CASE)

internal fun creditedNames(credit: String): Set<String> =
    (listOf(credit) + credit.split(creditSeparators)).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/** How many of [tracks] each artist name is credited on, collaborations included. */
internal fun songsPerArtistName(tracks: List<TrackInfo>): Map<String, Int> =
    tracks.flatMap { creditedNames(it.artist.name) }.groupingBy { it }.eachCount()

/**
 * The line under a library artist: their songs, else their albums, else a plain note. An artist the
 * account library lists can have no song or album this storefront's catalogue can show, and "0
 * songs" reads as a fault.
 */
internal fun artistLine(songs: Int, albums: Int): ArtistLine = when {
    songs > 0 -> ArtistLine.Songs(songs)
    albums > 0 -> ArtistLine.Albums(albums)
    else -> ArtistLine.InLibrary
}

/** What [artistLine] chose, kept apart from the wording so the choice is testable without resources. */
internal sealed interface ArtistLine {
    data class Songs(val count: Int) : ArtistLine
    data class Albums(val count: Int) : ArtistLine
    data object InLibrary : ArtistLine
}

/** [artistLine] in the app's language. */
@Composable
internal fun artistSummary(songs: Int, albums: Int): String = when (val line = artistLine(songs, albums)) {
    is ArtistLine.Songs -> plural(line.count, "song")
    is ArtistLine.Albums -> plural(line.count, "album")
    ArtistLine.InLibrary -> stringResource(R.string.library_artist_in_library)
}

/** Artists of library songs, plus the artists the Apple account's library holds. */
internal fun libraryArtistIds(repo: MusicRepository, lib: UserLibrary): Set<String> =
    libraryTracks(repo, lib).map { it.artist.id }.toSet() + repo.accountLibrary.artistIds

/** How many items each Library section holds - the counts shown on the root rows. Mirrors the
 *  per-kind derivations in LibrarySectionScreen (albums are implied by their saved songs). */
internal fun libraryCount(kind: LibraryKind, repo: MusicRepository, lib: UserLibrary): Int = when (kind) {
    LibraryKind.Playlists -> lib.playlists.size + repo.playlists.size
    LibraryKind.MadeForYou -> repo.playlists.size
    LibraryKind.Artists -> repo.artists.count { it.id in libraryArtistIds(repo, lib) }
    LibraryKind.Albums -> libraryAlbums(repo, lib).size
    LibraryKind.Songs -> libraryTracks(repo, lib).size
    LibraryKind.Favorites -> repo.tracks.count { lib.isFavorite(it.id, repo.favoriteTrackIds) }
    LibraryKind.Genres -> repo.albums.map { it.genre }.distinct().size
}

/** The count line a Library root row shows ("12 albums"), pluralised per kind. */
@Composable
internal fun libraryCountLabel(kind: LibraryKind, repo: MusicRepository, lib: UserLibrary): String {
    val n = libraryCount(kind, repo, lib)
    val word = when (kind) {
        LibraryKind.Albums -> "album"
        LibraryKind.Artists -> "artist"
        LibraryKind.Genres -> "genre"
        LibraryKind.Playlists, LibraryKind.MadeForYou -> "playlist"
        LibraryKind.Songs, LibraryKind.Favorites -> "song"
    }
    return plural(n, word)
}

@Composable
private fun LibraryIcon(icon: ImageVector) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie6Sided.toShape()))
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun LibraryScreen() {
    val pinnedTitle = stringResource(R.string.ctx_pinned)
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    // The catalogue and the account library load in the background: re-read when they change,
    // or a Library opened before they arrive stays empty.
    val revision by repo.revision.collectAsStateWithLifecycle()
    // Songs saved in an earlier session may have dropped out of the catalogue cache.
    LaunchedEffect(lib.savedTracks, lib.favorites) { repo.prepareTracks(lib.savedTracks + lib.favorites) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val sections = lib.normalizedSections().filter { it.visible }.map { it.section }

    ListScaffold(title = stringResource(R.string.nav_library), showSettings = false, actions = {
        IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.library_new_playlist)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options)) }
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem({ Text(stringResource(R.string.library_edit_categories)) }, { menu = false; nav.editLibrary() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem({ Text(stringResource(R.string.nav_settings)) }, { menu = false; nav.settings() }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
            }
        }
    }) {
        @Suppress("UNUSED_EXPRESSION") revision
        // Pinned items lead the page as a shelf, the way you'd keep favourites within thumb reach.
        if (LibrarySection.Pinned in sections && lib.pinned.isNotEmpty()) {
            item(key = "pinned-h") { SectionHeader(stringResource(R.string.library_pinned)) }
            item(key = "pinned") {
                TileRow(lib.pinned, { it }) { key ->
                    val (kind, id) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    when (kind) {
                        "t" -> repo.track(id)?.let { t ->
                            AlbumTile(t.album.copy(title = t.track.title), t.artist.name) { c.player.play(listOf(t), contextTitle = pinnedTitle) }
                        }
                        "al" -> repo.album(id)?.let { a -> AlbumTile(a, repo.artist(a.artistId)?.name.orEmpty()) { nav.album(a.id) } }
                        "p" -> (lib.playlists + repo.libraryPlaylists + repo.playlists).firstOrNull { it.id == id }?.let { p -> PlaylistTile(p) { if (p.id.startsWith("st:")) c.player.startAppleStation(p) else nav.playlist(p.id) } }
                    }
                }
            }
        }
        val rows = sections.mapNotNull { s -> s.kind()?.let { s to it } }
        items(rows, key = { it.first.name }) { (section, kind) ->
            ListItem(
                content = { Text(stringResource(section.labelRes), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(libraryCountLabel(kind, repo, lib), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { LibraryIcon(section.icon()) },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { nav.library(kind) },
            )
        }
        if (LibrarySection.RecentlyAdded in sections) {
            val recent = libraryAlbums(repo, lib).sortedByDescending { it.released }
            if (recent.isNotEmpty()) {
                item(key = "recent-h") { SectionHeader(stringResource(R.string.library_recently_added)) }
                item(key = "recent") {
                    TileRow(recent, { it.id }) { a -> AlbumTile(a, repo.artist(a.artistId)?.name.orEmpty()) { nav.album(a.id) } }
                }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
    if (creating) AddToPlaylistDialog(emptyList()) { creating = false }
}

/** Library -> Edit: show/hide each category and drag to reorder. Changes save as you make them. */
@Composable
fun EditLibraryScreen() {
    val c = LocalContainer.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val prefs = lib.normalizedSections()
    val reorder = rememberReorderState<LibrarySection>(64.dp)
    reorder.sync(prefs.map { it.section })
    val byKey = prefs.associateBy { it.section }

    ListScaffold(title = stringResource(R.string.library_edit_title), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "hint") {
            Text(
                stringResource(R.string.library_edit_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(reorder.order, key = { _, s -> s.name }) { i, section ->
            val visible = byKey[section]?.visible ?: false
            fun toggle() = c.library.update { l -> l.copy(sections = l.normalizedSections().map { if (it.section == section) it.copy(visible = !it.visible) else it }) }
            ListItem(
                leadingContent = { Checkbox(visible, { toggle() }) },
                content = { Text(stringResource(section.labelRes)) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(section.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            Modifier.size(48.dp).reorderHandle(reorder, section) { from, to ->
                                c.library.update { l -> l.copy(sections = l.normalizedSections().toMutableList().apply { add(to, removeAt(from)) }) }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.DragHandle, stringResource(R.string.cd_reorder_named, stringResource(section.labelRes))) }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (reorder.isDragging(i)) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                ),
                modifier = Modifier.height(64.dp).reorderItem(reorder, i, Modifier.animateItem()).clickable { toggle() },
            )
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

internal enum class Sort(@StringRes val label: Int) {
    Title(R.string.sort_title), Artist(R.string.sort_artist), Album(R.string.sort_album), Year(R.string.sort_year), Duration(R.string.sort_duration)
}

/** Sort keys offered per library kind: albums can't sort by album name or track length. */
internal fun sortsFor(kind: LibraryKind): List<Sort> = when (kind) {
    LibraryKind.Albums -> listOf(Sort.Title, Sort.Artist, Sort.Year)
    else -> listOf(Sort.Title, Sort.Artist, Sort.Album, Sort.Year, Sort.Duration)
}

private val ci = String.CASE_INSENSITIVE_ORDER

internal fun trackComparator(sort: Sort): Comparator<TrackInfo> = when (sort) {
    Sort.Title -> compareBy<TrackInfo, String>(ci) { it.track.title }.thenBy(ci) { it.artist.name }
    Sort.Artist -> compareBy<TrackInfo, String>(ci) { it.artist.name }
        .thenBy(ci) { it.album.title }
        .thenBy { it.track.number }
        .thenBy(ci) { it.track.title }
    Sort.Album -> compareBy<TrackInfo, String>(ci) { it.album.title }
        .thenBy { it.track.number }
        .thenBy(ci) { it.track.title }
    Sort.Year -> compareBy<TrackInfo> { it.album.year }
        .thenBy(ci) { it.album.title }
        .thenBy { it.track.number }
        .thenBy(ci) { it.track.title }
    Sort.Duration -> compareBy<TrackInfo> { it.track.durationMs }.thenBy(ci) { it.track.title }
}

internal fun albumComparator(sort: Sort, artistName: (String) -> String): Comparator<Album> = when (sort) {
    Sort.Artist -> compareBy<Album, String>(ci) { artistName(it.artistId) }.thenBy { it.year }.thenBy(ci) { it.title }
    Sort.Year -> compareBy<Album> { it.year }.thenBy(ci) { it.title }
    else -> compareBy<Album, String>(ci) { it.title }.thenBy(ci) { artistName(it.artistId) }
}

@Composable
fun LibrarySectionScreen(kind: LibraryKind) {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val revision by repo.revision.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(Sort.Title) }
    var reversed by rememberSaveable { mutableStateOf(false) }

    ListScaffold(title = stringResource(kind.labelRes), showBack = true) {
        @Suppress("UNUSED_EXPRESSION") revision
        val hasKeys = kind == LibraryKind.Songs || kind == LibraryKind.Albums || kind == LibraryKind.Favorites
        item(key = "sort") {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = reversed,
                    onClick = { reversed = !reversed },
                    leadingIcon = {
                        Icon(
                            if (reversed) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(if (reversed) "Desc" else "Asc") },
                )
                if (hasKeys) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sortsFor(kind).forEach { s ->
                            FilterChip(sort == s, { sort = s }, label = { Text(stringResource(s.label)) })
                        }
                    }
                }
            }
        }
        when (kind) {
            LibraryKind.Playlists, LibraryKind.MadeForYou -> {
                // Playlists: the ones made here, then the account's own, then editorial ones.
                val base = if (kind == LibraryKind.Playlists) lib.playlists + repo.libraryPlaylists + repo.playlists else repo.playlists
                val all = if (reversed) base.sortedWith(compareByDescending(ci) { it.title }) else base.sortedWith(compareBy(ci) { it.title })
                if (all.isEmpty()) item(key = "empty") { Empty(stringResource(R.string.library_empty_playlists)) }
                items(all, key = { it.id }) { p ->
                    val haptic = LocalHapticFeedback.current
                    var sheet by remember { mutableStateOf(false) }
                    ListItem(
                        content = { Text(p.title) },
                        supportingContent = { Text(if (p.userCreated) "Your playlist · ${plural(p.trackIds.size, "song")}" else plural(p.trackIds.size, "song")) },
                        leadingContent = { Artwork(p.id, p.colors, Modifier.size(56.dp), RoundedCornerShape(12.dp), coverUrl = p.cover) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(
                            onClick = { nav.playlist(p.id) },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sheet = true },
                        ).alpha(if (p.available) 1f else 0.38f),
                    )
                    if (sheet) PlaylistActionSheet(p) { sheet = false }
                }
            }
            LibraryKind.Artists -> {
                val ids = libraryArtistIds(repo, lib)
                val artists = repo.artists.filter { it.id in ids }
                    .sortedWith(if (reversed) compareByDescending(ci) { it.name } else compareBy(ci) { it.name })
                // Counted once per change, not once per row.
                val songCounts = songsPerArtistName(libraryTracks(repo, lib))
                val albumCounts = libraryAlbums(repo, lib).groupingBy { it.artistId }.eachCount()
                if (artists.isEmpty()) item(key = "empty") { Empty(stringResource(R.string.library_empty_artists)) }
                items(artists, key = { it.id }) { a ->
                    val haptic = LocalHapticFeedback.current
                    var sheet by remember { mutableStateOf(false) }
                    ListItem(
                        content = { Text(a.name) },
                        supportingContent = { Text(artistSummary(songCounts[a.name.lowercase()] ?: 0, albumCounts[a.id] ?: 0)) },
                        leadingContent = {
                            // Recomputed when the catalogue changes (revision), not on every recomposition.
                            val art = remember(a.id, a.cover, revision) { artistArt(a, repo.albumsOfArtist(a.id)) }
                            Artwork(a.id, listOf(a.color, "#1B1B1F", a.color), Modifier.size(56.dp), MaterialShapes.Cookie9Sided.toShape(), coverUrl = art)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(
                            onClick = { nav.artist(a.id) },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sheet = true },
                        ),
                    )
                    if (sheet) ArtistActionSheet(a) { sheet = false }
                }
            }
            LibraryKind.Albums -> {
                val base = libraryAlbums(repo, lib)
                val albumCmp = albumComparator(sort) { repo.artist(it)?.name ?: "" }
                val albums = base.sortedWith(if (reversed) albumCmp.reversed() else albumCmp)
                if (albums.isEmpty()) item(key = "empty") { Empty(stringResource(R.string.library_empty_albums)) }
                items(albums, key = { it.id }) { a ->
                    val haptic = LocalHapticFeedback.current
                    var sheet by remember { mutableStateOf(false) }
                    val artistName = repo.artist(a.artistId)?.name.orEmpty()
                    ListItem(
                        content = { Text(a.title) },
                        supportingContent = { Text("$artistName · ${a.year}") },
                        leadingContent = { Artwork(a.id, a.colors, Modifier.size(56.dp), RoundedCornerShape(12.dp), coverUrl = a.cover) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(
                            onClick = { nav.album(a.id) },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sheet = true },
                        ).alpha(if (a.available) 1f else 0.38f),
                    )
                    if (sheet) AlbumActionSheet(a, artistName) { sheet = false }
                }
            }
            LibraryKind.Genres -> {
                val genres = repo.albums.map { it.genre }.distinct()
                    .sortedWith(if (reversed) compareByDescending(ci) { it } else compareBy(ci) { it })
                items(genres, key = { it }) { g ->
                    ListItem(
                        content = { Text(g) },
                        supportingContent = { Text(plural(repo.tracks.count { it.album.genre == g }, "song")) },
                        leadingContent = { Artwork("genre-$g", repo.albums.first { it.genre == g }.colors, Modifier.size(56.dp), RoundedCornerShape(12.dp)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.genre(g) },
                    )
                }
            }
            LibraryKind.Songs, LibraryKind.Favorites -> {
                val base = if (kind == LibraryKind.Favorites) repo.tracks.filter { lib.isFavorite(it.id, repo.favoriteTrackIds) } else libraryTracks(repo, lib)
                val trackCmp = trackComparator(sort)
                val songs = base.sortedWith(if (reversed) trackCmp.reversed() else trackCmp)
                if (songs.isEmpty()) item(key = "empty") {
                    Empty(if (kind == LibraryKind.Favorites) stringResource(R.string.library_empty_favorites) else stringResource(R.string.library_empty_default))
                }
                items(songs, key = { it.id }) { t -> TrackRow(t, onClick = { c.player.play(songs, songs.indexOf(t), contextTitle = kind.title) }) }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Empty(text: String) =
    Text(text, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
