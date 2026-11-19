package app.cadence.feature.radio

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.ArtistAvatar
import app.cadence.core.design.Artwork
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.hiResCover

/**
 * Radio. Apple Music's Radio tab is a featured station plus stations grouped by genre and artist.
 * A "station" here reuses the player's local recommendation queue (PlayerController.startStation):
 * tapping a card seeds an endless-feeling autoplay queue from a representative song.
 * BACKEND: real /v1/me/stations + a continuous station feed (replace the empty st: stub in
 * RealCatalog.preparePlaylist).
 */
@Composable
fun RadioScreen() {
    val c = LocalContainer.current
    val repo = c.repository
    val lib by c.library.state.collectAsStateWithLifecycle()

    fun seedForGenre(g: String) = repo.tracks.firstOrNull { it.album.genre == g }
    fun seedForArtist(id: String) = repo.tracks.firstOrNull { it.artist.id == id }

    val genres = remember(repo.albums) { repo.albums.map { it.genre }.distinct().filter { it.isNotBlank() }.sorted() }
    val artists = remember(lib.followedArtists, repo.tracks) {
        (lib.followedArtists.toList() + repo.tracks.filter { lib.inLibrary(it.id) }.map { it.artist.id })
            .distinct().mapNotNull { repo.artist(it) }.take(12)
            .ifEmpty { repo.artists.take(12) }
    }
    // A rotating hero rather than always track #0, so Cadence Radio doesn't show one fixed cover.
    val featured = remember(repo.tracks) { repo.tracks.randomOrNull() }

    ListScaffold(title = stringResource(R.string.nav_radio), showSettings = false) {
        if (featured != null) {
            item(key = "feat") {
                Box(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(170.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .clickable { c.player.startStation(featured) },
                ) {
                    Artwork("radio-featured", featured.album.colors, Modifier.fillMaxSize(), RectangleShape, square = false, coverUrl = hiResCover(featured.album.cover))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text(stringResource(R.string.radio_card_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(stringResource(R.string.radio_subtitle), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
        item(key = "yours-h") { SectionHeader(stringResource(R.string.radio_yours)) }
        item(key = "yours") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "discovery") {
                    StationTile("station-discovery", stringResource(R.string.radio_discovery), listOf("#1B6FF3", "#0A2A66")) { c.player.startDiscoveryStation() }
                }
                item(key = "mine") {
                    StationTile("station-mine", stringResource(R.string.radio_mine), listOf("#E0457B", "#5A0E2E")) { c.player.startMyStation() }
                }
            }
        }
        item(key = "genre-h") { SectionHeader(stringResource(R.string.radio_stations_by_genre)) }
        item(key = "genre") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(genres, key = { it }) { g ->
                    val colors = repo.albums.firstOrNull { it.genre == g }?.colors ?: listOf("#6750A4", "#21005D")
                    StationTile("radio-$g", "$g Station", colors) { seedForGenre(g)?.let { c.player.startStation(it) } }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "artist-h") { SectionHeader(stringResource(R.string.radio_artist_stations)) }
            item(key = "artist") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(artists, key = { it.id }) { a ->
                        ArtistAvatar(a) { seedForArtist(a.id)?.let { c.player.startStation(it) } }
                    }
                }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

/** A genre/mood station card: seeded procedural art with a radio glyph and a title. */
@Composable
private fun StationTile(key: String, title: String, colors: List<String>, onClick: () -> Unit) {
    Column(Modifier.width(156.dp).clickable(onClick = onClick).padding(bottom = 8.dp)) {
        Box {
            Artwork(key, colors, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
            Icon(Icons.Rounded.Radio, null, Modifier.align(Alignment.Center).padding(4.dp), tint = Color.White)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.radio_station), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
