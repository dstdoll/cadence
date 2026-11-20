package app.cadence.feature.search

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.cadence.R
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.AppContainer
import app.cadence.LocalContainer
import app.cadence.core.design.Artwork
import app.cadence.core.design.LocalBottomInset
import app.cadence.core.design.LocalChrome
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.Navigator
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TrackMenuButton
import app.cadence.core.design.TrackDownloadButton
import app.cadence.backend.search.HitType
import app.cadence.backend.search.SearchHit
import app.cadence.feature.library.artistArt
import app.cadence.backend.search.uniqueByKey
import app.cadence.backend.search.SearchResults
import app.cadence.backend.api.SearchScope
import app.cadence.backend.api.Suggestions
import kotlinx.coroutines.delay

private enum class Scope(@StringRes val label: Int) { All(R.string.search_scope_all), Library(R.string.search_scope_library) }

/** Localized plural label for a result-type filter chip (the backend HitType carries English). */
@Composable
private fun searchTypeLabel(t: HitType): String = stringResource(
    when (t) {
        HitType.Song -> R.string.search_type_songs
        HitType.Artist -> R.string.search_type_artists
        HitType.Album -> R.string.search_type_albums
        HitType.Playlist -> R.string.search_type_playlists
        HitType.Lyric -> R.string.search_type_lyrics
    },
)

private const val SUGGEST_DEBOUNCE_MS = 150L

/**
 * Search, in four states: idle (title + browse categories), focused (recent searches), typing (scope
 * tabs, query completions with the typed part highlighted, direct matches) and submitted (type filters
 * over a mixed, relevance-ranked list). Focused states hide the tab bar and mini player.
 */
@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val chrome = LocalChrome.current
    val focus = LocalFocusManager.current
    val lib by c.library.state.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf(Scope.All) }
    var tab by rememberSaveable { mutableStateOf<HitType?>(null) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val active = focused || query.isNotEmpty()
    // Chrome hides only while typing; once results are up, the tab bar and mini player come back.
    val typing = focused || (query.isNotEmpty() && !submitted)

    DisposableEffect(typing) {
        chrome.immersive = typing
        onDispose { chrome.immersive = false }
    }
    fun reset() { query = ""; submitted = false; tab = null; focus.clearFocus(); focused = false }
    BackHandler(active) { reset() }
    fun submit(q: String) {
        if (q.isBlank()) return
        query = q; submitted = true; tab = null
        keyboard?.hide()
        focus.clearFocus()
        c.library.update { it.searched(q.trim()) }
    }

    // All ranking happens behind SearchService (backend); the screen only asks and renders.
    val revision by c.repository.revision.collectAsStateWithLifecycle()
    val searchScope = remember(scope, lib, revision) {
        val account = c.repository.accountLibrary
        SearchScope(
            // "Your library" is what Library shows: saved here plus the Apple account's library.
            libraryTrackIds = if (scope == Scope.All) null else lib.savedTracks + (account.trackIds - lib.hiddenFromLibrary),
            savedAlbumIds = lib.savedAlbums + account.albumIds,
            userPlaylists = lib.playlists,
        )
    }
    val results by produceState(SearchResults(emptyList(), emptyMap()), searchScope, query, submitted) {
        if (submitted && query.isNotBlank()) value = c.backend.search.search(query, searchScope)
    }
    val suggestions by produceState(Suggestions(emptyList(), emptyList()), searchScope, query) {
        if (query.isBlank()) { value = Suggestions(emptyList(), emptyList()); return@produceState }
        delay(SUGGEST_DEBOUNCE_MS) // a real backend shouldn't get a request per keystroke
        value = c.backend.search.suggest(query, searchScope)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AnimatedVisibility(!active, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings)) }
            }
        }
        SearchField(
            query = query,
            active = active,
            submittedScope = if (submitted) stringResource(scope.label) else null,
            onQuery = { query = it; submitted = false },
            onSubmit = { submit(query) },
            onBack = { reset() },
            onFocus = { focused = it },
        )
        Box(Modifier.weight(1f).imePadding()) {
            when {
                !active -> Categories(c, nav)
                query.isBlank() -> RecentSearches(lib.recentSearches, onPick = { submit(it) }, onClear = { c.library.update { l -> l.copy(recentSearches = emptyList()) } })
                else -> Column {
                    PrimaryTabRow(selectedTabIndex = scope.ordinal, containerColor = Color.Transparent) {
                        Scope.entries.forEach { s -> Tab(selected = scope == s, onClick = { scope = s }, text = { Text(stringResource(s.label)) }) }
                    }
                    if (submitted) Results(results, tab, { tab = it }, nav, c)
                    else Suggestions(query, suggestions, onComplete = { submit(it) }, nav = nav, c = c)
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String, active: Boolean, submittedScope: String?,
    onQuery: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit, onFocus: (Boolean) -> Unit,
) {
    val requester = remember { FocusRequester() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1) },
        leadingIcon = {
            if (active) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
            else Icon(Icons.Rounded.Search, null)
        },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); requester.requestFocus() }) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_search)) } },
        supportingText = submittedScope?.let { { Text(stringResource(R.string.search_in, it)) } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (active) 8.dp else 0.dp)
            .focusRequester(requester)
            .onFocusChanged { onFocus(it.isFocused) },
    )
}

