package app.cadence.backend.real

import app.cadence.backend.engine.CadenceLog
import org.json.JSONObject

/**
 * JNI surface of `libcadence_auth_android.so` (prebuilt; its core crate is not in this repo): Apple ID
 * SRP proof + HSA2 six-digit code, returning a media user token.
 */
object NativeAuth {
    @Volatile private var loaded: Boolean? = null

    fun available(): Boolean {
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("cadence_auth_android"); true }.getOrDefault(false)
        loaded = ok
        return ok
    }

    private external fun cadence_auth_start(email: String, password: String, storageDir: String): String?
    private external fun cadence_auth_submit(code: String): String?
    private external fun cadence_auth_resume(storageDir: String): String?
    private external fun cadence_auth_reset(storageDir: String)
    private external fun cadence_auth_free(pointer: Long)

    data class Result(
        val status: String,
        val mediaUserToken: String?,
        val storefront: String?,
        val kind: String?,
        val message: String?,
        /** "sms" when Apple will text a trusted number, "device" for a trusted-device prompt. */
        val delivery: String? = null,
        /** Trusted phone numbers Apple offered, as (id, numberWithDialCode). */
        val phones: List<Phone> = emptyList(),
    ) {
        data class Phone(val id: Int, val number: String)
    }

    private fun parse(raw: String?): Result {
        if (raw == null) return Result("error", null, null, "null", "auth library returned nothing")
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Result("error", null, null, "json", raw.take(120))
        val session = o.optJSONObject("session")
        val phones = ArrayList<Result.Phone>()
        o.optJSONArray("phones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                phones += Result.Phone(p.optInt("id"), p.optString("number"))
            }
        }
        return Result(
            status = o.optString("status", "error"),
            mediaUserToken = session?.optString("mediaUserToken")?.takeIf { it.isNotBlank() },
            storefront = session?.optString("storefront")?.takeIf { it.isNotBlank() },
            kind = o.optString("kind").takeIf { it.isNotBlank() },
            message = o.optString("message").takeIf { it.isNotBlank() },
            delivery = o.optString("delivery").takeIf { it.isNotBlank() },
            phones = phones,
        )
    }

    fun start(email: String, password: String, storageDir: String): Result {
        if (!available()) return Result("error", null, null, "unavailable", "auth library not loaded")
        return parse(runCatching { cadence_auth_start(email, password, storageDir) }
            .onFailure { CadenceLog.e("auth", "start threw", it) }.getOrNull())
    }

    fun submit(code: String): Result {
        if (!available()) return Result("error", null, null, "unavailable", "auth library not loaded")
        return parse(runCatching { cadence_auth_submit(code) }
            .onFailure { CadenceLog.e("auth", "submit threw", it) }.getOrNull())
    }

    fun resume(storageDir: String): Result {
        if (!available()) return Result("error", null, null, "unavailable", "auth library not loaded")
        return parse(runCatching { cadence_auth_resume(storageDir) }
            .onFailure { CadenceLog.e("auth", "resume threw", it) }.getOrNull())
    }

    fun reset(storageDir: String) { if (available()) runCatching { cadence_auth_reset(storageDir) } }
}
