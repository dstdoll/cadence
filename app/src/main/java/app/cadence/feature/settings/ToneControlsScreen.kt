package app.cadence.feature.settings

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.ListScaffold
import app.cadence.core.design.SectionHeader
import app.cadence.state.settings.ToneConfig

private const val RANGE_DB = 12f

/**
 * The bass/treble tone controls: two shelf filters applied at the sink (so they shape float/hi-res
 * output too), running before the equalizer. Moving a slider turns the controls on.
 */
@Composable
fun ToneControlsScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val tone = s.dspTone

    fun edit(block: (ToneConfig) -> ToneConfig) = c.settings.update { it.copy(dspTone = block(it.dspTone)) }

    ListScaffold(title = stringResource(R.string.set_tone_controls), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "on") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_tone_controls), stringResource(R.string.set_tone_sub) + "\n\n" + stringResource(R.string.set_tone_note, RANGE_DB.toString())) },
                trailingContent = { Switch(tone.enabled, { v -> edit { it.copy(enabled = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "reset") {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.set_shelves)) }
                TextButton(onClick = { edit { it.copy(bassDb = 0f, trebleDb = 0f) } }) { Text(stringResource(R.string.set_reset)) }
            }
        }

        item(key = "bass") {
            ToneSlider(stringResource(R.string.set_bass), stringResource(R.string.set_bass_sub), tone.bassDb, tone.enabled) { v -> edit { it.copy(bassDb = v, enabled = true) } }
        }
        item(key = "treble") {
            ToneSlider(stringResource(R.string.set_treble), stringResource(R.string.set_treble_sub), tone.trebleDb, tone.enabled) { v -> edit { it.copy(trebleDb = v, enabled = true) } }
        }

    }
}

@Composable
private fun ToneSlider(label: String, range: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val state = remember { SliderState(value, 0, -RANGE_DB..RANGE_DB) }
    LaunchedEffect(value) { if (!state.isDragging) state.value = value }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("$label  %+.1f dB".format(value), style = MaterialTheme.typography.labelLarge)
        Text(range, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(state = state, enabled = enabled, onValueChange = { v -> state.value = v; onChange(v) })
    }
}
