package app.cadence.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.AlbumTile
import app.cadence.core.design.Artwork
import app.cadence.core.design.hiResCover
import app.cadence.backend.model.ShelfItem
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.PlaylistTile
import androidx.compose.ui.res.stringResource
import app.cadence.R
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackRow
import app.cadence.core.design.DownloadStatusIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import java.util.Calendar

private data class Pick(
    val id: String,
    val title: String,
    val subtitle: String,
    val colors: List<String>,
    val cover: String? = null,
    val open: () -> Unit,
)

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Pull-to-refresh re-fetches the personal feed; `tick` re-reads the (snapshot) repo lists after.
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val greetingRes = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
    }
    val greeting = stringResource(greetingRes)
    val recentlyPlayed = stringResource(R.string.ctx_recently_played)
    // Re-read the catalogue whenever it changes: the account's shelves arrive after first paint.
    val revision by repo.revision.collectAsStateWithLifecycle()
    val shelves = remember(revision, tick) { repo.shelves }
    fun pick(item: ShelfItem) = when (item) {
        is ShelfItem.AlbumItem -> item.album.let { a -> Pick(a.id, a.title, repo.artist(a.artistId)?.name.orEmpty(), a.colors, cover = a.cover) { nav.album(a.id) } }
        is ShelfItem.PlaylistItem -> item.playlist.let { p -> Pick(p.id, p.title, p.description, p.colors, cover = p.cover) { nav.playlist(p.id) } }
    }
    // The service marks one shelf as the leading one; without shelves Home builds its own.
    val hero = shelves.firstOrNull { it.hero } ?: shelves.firstOrNull()
    val picks = remember(revision, tick) {
        hero?.items?.map(::pick)
            ?: (repo.playlists.map { Pick(it.id, it.title, it.description, it.colors, cover = it.cover) { nav.playlist(it.id) } } +
                repo.albums.take(3).map { a -> Pick(a.id, a.title, repo.artist(a.artistId)?.name.orEmpty(), a.colors, cover = a.cover) { nav.album(a.id) } })
    }
    // Local history when it exists; otherwise the account's recent-first albums
    // (RealCatalog orders /v1/me/recent/played ahead of recommendations and charts).
    val recent = lib.recentTracks.mapNotNull(repo::track).take(6)
        // With real shelves the service's own "recently played" row covers the empty-history case.
        .ifEmpty { if (shelves.isEmpty()) repo.albums.take(6).mapNotNull { a -> repo.tracksOfAlbum(a.id).firstOrNull() } else emptyList() }

    val jobs by app.cadence.backend.engine.DownloadEngine.jobs.collectAsStateWithLifecycle()
    val active = jobs.count { it.active }
    ListScaffold(
        title = stringResource(R.string.nav_home),
        subtitle = greeting,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch { runCatching { repo.refresh() }; tick++; refreshing = false }
            }
        },
        isRefreshing = refreshing,
        actions = {
            BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                IconButton(onClick = { c.downloadsOpen.value = true }) {
                    DownloadStatusIcon(jobs.filter { it.active }, stringResource(R.string.cd_downloads), completed = false)
                }
            }
        },
    ) {
        item(key = "picks-h") { SectionHeader(hero?.title ?: stringResource(R.string.home_top_picks)) }
        item(key = "picks") {
            // Fixed-size snapping carousel: cards keep one size, no grow/shrink animation.
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(picks, key = { it.id }) { pick ->
                    Box(
                        Modifier.width(300.dp).height(240.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .clickable(onClick = pick.open),
                    ) {
                        // High-res artwork for the large hero (small tiles keep the 300px variant).
                        Artwork(pick.id, pick.colors, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, square = false, coverUrl = hiResCover(pick.cover))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                            Text(pick.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text(pick.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent-h") { SectionHeader(recentlyPlayed) }
            items(recent, key = { "r" + it.id }) { t ->
                TrackRow(t, onClick = { c.player.play(recent, recent.indexOf(t), contextTitle = recentlyPlayed) })
            }
        }
        if (shelves.isNotEmpty()) {
            // The account's own shelves, in the order and under the titles the service gives them.
            shelves.filter { it !== hero }.forEach { shelf ->
                item(key = "sh-h-" + shelf.id) { SectionHeader(shelf.title) }
                item(key = "sh-" + shelf.id) {
                    TileRow(shelf.items, { it.key }) { item ->
                        when (item) {
                            is ShelfItem.AlbumItem -> AlbumTile(item.album, repo.artist(item.album.artistId)?.name.orEmpty()) { nav.album(item.album.id) }
                            is ShelfItem.PlaylistItem -> PlaylistTile(item.playlist) { nav.playlist(item.playlist.id) }
                        }
                    }
                }
            }
        } else {
            item(key = "mixes-h") { SectionHeader(stringResource(R.string.home_made_for_you)) }
            item(key = "mixes") { TileRow(repo.playlists, { it.id }) { PlaylistTile(it) { nav.playlist(it.id) } } }
            item(key = "albums-h") { SectionHeader(stringResource(R.string.home_albums_you_might_like)) }
            item(key = "albums") {
                TileRow(repo.albums.sortedByDescending { it.year }, { it.id }) { a ->
                    AlbumTile(a, repo.artist(a.artistId)?.name.orEmpty()) { nav.album(a.id) }
                }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}
