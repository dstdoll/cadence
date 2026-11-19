package app.cadence.feature.detail

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.core.design.AlbumTile
import app.cadence.core.design.ArtistAvatar
import app.cadence.core.design.Artwork
import app.cadence.core.design.ArtworkRenderer
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackRow
import app.cadence.backend.model.AudioQuality
import app.cadence.backend.model.Credit
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.QualityTier
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Who made the song: performers, writers, production, plus the audio quality on offer. */
@Composable
fun CreditsScreen(trackId: String) {
    val c = LocalContainer.current
    val info = c.repository.track(trackId) ?: return
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    val lyrics by produceState<Lyrics>(Lyrics.None, info.id) { value = c.repository.lyrics(info) }
    val released = info.album.released?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull()
    }
    val credits = info.track.credits

    val performersTitle = stringResource(R.string.credits_performers)
    val writingTitle = stringResource(R.string.credits_writing)
    val productionTitle = stringResource(R.string.credits_production)
    ListScaffold(title = stringResource(R.string.credits_title), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(info.album.id, info.album.colors, Modifier.size(200.dp), RoundedCornerShape(24.dp), coverUrl = info.album.cover)
                Text(info.track.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                Text(
                    listOfNotNull(info.artist.name, info.album.title, released).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { c.player.play(listOf(info), contextTitle = info.album.title) },
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(52.dp),
                    ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.cd_play)) }
                    FilledTonalButton(
                        onClick = { lyricsOpen = true }, enabled = lyrics !is Lyrics.None,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(52.dp),
                    ) { Icon(Icons.Rounded.FormatQuote, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.player_lyrics)) }
                }
            }
        }
        fun section(title: String, people: List<Credit>) {
            if (people.isEmpty()) return
            item(key = "h-$title") { SectionHeader(title) }
            items(people, key = { "$title-${it.name}" }) { p ->
                ListItem(
                    content = { Text(p.name) },
                    supportingContent = { Text(p.roles) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (credits == null) item(key = "none") {
            Text(stringResource(R.string.credits_none), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            section(performersTitle, credits.performers)
            section(writingTitle, credits.writing)
            section(productionTitle, credits.production)
        }
        info.track.format?.let { f ->
            val tier = AudioQuality.tier(f)
            item(key = "q-h") { SectionHeader(stringResource(R.string.moreinfo_audio_quality)) }
            item(key = "q") {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.GraphicEq, null) },
                    content = { Text(if (tier == QualityTier.Lossy) f.codec else app.cadence.feature.nowplaying.tierLabel(tier)) },
                    supportingContent = { Text("${AudioQuality.spec(f)}. ${app.cadence.feature.nowplaying.tierDescription(tier)}") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }

    if (lyricsOpen) ModalBottomSheet(onDismissRequest = { lyricsOpen = false }, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        val text = when (val l = lyrics) {
            is Lyrics.Synced -> l.lines.joinToString("\n") { it.text }
            is Lyrics.Plain -> l.text
            Lyrics.None -> ""
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(info.track.title, style = MaterialTheme.typography.titleLarge)
            Text(info.artist.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3f), modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** A browse category: tinted hero, then its albums, artists and songs. */
@Composable
fun GenreScreen(name: String) {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val albums = repo.albums.filter { it.genre == name }.sortedByDescending { it.year }
    val songs = repo.tracks.filter { it.album.genre == name }
    val artists = albums.map { it.artistId }.distinct().mapNotNull(repo::artist)
    val colors = albums.firstOrNull()?.colors ?: listOf("#6750A4", "#21005D")

    ListScaffold(title = name, showBack = true, showSettings = false) {
        item(key = "hero") {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(140.dp).clip(RoundedCornerShape(28.dp))
                    // palette() always yields three stops; a cover with one colour must not crash the screen.
                    .background(Brush.linearGradient(ArtworkRenderer.palette(colors).map { Color(it) })),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                    Text("${albums.size} albums · ${songs.size} songs", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        item(key = "play") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { c.player.play(songs, contextTitle = name) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.cd_play))
                }
                FilledTonalButton(onClick = { c.player.play(songs, shuffle = true, contextTitle = name) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.cd_shuffle))
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "al-h") { SectionHeader(stringResource(R.string.detail_albums)) }
            item(key = "al") { TileRow(albums, { it.id }) { a -> AlbumTile(a, repo.artist(a.artistId)?.name.orEmpty()) { nav.album(a.id) } } }
        }
        if (artists.isNotEmpty()) {
            item(key = "ar-h") { SectionHeader(stringResource(R.string.more_artists)) }
            item(key = "ar") { TileRow(artists, { it.id }) { a -> ArtistAvatar(a) { nav.artist(a.id) } } }
        }
        item(key = "s-h") { SectionHeader(stringResource(R.string.more_songs)) }
        items(songs, key = { "s" + it.id }) { t -> TrackRow(t, onClick = { c.player.play(songs, songs.indexOf(t), contextTitle = name) }) }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}
