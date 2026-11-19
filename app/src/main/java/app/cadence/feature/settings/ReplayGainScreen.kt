package app.cadence.feature.settings

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.ListScaffold
import app.cadence.state.settings.ReplayGainConfig
import app.cadence.state.settings.RgMode

private const val RANGE_DB = 15f

/**
 * Replay gain / preamp: levels playback volume so songs don't jump in loudness, with a master preamp
 * and clip protection. Applied as the first sink stage (before tone and the equalizer), so it also
 * works on float/hi-res output. Apple's stream carries no per-track loudness, so today the
 * "info missing" preamp is the gain that applies; measured Track/Album gains slot in once a loudness
 * pass is added.
 */
@Composable
fun ReplayGainScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val rg = s.dspReplayGain

    fun edit(block: (ReplayGainConfig) -> ReplayGainConfig) = c.settings.update { it.copy(dspReplayGain = block(it.dspReplayGain)) }

    ListScaffold(title = stringResource(R.string.set_replay_gain), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "on") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_replay_gain), stringResource(R.string.set_rg_sub) + "\n\n" + stringResource(R.string.set_rg_note)) },
                trailingContent = { Switch(rg.enabled, { v -> edit { it.copy(enabled = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "mode") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.set_rg_reference), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RgMode.entries.forEach { m ->
                        FilterChip(
                            selected = rg.mode == m,
                            onClick = { edit { it.copy(mode = m) } },
                            enabled = rg.enabled,
                            label = { Text(stringResource(if (m == RgMode.Track) R.string.set_rg_per_track else R.string.set_rg_per_album)) },
                        )
                    }
                }
            }
        }

        item(key = "preamp") {
            GainSlider(stringResource(R.string.set_preamp), stringResource(R.string.set_preamp_sub), rg.preampDb, rg.enabled) { v -> edit { it.copy(preampDb = v) } }
        }
        item(key = "noinfo") {
            GainSlider(stringResource(R.string.set_preamp_missing), stringResource(R.string.set_preamp_missing_sub), rg.noInfoDb, rg.enabled) { v -> edit { it.copy(noInfoDb = v) } }
        }

        item(key = "clip") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_prevent_clipping), stringResource(R.string.set_prevent_clipping_sub)) },
                // Not tied to replay gain: the limiter also guards the equalizer and tone.
                trailingContent = { Switch(rg.preventClip, { v -> edit { it.copy(preventClip = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

    }
}

@Composable
private fun GainSlider(label: String, sub: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val state = remember { SliderState(value, 0, -RANGE_DB..RANGE_DB) }
    LaunchedEffect(value) { if (!state.isDragging) state.value = value }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("$label  %+.1f dB".format(value), style = MaterialTheme.typography.labelLarge)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(state = state, enabled = enabled, onValueChange = { v -> state.value = v; onChange(v) })
    }
}