/** Browse grid: each genre is a tinted card with its own generated art, opening the genre page. */
@Composable
private fun Categories(c: AppContainer, nav: Navigator) {
    val repo = c.repository
    val genres = repo.albums.map { it.genre }.distinct()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalBottomInset.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.search_browse_categories), inset = 4.dp) }
        items(genres) { g ->
            val colors = repo.albums.first { it.genre == g }.colors
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clickable { nav.genre(g) }.background(Color.Transparent, RoundedCornerShape(20.dp))) {
                Artwork("genre-$g", colors, Modifier.fillMaxSize(), RoundedCornerShape(20.dp), square = false)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)), RoundedCornerShape(20.dp)))
                Text(g, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
            }
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recent.isEmpty()) item {
            Text(
                "Search the whole catalog or just your library. Lyrics count too: try a line you remember.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            item { SectionHeader(stringResource(R.string.search_recent), action = stringResource(R.string.action_clear), onAction = onClear) }
            items(recent) { s ->
                ListItem(
                    content = { Text(s) },
                    leadingContent = { Icon(Icons.Rounded.History, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(s) },
                )
            }
        }
    }
}

/** Live suggestions: completions first (typed part bright, the rest dimmed), then direct matches. */
@Composable
private fun Suggestions(
    query: String, suggestions: Suggestions,
    onComplete: (String) -> Unit, nav: Navigator, c: AppContainer,
) {
    val completions = suggestions.completions
    val direct = suggestions.matches
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(completions, key = { "c-$it" }) { s ->
            val typed = query.trim().lowercase()
            val at = s.lowercase().indexOf(typed)
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Search, null) },
                content = {
                    Text(buildAnnotatedString {
                        if (at < 0 || at + typed.length > s.length) withStyle(SpanStyle(color = dim)) { append(s) } else {
                            withStyle(SpanStyle(color = dim)) { append(s.substring(0, at)) }
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(at, at + typed.length)) }
                            withStyle(SpanStyle(color = dim)) { append(s.substring(at + typed.length)) }
                        }
                    })
                },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.CallMade, stringResource(R.string.cd_use_suggestion), tint = dim) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onComplete(s) },
            )
        }
        val directRows = direct.uniqueByKey()
        val directAmbiguous = ambiguousArtistNames(directRows)
        items(directRows, key = { it.key }) { hit -> HitRow(hit, nav, c, directAmbiguous) }
        if (completions.isEmpty() && direct.isEmpty()) item {
            Text(stringResource(R.string.search_no_matches_yet), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Results(
    results: SearchResults, tab: HitType?, onTab: (HitType?) -> Unit, nav: Navigator, c: AppContainer,
) {
    if (results.isEmpty) {
        Text(stringResource(R.string.search_nothing_matches), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val types = HitType.entries.filter { it in results.byType }
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(tab == null, { onTab(null) }, label = { Text(stringResource(R.string.search_top_results)) })
            types.forEach { t -> FilterChip(tab == t, { onTab(t) }, label = { Text(searchTypeLabel(t)) }) }
        }
        val list = if (tab == null) results.top else results.byType[tab].orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
            val rows = list.uniqueByKey()
            val ambiguous = ambiguousArtistNames(rows)
            items(rows, key = { it.key }) { hit -> HitRow(hit, nav, c, ambiguous) }
        }
    }
}

/**
 * Artist names that more than one row in [rows] carries (two different artists named alike): only
 * those rows get their genre in the subtitle, so a name that is unique stays as clean as before.
 */
internal fun ambiguousArtistNames(rows: List<SearchHit>): Set<String> =
    rows.filterIsInstance<SearchHit.ArtistHit>().groupBy { it.artist.name }.filterValues { it.size > 1 }.keys

/** One result row; the subtitle always starts with what kind of thing it is. */
@Composable
private fun HitRow(hit: SearchHit, nav: Navigator, c: AppContainer, ambiguousArtists: Set<String> = emptySet()) {
    val searchTitle = stringResource(R.string.ctx_search)
    val (title, subtitle, art) = when (hit) {
        is SearchHit.ArtistHit -> Triple(
            hit.artist.name,
            hit.artist.genres.firstOrNull()?.takeIf { hit.artist.name in ambiguousArtists }
                ?.let { stringResource(R.string.search_hit_artist_genre, it) } ?: stringResource(R.string.search_hit_artist),
            Triple(hit.artist.id, listOf(hit.artist.color, "#1B1B1F", hit.artist.color), true),
        )
        is SearchHit.AlbumHit -> Triple(hit.album.title, stringResource(R.string.search_hit_album, hit.artistName), Triple(hit.album.id, hit.album.colors, false))
        is SearchHit.SongHit -> Triple(hit.track.track.title, stringResource(R.string.search_hit_song, hit.track.artist.name), Triple(hit.track.album.id, hit.track.album.colors, false))
        is SearchHit.PlaylistHit -> Triple(hit.playlist.title, stringResource(R.string.search_hit_playlist, if (hit.playlist.userCreated) stringResource(R.string.search_hit_yours) else hit.playlist.curator.ifBlank { "Cadence" }), Triple(hit.playlist.id, hit.playlist.colors, false))
        is SearchHit.LyricHit -> Triple("“${hit.line}”", stringResource(R.string.search_hit_lyrics, hit.track.track.title, hit.track.artist.name), Triple(hit.track.album.id, hit.track.album.colors, false))
    }
    // Real cover per hit type: albums/songs share the album artwork, artists use their photo.
    val cover = when (hit) {
        is SearchHit.AlbumHit -> hit.album.cover
        is SearchHit.SongHit -> hit.track.album.cover
        is SearchHit.LyricHit -> hit.track.album.cover
        is SearchHit.PlaylistHit -> hit.playlist.cover
        // The artist's photo, else their newest album's cover, as the library's artist rows already do.
        is SearchHit.ArtistHit -> remember(hit.artist.id, hit.artist.cover) { artistArt(hit.artist, c.repository.albumsOfArtist(hit.artist.id)) }
    }
    ListItem(
        leadingContent = {
            val shape = if (art.third) MaterialShapes.Cookie9Sided.toShape() else RoundedCornerShape(10.dp)
            Box {
                Artwork(art.first, art.second, Modifier.size(52.dp), shape, coverUrl = cover)
                if (hit is SearchHit.LyricHit) Icon(
                    Icons.Rounded.FormatQuote, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(2.dp),
                )
            }
        },
        content = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = when (hit) {
            is SearchHit.SongHit -> ({ Row(verticalAlignment = Alignment.CenterVertically) { TrackMenuButton(hit.track); TrackDownloadButton(hit.track) } })
            is SearchHit.LyricHit -> ({ Row(verticalAlignment = Alignment.CenterVertically) { TrackMenuButton(hit.track); TrackDownloadButton(hit.track) } })
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.semantics(mergeDescendants = true) {}.clickable {
            when (hit) {
                is SearchHit.ArtistHit -> nav.artist(hit.artist.id)
                is SearchHit.AlbumHit -> nav.album(hit.album.id)
                is SearchHit.PlaylistHit -> if (hit.playlist.id.startsWith("st:")) c.player.startAppleStation(hit.playlist) else nav.playlist(hit.playlist.id)
                is SearchHit.SongHit -> c.player.play(listOf(hit.track), contextTitle = searchTitle)
                is SearchHit.LyricHit -> c.player.play(listOf(hit.track), contextTitle = searchTitle)
            }
        },
    )
}
