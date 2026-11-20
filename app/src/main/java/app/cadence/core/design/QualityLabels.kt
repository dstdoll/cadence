package app.cadence.core.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.cadence.R
import app.cadence.backend.engine.media.QualityFamily
import app.cadence.backend.engine.media.RenditionSpec
import app.cadence.backend.model.AudioQuality

/**
 * The words for a quality: "Hi-Res Lossless · 24-bit / 96 kHz", "Lossless · 24-bit / 44.1 kHz", "AAC · 256 kbps",
 * "Data saver · HE-AAC 64 kbps". Hi-Res is lossless above 48 kHz, as everywhere else in the app
 * ([AudioQuality.tier]). A lossless spec with no rate limit is "Highest available".
 */
@Composable
fun qualityLabel(spec: RenditionSpec): String = when (spec.family) {
    QualityFamily.LOSSLESS -> when {
        spec.sampleRate == 0 -> stringResource(R.string.quality_best)
        spec.sampleRate > 48_000 -> stringResource(R.string.quality_hires_fmt, spec.bitDepth, AudioQuality.khz(spec.sampleRate))
        else -> stringResource(R.string.quality_lossless_fmt, spec.bitDepth, AudioQuality.khz(spec.sampleRate))
    }
    QualityFamily.AAC_LC -> stringResource(R.string.quality_aac_fmt, spec.kbps)
    QualityFamily.HE_AAC -> stringResource(R.string.quality_he_fmt, spec.kbps)
    QualityFamily.ATMOS -> stringResource(R.string.set_dolby_atmos)
}

/** The one-line explanation under a rung, or null when the label says it all. */
@Composable
fun qualityHint(spec: RenditionSpec): String? =
    if (spec.family == QualityFamily.LOSSLESS && spec.sampleRate == 0) stringResource(R.string.quality_best_hint) else null

/** The words for a recorded quality string (`alac:24:96000`, `aac:128`, `atmos:768`, `spatial`, or "" when it was not recorded). */
@Composable
fun qualityLabelForSpec(spec: String): String = when {
    spec == "spatial" -> stringResource(R.string.dq_spatial)
    spec.startsWith("atmos:") -> stringResource(R.string.dq_atmos, spec.substringAfter(':').toIntOrNull() ?: 0)
    else -> RenditionSpec.parse(spec)?.let { qualityLabel(it) } ?: stringResource(R.string.dq_quality_unknown)
}
