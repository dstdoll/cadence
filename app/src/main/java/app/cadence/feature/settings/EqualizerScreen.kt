package app.cadence.feature.settings

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import app.cadence.state.settings.AutoEq
import app.cadence.state.settings.AutoEqFormatException
import app.cadence.state.settings.HeadphoneProfile
import app.cadence.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalSlider
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
import app.cadence.state.settings.EqConfig

private const val RANGE_DB = 12f

/**
 * The custom DSP equalizer: a preamp plus a 10-band graphic curve, applied at the sink so it also
 * shapes float/hi-res output (unlike the device's system equalizer, which is bypassed there and
 * clashes with bit-perfect). Editing a band turns the equalizer on.
 */
@Composable
fun EqualizerScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val eq = s.dspEq

    fun edit(block: (EqConfig) -> EqConfig) = c.settings.update { it.copy(dspEq = block(it.dspEq)) }

    ListScaffold(title = stringResource(R.string.set_equalizer), showBack = true, showSettings = false, largeTitle = false) {
        item(key = "on") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_equalizer), stringResource(R.string.set_eq_sub) + "\n\n" + stringResource(R.string.set_eq_note, RANGE_DB.toString())) },
                trailingContent = { Switch(eq.enabled, { v -> edit { it.copy(enabled = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "preamp") {
            val state = remember { SliderState(eq.preampDb, 0, -RANGE_DB..RANGE_DB) }
            LaunchedEffect(eq.preampDb) { if (!state.isDragging) state.value = eq.preampDb }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.set_preamp) + "  %+.1f dB".format(eq.preampDb), style = MaterialTheme.typography.labelLarge)
                Slider(
                    state = state,
                    onValueChange = { v -> state.value = v; edit { it.copy(preampDb = v) } },
                )
            }
        }

        item(key = "headroom") {
            ListItem(
                content = { TitleInfo(stringResource(R.string.set_auto_headroom), stringResource(R.string.set_auto_headroom_sub)) },
                trailingContent = { Switch(eq.autoHeadroom, { v -> edit { it.copy(autoHeadroom = v) } }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        item(key = "headphone") {
            HeadphoneRow(
                profile = eq.headphone,
                onLoad = { p -> edit { it.copy(headphone = p, enabled = true) } },
                onRemove = { edit { it.copy(headphone = null) } },
            )
        }

        item(key = "bands-h") {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.set_bands)) }
                TextButton(onClick = { edit { it.copy(bands = EqConfig.defaultBands, preampDb = 0f) } }) { Text(stringResource(R.string.set_reset)) }
            }
        }

        item(key = "bands") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                eq.bands.forEachIndexed { i, band ->
                    val state = remember(i) { SliderState(band.gainDb, 0, -RANGE_DB..RANGE_DB) }
                    LaunchedEffect(band.gainDb) { if (!state.isDragging) state.value = band.gainDb }
                    val onChange: (Float) -> Unit = { v ->
                        state.value = v
                        edit { cfg ->
                            val bands = cfg.bands.toMutableList()
                            bands[i] = bands[i].copy(gainDb = v)
                            cfg.copy(bands = bands, enabled = true)
                        }
                    }
                    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%+.0f".format(band.gainDb), style = MaterialTheme.typography.labelMedium)
                        VerticalSlider(state = state, onValueChange = onChange, modifier = Modifier.height(220.dp).padding(vertical = 8.dp), enabled = eq.enabled)
                        Text(
                            if (band.freq >= 1000) "${band.freq / 1000}k" else "${band.freq}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

    }
}

/**
 * The AutoEQ headphone correction: shows the loaded profile and loads a `ParametricEQ.txt` picked with
 * the system file picker. A file that is not a usable profile leaves the current one in place and says why.
 */
@Composable
private fun HeadphoneRow(profile: HeadphoneProfile?, onLoad: (HeadphoneProfile) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = runCatching { readProfile(context, uri) }.fold(
            onSuccess = { onLoad(it); null },
            onFailure = { e -> autoEqError(resources, e) },
        )
    }
    val title = stringResource(R.string.set_autoeq)
    ListItem(
        content = { TitleInfo(title, stringResource(R.string.set_autoeq_info)) },
        supportingContent = {
            Column {
                Text(
                    profile?.let { pluralStringResource(R.plurals.set_autoeq_summary, it.filters.size, it.name, it.filters.size) }
                        ?: stringResource(R.string.set_autoeq_none),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = { Icon(Icons.Rounded.Headphones, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker.launch(arrayOf("text/plain", "text/*")) }) {
                    Text(stringResource(if (profile == null) R.string.set_autoeq_load else R.string.set_autoeq_change))
                }
                if (profile != null) {
                    IconButton(onClick = { error = null; onRemove() }) { Icon(Icons.Rounded.Close, stringResource(R.string.set_autoeq_remove)) }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { picker.launch(arrayOf("text/plain", "text/*")) },
    )
}

/** Reads and parses the picked file (at most 64 KiB; a real profile is under 1 KiB). */
private fun readProfile(context: Context, uri: Uri): HeadphoneProfile {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= 64 * 1024) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw AutoEqFormatException(AutoEqFormatException.Reason.Unreadable)
    if (bytes.size > 64 * 1024) throw AutoEqFormatException(AutoEqFormatException.Reason.Unreadable, "too large")
    return AutoEq.parse(bytes.toString(Charsets.UTF_8), name)
}

private fun autoEqError(resources: Resources, e: Throwable): String = when ((e as? AutoEqFormatException)?.reason) {
    AutoEqFormatException.Reason.NoFilters -> resources.getString(R.string.set_autoeq_err_nofilters)
    AutoEqFormatException.Reason.Unsupported -> resources.getString(R.string.set_autoeq_err_unsupported, e.detail)
    AutoEqFormatException.Reason.OutOfRange -> resources.getString(R.string.set_autoeq_err_range, e.detail)
    AutoEqFormatException.Reason.TooMany -> resources.getString(R.string.set_autoeq_err_toomany, e.detail)
    else -> resources.getString(R.string.set_autoeq_err_read)
}

