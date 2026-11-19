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
import app.cadence.state.settings.ChannelConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stereo balance + width, applied at the sink after the equalizer (so they shape float/hi-res output
 * too). Balance pans the mix left/right; width goes from mono (0) through normal stereo (1) to a wider
 * image (2). Moving a slider turns the stage on. Stereo audio only.
 */
@Composable
fun BalanceScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val ch = s.dspChannel

    fun edit(block: (ChannelConfig) -> ChannelConfig) = c.settings.update { it.copy(dspChannel = block(it.dspChannel)) }

    ListScaffold(title = stringResource(R.string.set_balance_width), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "on") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_balance_width), stringResource(R.string.set_balance_sub) + "\n\n" + stringResource(R.string.set_balance_note)) },
                trailingContent = { Switch(ch.enabled, { v -> edit { it.copy(enabled = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "reset") {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.set_stereo_field)) }
                TextButton(onClick = { edit { it.copy(balance = 0f, width = 1f) } }) { Text(stringResource(R.string.set_reset)) }
            }
        }

        item(key = "balance") {
            SliderRow(stringResource(R.string.set_balance), balanceLabel(ch.balance), ch.balance, -1f..1f, ch.enabled) { v ->
                edit { it.copy(balance = v, enabled = true) }
            }
        }
        item(key = "width") {
            SliderRow(stringResource(R.string.set_width), widthLabel(ch.width), ch.width, 0f..2f, ch.enabled) { v ->
                edit { it.copy(width = v, enabled = true) }
            }
        }

    }
}

@Composable
private fun balanceLabel(v: Float): String = when {
    abs(v) < 0.005f -> stringResource(R.string.set_centre)
    v < 0 -> stringResource(R.string.set_pct_left, (abs(v) * 100).roundToInt())
    else -> stringResource(R.string.set_pct_right, (v * 100).roundToInt())
}

@Composable
private fun widthLabel(v: Float): String = when {
    v < 0.005f -> stringResource(R.string.set_mono)
    abs(v - 1f) < 0.005f -> stringResource(R.string.set_normal_stereo)
    else -> "%.2f×".format(v)
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val state = remember { SliderState(current, 0, range) }
    LaunchedEffect(current) { if (!state.isDragging) state.value = current }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("$label  $value", style = MaterialTheme.typography.labelLarge)
        Slider(state = state, enabled = enabled, onValueChange = { v -> state.value = v; onChange(v) })
    }
}
