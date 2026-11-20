package app.cadence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.cadence.backend.engine.NativeCrashImport
import app.cadence.backend.engine.NativeNotice
import app.cadence.backend.engine.NativeNotices

/**
 * Tells the user when the native layer caught an error that affected them (a track skipped, a
 * sign-in failed) or when the previous run ended in a native crash. The notices come from
 * [NativeNotices] (rate limited there); every one of them is in `cadence.log` whether or not a toast
 * was shown. The native side's meaning for each code is in native/APPLE_ERRORS.md.
 */
object NativeNoticeToasts {
    private val main = Handler(Looper.getMainLooper())

    fun install(context: Context) {
        val app = context.applicationContext
        NativeNotices.listener = { notice ->
            val long = notice.impact != "track" // a skipped track is routine; the rest deserve a longer look
            main.post { Toast.makeText(app, text(app, notice), if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        }
        // Reads native-crash.log off the main thread; posts PREVIOUS_CRASH if the last run died natively.
        Thread({ NativeCrashImport.run(app) }, "cadence-native-crash-import").apply { isDaemon = true }.start()
    }

    internal fun text(context: Context, notice: NativeNotice): String = context.getString(
        when (notice.kind) {
            "KEY_REJECTED" -> R.string.native_notice_key_rejected
            "LEASE_OR_ASSET" -> R.string.native_notice_lease_or_asset
            "DECRYPT" -> R.string.native_notice_decrypt
            "SIGN_IN" -> R.string.native_notice_sign_in
            "TOKEN_MINT" -> R.string.native_notice_token_mint
            "ENGINE_INIT" -> R.string.native_notice_engine_init
            "OUT_OF_MEMORY" -> R.string.native_notice_out_of_memory
            "PREVIOUS_CRASH" -> R.string.native_notice_previous_crash
            else -> R.string.native_notice_unknown
        },
    )
}
