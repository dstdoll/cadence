package app.cadence.feature.nowplaying

import app.cadence.core.design.colors
import kotlinx.coroutines.launch
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.AddToPlaylistDialog
import app.cadence.core.design.TrackActionsSheet
import app.cadence.core.design.Artwork
import app.cadence.core.design.CoverArt
import app.cadence.core.design.hiResCover
import app.cadence.core.design.LocalNavigator
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.TrackInfo
import app.cadence.feature.lyrics.LyricsView
import app.cadence.feature.queue.QueueView
import kotlinx.coroutines.delay

enum class PlayerMode { Artwork, Lyrics, Queue }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlaying(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val state by c.player.state.collectAsStateWithLifecycle()
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val info = state.current ?: return
    var mode by rememberSaveable { mutableStateOf(PlayerMode.Artwork) }
    val clock = rememberPlaybackClock(state.isPlaying, c.player::position)
    val lyrics by produceState<Lyrics>(Lyrics.None, info.id) { value = c.repository.lyrics(info) }

    // Swallow taps so nothing underneath reacts. Vertical drags anywhere on the sheet move it (see AppShell).
    Box(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        PlayerBackground()
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val wide = maxWidth >= 720.dp && maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                TopBar(info, state.contextTitle, onCollapse)
                if (wide) {
                    // Dos paneles: la caratula a la izquierda, dimensionada por la ALTURA disponible, y
                    // titulo + controles a la derecha. Letras o cola solo cuando el modo las pide: antes el
                    // panel derecho mostraba letras siempre, incluso en modo Artwork.
                    Row(Modifier.weight(1f)) {
                        // Sin tope de ancho: en horizontal la caratula la limita la ALTURA del panel,
                        // asi llena su mitad en vez de quedar flotando chica en el medio.
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            BigArtwork(info, state.isPlaying)
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = if (mode == PlayerMode.Artwork) Arrangement.Center else Arrangement.Bottom,
                        ) {
                            when (mode) {
                                PlayerMode.Queue -> QueueView(Modifier.weight(1f))
                                PlayerMode.Lyrics -> LyricsView(
                                    lyrics, c.player::position, state.isPlaying, { c.player.seekTo(it) }, settings,
                                    Modifier.weight(1f),
                                )
                                PlayerMode.Artwork -> Unit
                            }
                            TitleRow(info)
                            Controls(mode = mode, onMode = { mode = it }, clock = clock, showVolume = mode == PlayerMode.Artwork)
                        }
                    }
                } else {
                    SharedTransitionLayout(Modifier.weight(1f)) {
                        AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { m ->
                            val art = Modifier.sharedElement(rememberSharedContentState("art"), this@AnimatedContent)
                            when (m) {
                                PlayerMode.Artwork -> Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        BigArtwork(info, state.isPlaying, art)
                                    }
                                    TitleRow(info)
                                }
                                PlayerMode.Lyrics -> Column(Modifier.fillMaxSize()) {
                                    CompactHeader(info, art)
                                    LyricsView(
                                        lyrics, c.player::position, state.isPlaying, { c.player.seekTo(it) }, settings,
                                        Modifier.weight(1f),
                                    )
                                }
                                PlayerMode.Queue -> Column(Modifier.fillMaxSize()) {
                                    CompactHeader(info, art)
                                    QueueView(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Controls(mode = mode, onMode = { mode = it }, clock = clock, showVolume = mode == PlayerMode.Artwork)
                }
            }
        }
    }
}

@Composable
private fun TopBar(info: TrackInfo, contextTitle: String?, onCollapse: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(top = 8.dp).size(36.dp, 4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)).align(Alignment.CenterHorizontally),
        )
        // Icons and text share the readable-on-background treatment, so they stay legible on dark covers.
        val text = rememberPlayerTextColors()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.cd_collapse), Modifier.size(32.dp), tint = text.title) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.player_playing_from), style = MaterialTheme.typography.labelSmall, color = text.artist)
                Text(contextTitle ?: info.album.title, style = MaterialTheme.typography.labelLarge, color = text.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OverflowMenu(info, onCollapse, tint = text.title)
        }
    }
}

