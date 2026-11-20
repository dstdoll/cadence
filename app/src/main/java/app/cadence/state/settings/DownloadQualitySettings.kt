package app.cadence.state.settings

import app.cadence.backend.engine.media.QualityPolicy
import app.cadence.backend.engine.media.RenditionSpec

const val QUALITY_MODE_ASK = "ask"
const val QUALITY_MODE_DEFAULT = "default"

/** Whether downloads ask which quality to use; anything but "default" asks (a corrupt value must not skip the question). */
val UserSettings.asksDownloadQuality: Boolean get() = downloadQualityMode != QUALITY_MODE_DEFAULT

/**
 * The quality a download uses when nobody picks one: the saved default, or, when none was saved yet, what the
 * older format, bit depth and sample rate settings meant, so an upgrade changes nothing.
 */
fun UserSettings.downloadPolicy(): QualityPolicy {
    val saved = RenditionSpec.parse(downloadQuality)
    return if (saved != null) QualityPolicy(saved, downloadAtmos) else QualityPolicy.fromLegacy(downloadCodec, bitDepth, maxSampleRate, downloadAtmos)
}
