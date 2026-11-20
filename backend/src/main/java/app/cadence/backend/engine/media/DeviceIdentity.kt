package app.cadence.backend.engine.media

import android.content.Context
import android.provider.Settings
import java.io.File
import java.security.SecureRandom

/**
 * The id the Apple libraries are configured with, and the one way to change it.
 *
 * It is normally the platform's `ANDROID_ID`, which for one app is fixed for as long as its signing
 * key is: reinstalling does not change it. Everything the libraries provision -- the machine data,
 * the FairPlay state, the device GUID that travels with every key request -- is bound to it. So when
 * the key service starts refusing that identity (the shared key is still delivered, every track key
 * is rejected, and the same account plays elsewhere), nothing on the device can recover: the same id
 * comes back on every start.
 *
 * [rotate] writes a replacement id next to the app's other state. From the next start it is what the
 * native layer is given instead, and since it differs from the recorded one, the existing startup
 * logic sets the old provisioning aside and the libraries provision the device again, as they do for
 * a session imported from another phone.
 *
 * The id is never logged; [describe] gives what a log needs.
 */
object DeviceIdentity {
    /** Holds the replacement id, when there is one. Lives in the app's private files directory. */
    const val OVERRIDE_FILE = "DEVICE_ID_OVERRIDE"

    /** The replacement id if one was set and is usable, otherwise the platform's, otherwise "unknown". */
    fun id(context: Context): String =
        override(context.filesDir)
            ?: runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
            ?: "unknown"

    /** Whether a replacement id is in effect. */
    fun isRotated(context: Context): Boolean = override(context.filesDir) != null

    /** Sets a fresh random replacement id. It takes effect on the next start of the native layer. */
    fun rotate(context: Context): Boolean = rotate(context.filesDir, SecureRandom())

    /** Goes back to the platform's id on the next start. */
    fun clear(context: Context): Boolean = File(context.filesDir, OVERRIDE_FILE).let { !it.exists() || it.delete() }

    /** For logs: says an id is there and how long it is, never what it is. */
    fun describe(id: String): String = if (usable(id) == null) "none" else "len=${id.length}"

    internal fun override(filesDir: File): String? =
        runCatching { File(filesDir, OVERRIDE_FILE).readText() }.getOrNull()?.let(::usable)

    internal fun rotate(filesDir: File, random: SecureRandom): Boolean = runCatching {
        val bytes = ByteArray(8).also(random::nextBytes)
        File(filesDir, OVERRIDE_FILE).writeText(bytes.joinToString("") { "%02x".format(it) })
        true
    }.getOrDefault(false)

    /**
     * An id as the platform reports one: 1 to 16 hex digits, lower-cased. The native layer applies the
     * same rule (`identity::usable_id`), so an id that passes here is the id it will configure.
     */
    internal fun usable(raw: String): String? {
        val id = raw.trim().lowercase()
        return id.takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}
