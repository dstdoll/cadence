package app.cadence.feature.settings

import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import app.cadence.core.design.ListScaffold
import app.cadence.state.settings.DitherType

/**
 * Dither for the 16-bit output path: it trades quantization *distortion* for a faint, benign noise
 * floor. It only does anything where audio is actually reduced to 16 bits (hi-res output off, or a
 * 16-bit DAC) — on a float path there is nothing to quantize, so the stage stays idle.
 */
@Composable
fun DitherScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val d = s.dspDither

    ListScaffold(title = stringResource(R.string.set_dither), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "on") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_dither), stringResource(R.string.set_dither_sub) + "\n\n" + stringResource(R.string.set_dither_note)) },
                trailingContent = { Switch(d.enabled, { v -> c.settings.update { it.copy(dspDither = it.dspDither.copy(enabled = v)) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "types") {
            androidx.compose.foundation.layout.Column {
                DitherType.entries.filter { it != DitherType.None }.forEach { type ->
                    fun pick() = c.settings.update { it.copy(dspDither = it.dspDither.copy(type = type)) }
                    ListItem(
                        content = { TitleInfo(stringResource(type.labelRes), describe(type)) },
                        trailingContent = { RadioButton(selected = d.type == type, enabled = d.enabled, onClick = { pick() }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = d.enabled) { pick() },
                    )
                }
            }
        }

    }
}

@Composable
private fun describe(type: DitherType): String = when (type) {
    DitherType.Rectangular -> stringResource(R.string.set_dither_desc_rect)
    DitherType.Triangular -> stringResource(R.string.set_dither_desc_tri)
    DitherType.Shaped -> stringResource(R.string.set_dither_desc_shaped)
    DitherType.None -> ""
}
