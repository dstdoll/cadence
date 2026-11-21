package app.cadence.backend.engine.media

import android.content.Context

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
                val deviceId = DeviceIdentity.id(context)
                // The id is not logged: it identifies the device, and this log can be exported.
                AppLog.log("Native", "auto init base=${context.filesDir.absolutePath} device=<${DeviceIdentity.describe(deviceId)}${if (DeviceIdentity.isRotated(context)) " rotated" else ""}>")
                app.cadence.backend.engine.Provisioning.dir = context.filesDir
                val dnsServers = ProcessNetwork.activeDnsServers(context)
                ready = NativeWrapper.init(context.filesDir.absolutePath, deviceId, dnsServers)
                if (ready) ProcessNetwork.follow(context, dnsServers)
                app.cadence.backend.engine.Provisioning.log("after native init")
                AppLog.log("Native", "auto init result=$ready")
            }.onFailure { AppLog.error("Native", "auto init failed", it) }
        }.start()
    }
}
