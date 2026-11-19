package app.cadence.feature.profile

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.ArtistAvatar
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.SectionHeader
import app.cadence.core.design.TileRow
import app.cadence.core.design.TrackRow

/**
 * Profile. Apple Music's profile is the public identity plus listening activity. This screen surfaces
 * the real data we already hold: the signed-in name/email (from social-profile), local counts, a
 * "Replay" of the most-played artists, and the listening history (promoted from the transient queue
 * view). Subscription/Notifications are placeholders.
 * BACKEND: social-profile followers/following, a real play-history endpoint, subscription/account.
 */
@Composable
fun ProfileScreen() {
    val c = LocalContainer.current
    val repo = c.repository
    val nav = LocalNavigator.current
    val lib by c.library.state.collectAsStateWithLifecycle()
    val session by c.backend.auth.session.collectAsStateWithLifecycle()

    val name = session?.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_you)
    val email = session?.email.orEmpty()
    val history = lib.recentTracks.mapNotNull(repo::track)
    // Replay: the artists you play most, from local history (a local stand-in for Apple's Replay).
    val topArtists = history.groupingBy { it.artist.id }.eachCount().entries
        .sortedByDescending { it.value }.mapNotNull { repo.artist(it.key) }.take(10)
    val totalMinutes = (history.sumOf { it.track.durationMs } / 60_000).toInt()

    val historyTitle = stringResource(R.string.ctx_history)
    ListScaffold(title = stringResource(R.string.profile_title), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()))
                    Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (email.isNotBlank()) Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.profile_stat_playlists), lib.playlists.size, Modifier.weight(1f))
                Stat(stringResource(R.string.cd_following), lib.followedArtists.size, Modifier.weight(1f))
                Stat(stringResource(R.string.profile_stat_minutes), totalMinutes, Modifier.weight(1f))
            }
        }
        if (topArtists.isNotEmpty()) {
            item(key = "replay-h") { SectionHeader(stringResource(R.string.profile_replay)) }
            item(key = "replay") { TileRow(topArtists, { it.id }) { a -> ArtistAvatar(a) { nav.artist(a.id) } } }
        }
        item(key = "hist-h") { SectionHeader(stringResource(R.string.profile_history)) }
        if (history.isEmpty()) {
            item(key = "hist-empty") {
                Text(stringResource(R.string.profile_history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        } else {
            itemsIndexed(history.take(30), key = { _, t -> "h" + t.id }) { i, t ->
                TrackRow(t, onClick = { c.player.play(history, i, contextTitle = historyTitle) })
            }
            item(key = "hist-clear") {
                TextButton(onClick = { c.library.update { it.clearHistory() } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.profile_clear_history)) }
            }
        }
        item(key = "acct-h") { SectionHeader(stringResource(R.string.profile_account)) }
        item(key = "sub") {
            ListItem(
                content = { Text(stringResource(R.string.profile_subscription)) },
                supportingContent = { Text(stringResource(R.string.profile_subscription_plan)) },
                leadingContent = { Icon(Icons.Rounded.CreditCard, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item(key = "notif") {
            ListItem(
                content = { Text(stringResource(R.string.profile_notifications)) },
                supportingContent = { Text(stringResource(R.string.profile_notifications_sub)) },
                leadingContent = { Icon(Icons.Rounded.Notifications, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
