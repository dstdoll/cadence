package app.cadence.core.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import app.cadence.state.settings.ColorSource

/**
 * The Android-version decisions in one place, as pure functions of the SDK level so they are unit-tested
 * and the call sites stay one-liners. The app supports Android 10 (API 29) and newer; what a feature needs
 * from a newer Android degrades instead of crashing.
 */
object ApiCompat {
    /** Material You wallpaper colours exist from Android 12 (API 31). */
    @ChecksSdkIntAtLeast(api = 31)
    fun supportsWallpaperColors(): Boolean = supportsWallpaperColors(Build.VERSION.SDK_INT)
    fun supportsWallpaperColors(sdk: Int) = sdk >= 31

    /** The colour source actually used: the wallpaper source falls back to the album-art source before Android 12. */
    fun effectiveColorSource(source: ColorSource, sdk: Int = Build.VERSION.SDK_INT): ColorSource =
        if (source == ColorSource.Wallpaper && !supportsWallpaperColors(sdk)) ColorSource.Artwork else source

    /** The colour sources the settings screen offers. */
    fun availableColorSources(sdk: Int = Build.VERSION.SDK_INT): List<ColorSource> =
        ColorSource.entries.filter { it != ColorSource.Wallpaper || supportsWallpaperColors(sdk) }

    /** `RenderEffect` (used for monochrome video/covers) exists from Android 12. */
    @ChecksSdkIntAtLeast(api = 31)
    fun supportsRenderEffect(): Boolean = supportsRenderEffect(Build.VERSION.SDK_INT)
    fun supportsRenderEffect(sdk: Int) = sdk >= 31

    /** Runtime notification permission exists from Android 13; before that notifications are on by default. */
    @ChecksSdkIntAtLeast(api = 33)
    fun needsNotificationPermission(): Boolean = needsNotificationPermission(Build.VERSION.SDK_INT)
    fun needsNotificationPermission(sdk: Int) = sdk >= 33

    /** `Context.getDisplay()` is public API from Android 11. */
    @ChecksSdkIntAtLeast(api = 30)
    fun hasPublicContextDisplay(): Boolean = hasPublicContextDisplay(Build.VERSION.SDK_INT)
    fun hasPublicContextDisplay(sdk: Int) = sdk >= 30
}
