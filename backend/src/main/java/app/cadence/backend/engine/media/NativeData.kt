package app.cadence.backend.engine.media

import android.content.Context
import java.io.File

object NativeData {
    /**
     * Never seeded: the Apple libraries provision these on the device, for this device's own id
     * (CoreADI writes `adi.pb` during the first signed request; FairPlay writes its state on first use).
     * A copy made for another device would only be set aside by the native identity check.
     */
    private val DEVICE_BOUND = setOf("adi.pb", "ANDROID_ID", "fsi.pdat", "IC-Info.sids")

    /** Seeds the account state used by the Apple libraries. Debug builds only. */
    fun seed(context: Context) {
        val assets = context.assets
        val root = context.filesDir
        val names = runCatching { assets.list("native-data")?.toList().orEmpty() }.getOrDefault(emptyList())
        if (names.isEmpty()) {
            AppLog.log("NativeData", "no bundled native-data")
            return
        }
        var copied = 0
        val skipped = names.filter { it in DEVICE_BOUND }
        if (skipped.isNotEmpty()) AppLog.log("NativeData", "not seeding device-bound ${skipped.joinToString()}")
        for (name in names - DEVICE_BOUND) copy(assets, "native-data/$name", File(root, name), name) { copied++ }
        AppLog.log("NativeData", "seeded $copied files into ${root.absolutePath}")
    }

    private fun copy(assets: android.content.res.AssetManager, assetPath: String, dest: File, name: String, onCopied: () -> Unit) {
        val children = runCatching { assets.list(assetPath)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (children.isNotEmpty()) {
            dest.mkdirs()
            for (child in children) copy(assets, "$assetPath/$child", File(dest, child), child, onCopied)
            return
        }
        if (dest.exists()) return
        dest.parentFile?.mkdirs()
        assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        AppLog.log("NativeData", "copied $name ${dest.length()}")
        onCopied()
    }
}
