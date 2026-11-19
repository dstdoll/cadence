package app.cadence.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.core.design.AlbumTile
import app.cadence.core.design.ArtistAvatar
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen() {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val genres = repo.albums.map { it.genre }.distinct().sorted()
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val albums = repo.albums.filter { genre == null || it.genre == genre }.sortedByDescending { it.year }
    val songs = repo.tracks.filter { genre == null || it.album.genre == genre }

    val browseTitle = stringResource(R.string.ctx_browse)
    ListScaffold(title = stringResource(R.string.nav_browse), subtitle = stringResource(R.string.browse_subtitle)) {
        item(key = "genres") {
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(genre == null, { genre = null }, label = { Text(stringResource(R.string.filter_all)) })
                genres.forEach { g -> FilterChip(genre == g, { genre = if (genre == g) null else g }, label = { Text(g) }) }
            }
        }
        item(key = "new-h") { SectionHeader(stringResource(R.string.browse_new_releases)) }
        item(key = "new") {
            TileRow(albums, { it.id }) { a -> AlbumTile(a, "${repo.artist(a.artistId)?.name} · ${a.year}", 180.dp) { nav.album(a.id) } }
        }
        item(key = "artists-h") { SectionHeader(stringResource(R.string.browse_artists_to_know)) }
        item(key = "artists") { TileRow(repo.artists, { it.id }) { ArtistAvatar(it) { nav.artist(it.id) } } }
        item(key = "songs-h") { SectionHeader(if (genre == null) "Fresh tracks" else "$genre tracks") }
        items(songs, key = { "s" + it.id }) { t -> TrackRow(t, onClick = { c.player.play(songs, songs.indexOf(t), contextTitle = browseTitle) }) }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}
