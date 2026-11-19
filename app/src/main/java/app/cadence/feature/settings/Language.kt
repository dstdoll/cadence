package app.cadence.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language selection. The chosen locale is stored by the system (Android 13+) or AppCompat
 * (API < 33), not in our settings, so it also appears in the OS per-app language screen and survives
 * reinstalls of the preference. [System] follows the device language.
 */
enum class LanguageOption(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
}

/** The language currently applied to the app (reads AppCompat's stored locale list). */
fun currentLanguageOption(): LanguageOption {
    val locales = AppCompatDelegate.getApplicationLocales()
    val lang = if (locales.isEmpty) null else locales[0]?.language
    return when (lang) {
        "es" -> LanguageOption.Spanish
        "en" -> LanguageOption.English
        else -> LanguageOption.System
    }
}

/** Apply a language. Empty tag clears the override (back to the system language). Triggers a recreate. */
fun applyLanguage(option: LanguageOption) {
    AppCompatDelegate.setApplicationLocales(
        if (option.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(option.tag),
    )
}