@Composable
private fun OverflowMenu(info: TrackInfo, onCollapse: () -> Unit, tint: androidx.compose.ui.graphics.Color) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more_options), tint = tint) }
    if (open) TrackActionsSheet(info, onDismiss = { open = false }, fromPlayer = true, beforeNavigate = onCollapse)
}

/** Large cover that relaxes (shrinks) while paused and springs back when music resumes. */
@Composable
private fun BigArtwork(info: TrackInfo, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (isPlaying) 1f else 0.82f, spring(0.62f, Spring.StiffnessLow), label = "art")
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    // The neon takes the cover's own colours, read from its pixels as the desktop visualiser does. Until the
    // cover has been analysed, Apple's palette lifted towards white stands in.
    val coverPalette = app.cadence.core.design.rememberCoverPalette(info.album.cover)
    val fallbackNeon = remember(info.album.colors) {
        info.album.colors.mapNotNull { hex -> runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
            .ifEmpty { listOf(androidx.compose.ui.graphics.Color.White) }.map { lerpToWhite(it, 0.35f) }
    }
    val neonColors = remember(coverPalette, fallbackNeon) { coverPalette?.colors() ?: fallbackNeon }
    Box(
        modifier.padding(horizontal = PlayerGutter, vertical = 12.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .neonTube(settings.waveStyle == app.cadence.state.settings.WaveStyle.Neon, c.wave, isPlaying, neonColors, 28.dp),
    ) {
        CoverArt(
            info.album.id, info.album.colors, info.album.motion, animate = isPlaying,
            // Sin fillMaxWidth: fijaba minWidth==maxWidth y anulaba el aspectRatio(1f) de CoverArt, asi
            // que en una ventana baja la caratula se desbordaba por encima del titulo.
            modifier = Modifier.shadow(if (isPlaying) 24.dp else 8.dp, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            coverUrl = hiResCover(info.album.cover),
        )
    }
}

/** Title and artist colours measured against the player's actual background (see PlayerContrast). */
@Composable
private fun rememberPlayerTextColors(): PlayerTextColors {
    val cs = MaterialTheme.colorScheme
    return remember(cs.onSurface, cs.onSurfaceVariant, cs.surfaceContainerLowest, cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer) {
        playerTextColors(cs)
    }
}

@Composable
private fun TitleRow(info: TrackInfo) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val fav = lib.isFavorite(info.id, c.repository.favoriteTrackIds)
    Row(Modifier.fillMaxWidth().padding(start = PlayerGutter, end = PlayerGutter - IconInset, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val text = rememberPlayerTextColors()
            Text(info.track.title, style = MaterialTheme.typography.headlineSmall, color = text.title, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(
                info.artist.name, style = MaterialTheme.typography.titleMedium, color = text.artist,
                maxLines = 1, modifier = Modifier.clickable { nav.artist(info.artist.id) },
            )
        }
        FavoriteButton(fav) {
            val next = !fav
            c.library.update { it.toggleFavorite(info.id, c.settings.state.value.addFavoritesToLibrary, c.repository.favoriteTrackIds) }
            c.scope.launch { c.repository.setFavorite(info.id, next) }
        }
    }
}

@Composable
private fun FavoriteButton(fav: Boolean, onToggle: () -> Unit) {
    val pop by animateFloatAsState(if (fav) 1f else 0f, spring(0.35f, Spring.StiffnessMediumLow), label = "fav")
    IconToggleButton(fav, { onToggle() }) {
        Icon(
            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.cd_unfavorite else R.string.cd_favorite),
            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { val s = 1f + 0.25f * pop * (1f - pop) * 4f; scaleX = s; scaleY = s },
        )
    }
}

@Composable
private fun CompactHeader(info: TrackInfo, artModifier: Modifier) {
    val c = LocalContainer.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = PlayerGutter, end = PlayerGutter - IconInset, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(artModifier.size(64.dp)) { Artwork(info.album.id, info.album.colors, Modifier.size(64.dp), RoundedCornerShape(14.dp), coverUrl = info.album.cover) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            val text = rememberPlayerTextColors()
            Text(info.track.title, style = MaterialTheme.typography.titleMedium, color = text.title, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(info.artist.name, style = MaterialTheme.typography.bodyMedium, color = text.artist, maxLines = 1)
        }
        val fav = lib.isFavorite(info.id, c.repository.favoriteTrackIds)
        FavoriteButton(fav) {
            val next = !fav
            c.library.update { it.toggleFavorite(info.id, c.settings.state.value.addFavoritesToLibrary, c.repository.favoriteTrackIds) }
            c.scope.launch { c.repository.setFavorite(info.id, next) }
        }
    }
}

@Composable
private fun Controls(mode: PlayerMode, onMode: (PlayerMode) -> Unit, clock: androidx.compose.runtime.State<Long>, showVolume: Boolean) {
    val c = LocalContainer.current
    val p = c.player
    val state by p.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val settings by c.settings.state.collectAsStateWithLifecycle()
        // The neon bar's gradient ends in the cover's accent (same palette as the neon tube).
        val coverPalette = app.cadence.core.design.rememberCoverPalette(state.current?.album?.cover)
        val neonAccent = coverPalette?.let { androidx.compose.ui.graphics.Color(0xFF000000.toInt() or it.accent) }
        SeekBar(clock, state.durationMs, state.isPlaying, { p.seekTo(it) }, waveStyle = settings.waveStyle, neonAccent = neonAccent) {
            val f = state.format
            if (settings.showQuality && f != null) QualityBadge(f)
        }
        Spacer(Modifier.height(12.dp))
        TransportControls(state, p::togglePlay, p::previous, p::next, p::toggleShuffle, p::cycleRepeat)
        AnimatedVisibility(showVolume) { VolumeSlider(Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        PlayerToolbar(mode, onMode)
    }
}

@Composable
private fun PlayerToolbar(mode: PlayerMode, onMode: (PlayerMode) -> Unit) {
    val context = LocalContext.current
    val p = LocalContainer.current.player
    val state by p.state.collectAsStateWithLifecycle()
    var sleepMenu by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(expanded = true) {
        IconToggleButton(mode == PlayerMode.Lyrics, { onMode(if (it) PlayerMode.Lyrics else PlayerMode.Artwork) }) {
            Icon(Icons.Rounded.Lyrics, stringResource(R.string.player_lyrics))
        }
        IconButton(onClick = {
            val shown = Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(context).showSystemOutputSwitcher()
            if (!shown) context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Icon(Icons.Rounded.SpeakerGroup, stringResource(R.string.player_output_device)) }
        Box {
            IconToggleButton(state.sleepAtMs != null, { sleepMenu = true }) {
                Icon(if (state.sleepAtMs != null) Icons.Rounded.Bedtime else Icons.Outlined.Bedtime, stringResource(R.string.player_sleep_timer))
            }
            SleepMenu(sleepMenu, state.sleepAtMs, { sleepMenu = false }) { p.sleepIn(it) }
        }
        IconToggleButton(mode == PlayerMode.Queue, { onMode(if (it) PlayerMode.Queue else PlayerMode.Artwork) }) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue))
        }
    }
}

@Composable
private fun SleepMenu(open: Boolean, sleepAt: Long?, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(open, sleepAt) { while (open && sleepAt != null) { now = System.currentTimeMillis(); delay(1_000) } }
    DropdownMenu(open, onDismiss, shape = RoundedCornerShape(16.dp)) {
        if (sleepAt != null) {
            val left = ((sleepAt - now) / 1000).coerceAtLeast(0)
            Text(
                stringResource(R.string.player_sleep_countdown, left / 60, left % 60),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(180.dp),
            )
            DropdownMenuItem({ Text(stringResource(R.string.player_sleep_off)) }, { onPick(null); onDismiss() })
        }
        DropdownMenuItem({ Text(stringResource(R.string.player_sleep_end_of_song)) }, { onPick(0); onDismiss() })
        listOf(5, 15, 30, 60).forEach { m -> DropdownMenuItem({ Text(pluralStringResource(R.plurals.minutes, m, m)) }, { onPick(m); onDismiss() }) }
    }
}
