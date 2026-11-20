package app.cadence.backend.engine

/**
 * Something went wrong in the native layer and the user should know, in terms the app understands.
 * [kind] is the native class code (`KEY_REJECTED`, `LEASE_OR_ASSET`, `DECRYPT`, `SIGN_IN`,
 * `TOKEN_MINT`, `ENGINE_INIT`, `OUT_OF_MEMORY`, `PREVIOUS_CRASH`); [impact] is `track`, `session` or
 * `app`; [entry]/[detail] say where (`kd`, `adam=1595217258 key=c23`). The exact meaning of each
 * code, and what to do about it, is in native/APPLE_ERRORS.md.
 */
data class NativeNotice(val kind: String, val impact: String, val entry: String, val detail: String)

/**
 * Decides whether a notice is shown, so a failing playlist cannot bury the screen in toasts: the
 * same kind at most once per [perKindMs], and at most [globalMax] notices per [globalWindowMs].
 */
class NoticeLimiter(
    private val perKindMs: Long = 30_000L,
    private val globalWindowMs: Long = 60_000L,
    private val globalMax: Int = 3,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastByKind = HashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun allow(kind: String): Boolean {
        val t = now()
        while (recent.isNotEmpty() && t - recent.first() >= globalWindowMs) recent.removeFirst()
        val last = lastByKind[kind]
        if (last != null && t - last < perKindMs) return false
        if (recent.size >= globalMax) return false
        lastByKind[kind] = t
        recent.addLast(t)
        return true
    }
}

/**
 * The app registers a [listener] (a toast) once; the native layer and the crash-log import post here.
 * Every notice is also in the log, shown or not.
 */
object NativeNotices {
    @Volatile var listener: ((NativeNotice) -> Unit)? = null
    internal var limiter = NoticeLimiter()

    fun post(notice: NativeNotice) {
        val shown = limiter.allow(notice.kind)
        val target = listener
        CadenceLog.i("native", "notice ${notice.kind} impact=${notice.impact} entry=${notice.entry} ${notice.detail}".trim() +
            if (!shown) " (not shown: rate limit)" else if (target == null) " (not shown: no UI yet)" else "")
        if (notice.kind == "KEY_REJECTED") Provisioning.logOnKeyRejected()
        if (shown) target?.invoke(notice)
    }
}
