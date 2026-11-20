package app.cadence.feature.nowplaying

import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import app.cadence.backend.model.AudioFormatInfo
import app.cadence.backend.model.AudioQuality
import app.cadence.backend.model.QualityTier

/** Compact pill: "Hi-Res Lossless" / "Lossless" / codec name. Tap for the full breakdown. */
@Composable
fun QualityBadge(format: AudioFormatInfo, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    val tier = AudioQuality.tier(format)
    // Sized and typeset like the time labels it sits between: same label style, same 24dp row,
    // a soft tonal fill instead of an outline so it reads as part of the scrubber, not a sticker on it.
    Row(
        Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .clickable(onClickLabel = stringResource(R.string.quality_details)) { open = true }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (tier == QualityTier.Spatial) Icons.Rounded.SurroundSound else Icons.Rounded.GraphicEq,
            null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (tier == QualityTier.Lossy) format.codec else tierLabel(tier),
            style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        )
    }
    if (open) QualitySheet(format) { open = false }
}

@Composable
private fun QualitySheet(format: AudioFormatInfo, onDismiss: () -> Unit) {
    val tier = AudioQuality.tier(format)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(if (tier == QualityTier.Lossy) stringResource(R.string.quality_codec_audio, format.codec) else tier.label, style = MaterialTheme.typography.titleLarge)
                    Text(AudioQuality.spec(format), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                tierDescription(tier),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            HorizontalDivider()
            Spec(stringResource(R.string.quality_format), format.codec + if (AudioQuality.isLossless(format)) " " + stringResource(R.string.quality_lossless) else "")
            format.bitDepth?.let { Spec(stringResource(R.string.quality_bit_depth), stringResource(R.string.quality_bits, it)) }
            format.sampleRate?.let { Spec(stringResource(R.string.quality_sample_rate), AudioQuality.khz(it)) }
            AudioQuality.channels(format.channels)?.let { Spec(stringResource(R.string.quality_channels), it) }
            if (!AudioQuality.isLossless(format)) format.bitrate?.let { Spec(stringResource(R.string.quality_bitrate), stringResource(R.string.quality_kbps, (it + 500) / 1000)) }
            Text(
                stringResource(R.string.quality_resample_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Spec(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The tier's name in the app's language. */
@Composable
internal fun tierLabel(tier: QualityTier): String = stringResource(
    when (tier) {
        QualityTier.HiResLossless -> R.string.quality_tier_hires
        QualityTier.Lossless -> R.string.quality_tier_lossless
        QualityTier.Spatial -> R.string.quality_tier_spatial
        QualityTier.Lossy -> R.string.quality_tier_standard
    },
)

/** What the tier means, in the app's language. */
@Composable
internal fun tierDescription(tier: QualityTier): String = stringResource(
    when (tier) {
        QualityTier.HiResLossless -> R.string.quality_desc_hires
        QualityTier.Lossless -> R.string.quality_desc_lossless
        QualityTier.Spatial -> R.string.quality_desc_spatial
        QualityTier.Lossy -> R.string.quality_desc_lossy
    },
)
