package app.cadence.core.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.cadence.R
import app.cadence.state.settings.ColorSource
import app.cadence.state.settings.ContrastLevel
import app.cadence.state.settings.DarkMode
import app.cadence.state.settings.UserSettings
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.toDynamicScheme
import com.materialkolor.toColorScheme

/** Roboto Flex, one variable file, instantiated at the weights the UI uses. */
val Flex: FontFamily = FontFamily(
    (100..900 step 100).map { w ->
        Font(R.font.roboto_flex, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val base = Typography()
private val CadenceTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Flex, fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = Flex, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Flex, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Flex, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Flex),
    bodyMedium = base.bodyMedium.copy(fontFamily = Flex),
    bodySmall = base.bodySmall.copy(fontFamily = Flex),
    labelLarge = base.labelLarge.copy(fontFamily = Flex, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Flex, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = Flex, fontWeight = FontWeight.Medium),
)

/** Whether the active theme is monochrome; artwork and accents read this to drop color. */
val LocalMonochrome = staticCompositionLocalOf { false }
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun isDark(settings: UserSettings) = when (settings.darkMode) {
    DarkMode.System -> isSystemInDarkTheme()
    DarkMode.Light -> false
    DarkMode.Dark -> true
}

/**
 * The system draws the start screen before the app runs, so it only knows the system's mode. Telling
 * it the app's own choice (kept by the system across launches) makes the start screen open in the
 * same light or dark mode as the app. Setting the value it already has changes nothing.
 */
private fun applyStartScreenMode(context: android.content.Context, mode: DarkMode) {
    val ui = context.getSystemService(android.app.UiModeManager::class.java) ?: return
    val night = when (mode) {
        DarkMode.System -> android.app.UiModeManager.MODE_NIGHT_AUTO
        DarkMode.Light -> android.app.UiModeManager.MODE_NIGHT_NO
        DarkMode.Dark -> android.app.UiModeManager.MODE_NIGHT_YES
    }
    // setApplicationNightMode is Android 12+; before that the AppCompat default is the closest thing.
    if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { ui.setApplicationNightMode(night) }
    else androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            DarkMode.System -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            DarkMode.Light -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            DarkMode.Dark -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        },
    )
}

@Composable
fun CadenceTheme(settings: UserSettings, artworkSeed: Color?, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(settings.darkMode) { applyStartScreenMode(context, settings.darkMode) }
    val contrast = when (settings.contrast) {
        ContrastLevel.Standard -> 0.0
        ContrastLevel.Medium -> 0.5
        ContrastLevel.High -> 1.0
    }
    val target = remember(settings.colorSource, settings.customSeed, settings.pureBlack, contrast, dark, artworkSeed) {
        fun seeded(seed: Color, style: PaletteStyle) =
            seed.toDynamicScheme(dark, style, contrast).toColorScheme(dark && settings.pureBlack) { it }
        when (ApiCompat.effectiveColorSource(settings.colorSource)) {
            ColorSource.Wallpaper ->
                // effectiveColorSource already maps this to Artwork before Android 12; the explicit check keeps the API call guarded.
                (if (!ApiCompat.supportsWallpaperColors()) seeded(artworkSeed ?: Color(settings.customSeed), PaletteStyle.Content)
                else if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
                    .let { if (dark && settings.pureBlack) it.pureBlack() else it }
            ColorSource.Artwork -> seeded(artworkSeed ?: Color(settings.customSeed), PaletteStyle.Content)
            ColorSource.Custom -> seeded(Color(settings.customSeed), PaletteStyle.TonalSpot)
            ColorSource.Monochrome -> seeded(Color.Gray, PaletteStyle.Monochrome).grayscaleErrors()
                .let { if (dark && settings.pureBlack) it.pureBlack() else it }
        }
    }
    val scheme = animatedScheme(target, enabled = !settings.reduceMotion)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMonochrome provides (settings.colorSource == ColorSource.Monochrome || settings.grayscaleArtwork),
        LocalReduceMotion provides settings.reduceMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = if (settings.reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
            typography = CadenceTypography,
            content = content,
        )
    }
}

/** Cross-fades between schemes so album-art theming glides when the track changes. */
@Composable
private fun animatedScheme(target: ColorScheme, enabled: Boolean): ColorScheme {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = from.lerp(to, t.value)
        to = target
        if (!enabled) { t.snapTo(1f); return@LaunchedEffect }
        t.snapTo(0f)
        t.animateTo(1f, tween(650))
    }
    return if (t.value >= 1f) to else from.lerp(to, t.value)
}

private fun ColorScheme.lerp(b: ColorScheme, f: Float): ColorScheme = b.copy(
    primary = lerp(primary, b.primary, f), onPrimary = lerp(onPrimary, b.onPrimary, f),
    primaryContainer = lerp(primaryContainer, b.primaryContainer, f), onPrimaryContainer = lerp(onPrimaryContainer, b.onPrimaryContainer, f),
    inversePrimary = lerp(inversePrimary, b.inversePrimary, f),
    secondary = lerp(secondary, b.secondary, f), onSecondary = lerp(onSecondary, b.onSecondary, f),
    secondaryContainer = lerp(secondaryContainer, b.secondaryContainer, f), onSecondaryContainer = lerp(onSecondaryContainer, b.onSecondaryContainer, f),
    tertiary = lerp(tertiary, b.tertiary, f), onTertiary = lerp(onTertiary, b.onTertiary, f),
    tertiaryContainer = lerp(tertiaryContainer, b.tertiaryContainer, f), onTertiaryContainer = lerp(onTertiaryContainer, b.onTertiaryContainer, f),
    background = lerp(background, b.background, f), onBackground = lerp(onBackground, b.onBackground, f),
    surface = lerp(surface, b.surface, f), onSurface = lerp(onSurface, b.onSurface, f),
    surfaceVariant = lerp(surfaceVariant, b.surfaceVariant, f), onSurfaceVariant = lerp(onSurfaceVariant, b.onSurfaceVariant, f),
    surfaceTint = lerp(surfaceTint, b.surfaceTint, f),
    inverseSurface = lerp(inverseSurface, b.inverseSurface, f), inverseOnSurface = lerp(inverseOnSurface, b.inverseOnSurface, f),
    outline = lerp(outline, b.outline, f), outlineVariant = lerp(outlineVariant, b.outlineVariant, f),
    surfaceBright = lerp(surfaceBright, b.surfaceBright, f), surfaceDim = lerp(surfaceDim, b.surfaceDim, f),
    surfaceContainer = lerp(surfaceContainer, b.surfaceContainer, f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, b.surfaceContainerHigh, f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, b.surfaceContainerHighest, f),
    surfaceContainerLow = lerp(surfaceContainerLow, b.surfaceContainerLow, f),
    surfaceContainerLowest = lerp(surfaceContainerLowest, b.surfaceContainerLowest, f),
)

private fun ColorScheme.pureBlack() = copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

/** The monochrome palette keeps a red error role by default; make it strictly black & white. */
private fun ColorScheme.grayscaleErrors() = copy(
    error = onSurface, onError = surface, errorContainer = surfaceContainerHighest, onErrorContainer = onSurface,
)
