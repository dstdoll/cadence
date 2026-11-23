package app.cadence.feature.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.cadence.backend.engine.CadenceLog

/**
 * Per-app language selection. Stored by the system [LocaleManager] on Android 13+ (API 33+)
 * and by [AppCompatDelegate] on older releases. [System] follows the device language.
 */
enum class LanguageOption(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
    Chinese("zh-CN"),
}

private fun optionFromLanguageTag(lang: String?): LanguageOption = when (lang) {
    "es" -> LanguageOption.Spanish
    "en" -> LanguageOption.English
    "zh" -> LanguageOption.Chinese
    else -> LanguageOption.System
}

/** The language currently applied to the app. */
fun currentLanguageOption(context: Context? = null): LanguageOption {
    if (Build.VERSION.SDK_INT >= 33 && context != null) {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales != null) {
            val lang = if (locales.isEmpty) null else locales[0]?.language
            return optionFromLanguageTag(lang)
        }
    }
    val locales = AppCompatDelegate.getApplicationLocales()
    val lang = if (locales.isEmpty) null else locales[0]?.language
    return optionFromLanguageTag(lang)
}

/** Apply a language. Empty tag clears the override (back to the system language) and recreates the activity. */
fun applyLanguage(context: Context, option: LanguageOption) {
    CadenceLog.i("language", "apply option=${option.name} tag=${option.tag} sdk=${Build.VERSION.SDK_INT}")
    if (Build.VERSION.SDK_INT >= 33) {
        val lm = context.getSystemService(LocaleManager::class.java)
        if (lm != null) {
            lm.applicationLocales = if (option.tag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(option.tag)
            }
            return
        }
    }
    AppCompatDelegate.setApplicationLocales(
        if (option.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(option.tag),
    )
    context.findActivity()?.recreate()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
