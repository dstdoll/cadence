package app.cadence.state.settings

import kotlin.math.roundToInt

/** Why an AutoEQ file could not be loaded; [reason] is shown to the user as is. */
class AutoEqFormatException(val reason: Reason, val detail: String = "") : Exception("${reason.name} $detail") {
    enum class Reason { NoFilters, Unsupported, OutOfRange, TooMany, Unreadable }
}

/**
 * Reads AutoEQ's `ParametricEQ.txt` (the Equalizer APO format its site and repository publish for
 * each headphone model):
 *
 * ```
 * Preamp: -4.6 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 8.0 dB Q 0.70
 * Filter 2: ON PK Fc 5956 Hz Gain -4.6 dB Q 0.99
 * Filter 6: ON HSC Fc 10000 Hz Gain -3.8 dB Q 0.70
 * ```
 *
 * PK, LSC and HSC are the RBJ cookbook peaking, low-shelf and high-shelf filters, the same ones the
 * equalizer runs, so a loaded profile sounds as AutoEQ designed it. Filters marked OFF are skipped;
 * any other filter type is refused rather than silently dropped, since the curve would be wrong.
 */
object AutoEq {
    private val preamp = Regex("""^Preamp:\s*(-?\d+(?:[.,]\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val filter = Regex(
        """^Filter(?:\s+\d+)?:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:[.,]\d+)?)\s*dB\s+Q\s+(\d+(?:[.,]\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    /** Parses [text]; [fileName] (as picked) names the profile. Throws [AutoEqFormatException]. */
    fun parse(text: String, fileName: String?): HeadphoneProfile {
        var preampDb = 0f
        val filters = ArrayList<EqBandSetting>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            preamp.find(line)?.let { preampDb = num(it.groupValues[1]); continue }
            val m = filter.find(line)
            if (m == null) {
                // A filter line in a shape this reader does not know (another APO filter type).
                if (line.startsWith("Filter", ignoreCase = true)) throw AutoEqFormatException(AutoEqFormatException.Reason.Unsupported, line.take(60))
                continue
            }
            if (m.groupValues[1].equals("OFF", ignoreCase = true)) continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> EqFilterType.Peaking
                "LSC" -> EqFilterType.LowShelf
                "HSC" -> EqFilterType.HighShelf
                else -> throw AutoEqFormatException(AutoEqFormatException.Reason.Unsupported, m.groupValues[2])
            }
            val freq = num(m.groupValues[3])
            val gain = num(m.groupValues[4])
            val q = num(m.groupValues[5])
            if (freq !in 10f..24_000f || gain !in -30f..30f || q !in 0.05f..20f) {
                throw AutoEqFormatException(AutoEqFormatException.Reason.OutOfRange, line.take(60))
            }
            filters += EqBandSetting(freq.roundToInt(), gain, q, type)
        }
        if (filters.isEmpty()) throw AutoEqFormatException(AutoEqFormatException.Reason.NoFilters)
        if (filters.size > HeadphoneProfile.MAX_FILTERS) throw AutoEqFormatException(AutoEqFormatException.Reason.TooMany, filters.size.toString())
        return HeadphoneProfile(nameFrom(fileName), preampDb.coerceIn(-30f, 0f), filters)
    }

    /** "Beyerdynamic DT 770 Pro (80 Ohm) ParametricEQ.txt" -> "Beyerdynamic DT 770 Pro (80 Ohm)". */
    fun nameFrom(fileName: String?): String =
        fileName.orEmpty().substringAfterLast('/')
            .removeSuffix(".txt").removeSuffix(".TXT")
            .replace(Regex("""\s*ParametricEQ$""", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { "AutoEQ" }

    private fun num(s: String): Float = s.replace(',', '.').toFloat()
}
