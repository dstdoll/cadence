package app.cadence.backend.engine.media

import android.content.Context
import android.provider.Settings

object NativeRuntime {
    @Volatile var ready: Boolean = false
        private set
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        Thread {
            runCatching {
                NativeData.seed(context)
                val deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID,
                ) ?: "unknown"
                AppLog.log("Native", "auto init base=${context.filesDir.absolutePath} device=$deviceId")
                ready = NativeWrapper.init(context.filesDir.absolutePath, deviceId, ProcessNetwork.activeDnsServers(context))
                AppLog.log("Native", "auto init result=$ready")
            }.onFailure { AppLog.error("Native", "auto init failed", it) }
        }.start()
    }
}
