package app.cadence.backend.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Paces every amp-api call so the app cannot burst past what Apple tolerates.
 *
 * Apple publishes no limit and sends no rate-limit headers; the only signals are a 429 and its
 * `Retry-After`. The defaults sit inside what was measured against the live API (30 back-to-back
 * calls at 4.2/s, no throttling): 4 requests per second sustained with a burst of 8. A 429 pauses
 * all traffic for the time Apple asks, doubling on repeats, instead of retrying into the limit.
 */
class AmpGate(
    private val perSecond: Double = 4.0,
    private val burst: Int = 8,
    // Monotonic: a wall clock stepped back by NTP or by hand would otherwise drain the bucket or
    // stretch a pause by the size of the step, holding every caller for that long.
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var tokens = burst.toDouble()
    private var refilledAt = nowMs()

    /** Guards the throttle state below: responses update it from any thread, outside [lock]. */
    private val state = Any()
    private var pausedUntil = Long.MIN_VALUE
    private var penaltyMs = 0L
    /** Bumped each time the penalty grows, so answers to requests sent before that can be told apart. */
    private var epoch = 0L

    val granted = AtomicLong()
    val throttled = AtomicLong()

    /**
     * Suspends until a request may be sent. Callers are served in arrival order. Returns the
     * throttle epoch the request is sent in; pass it back to [throttled] or [succeeded].
     */
    suspend fun acquire(): Long = lock.withLock {
        var sentIn = 0L
        while (true) {
            val now = nowMs()
            val (until, current) = synchronized(state) { pausedUntil to epoch }
            if (now < until) { sleep(until - now); continue }
            tokens = minOf(burst.toDouble(), tokens + (now - refilledAt).coerceAtLeast(0L) * perSecond / 1000.0)
            refilledAt = now
            if (tokens >= 1.0) { tokens -= 1.0; sentIn = current; break }
            sleep(((1.0 - tokens) * 1000.0 / perSecond).toLong().coerceAtLeast(1))
        }
        granted.incrementAndGet()
        sentIn
    }

    /**
     * Apple answered 429. Holds every caller for `Retry-After`, or 2 s doubling up to 60 s.
     * [sentIn] is what [acquire] returned for the rejected request. Requests that were already in
     * flight when the pause began are rejected by the same window, so they extend the pause but do
     * not double it again: a burst of eight 429s is one throttle, not a jump straight to 60 s.
     */
    fun throttled(retryAfterSeconds: Int?, sentIn: Long? = null): Long = synchronized(state) {
        throttled.incrementAndGet()
        if (sentIn == null || sentIn >= epoch) {
            penaltyMs = if (penaltyMs == 0L) 2_000 else (penaltyMs * 2).coerceAtMost(60_000)
            epoch++
        }
        val wait = retryAfterSeconds?.takeIf { it > 0 }?.times(1000L)?.coerceAtMost(120_000) ?: penaltyMs
        pausedUntil = maxOf(pausedUntil, nowMs() + wait)
        wait
    }

    /**
     * A request went through: the next 429 starts again from the shortest pause. An answer to a
     * request sent before the latest pause began says nothing about the limit now and is ignored.
     */
    fun succeeded(sentIn: Long? = null) {
        synchronized(state) { if (sentIn == null || sentIn >= epoch) penaltyMs = 0L }
    }

}

/**
 * Short-lived response cache keyed by URL. Catalogue responses declare `max-age=60` (404s too),
 * so repeating one within a minute is pure waste; subscriber data is `no-store` and never lands here.
 */
class AmpResponseCache(
    private val maxEntries: Int = 256,
    // Monotonic, so a wall clock stepped back cannot keep entries past their max-age.
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    class Entry(val code: Int, val body: String, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    val hits = AtomicLong()

    fun get(url: String): Entry? {
        val entry = entries[url] ?: return null
        if (nowMs() >= entry.expiresAt) { entries.remove(url, entry); return null }
        hits.incrementAndGet()
        return entry
    }

    fun put(url: String, code: Int, body: String, ttlMs: Long) {
        if (ttlMs <= 0) return
        if (entries.size >= maxEntries) {
            val now = nowMs()
            entries.entries.removeIf { it.value.expiresAt <= now }
            // Still full: drop the entries closest to expiry.
            if (entries.size >= maxEntries) {
                entries.entries.sortedBy { it.value.expiresAt }.take(maxEntries / 4).forEach { entries.remove(it.key) }
            }
        }
        entries[url] = Entry(code, body, nowMs() + ttlMs)
    }

    fun clear() = entries.clear()
}
