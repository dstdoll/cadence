package app.cadence.backend.engine.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Per-segment FairPlay key map for an HLS download.
 *
 * Apple's ALAC HLS playlists carry more than one `EXT-X-KEY`: a prefetch key
 * (`skd://itunes.apple.com/P000000000/s1/e1`, fetched with adamId "0") covers
 * the first segment, and the real content key covers the rest. Decrypting the
 * whole package with a single context silently corrupts the first segment, so
 * the key that was active for each segment is recorded here and replayed at
 * decrypt time.
 */
data class KeyPlan(val adamId: String, val segments: List<SegmentKey>) {
    data class SegmentKey(val keyUri: String, val adamId: String)

    fun toJson(): String = JSONObject()
        .put("adamId", adamId)
        .put(
            "segments",
            JSONArray().apply {
                segments.forEach {
                    put(JSONObject().put("keyUri", it.keyUri).put("adamId", it.adamId))
                }
            },
        )
        .toString()

    companion object {
        /** Apple uses the prefetch key when the asset id is 0. */
        fun isPrefetch(keyUri: String): Boolean = keyUri.contains("P000000000")

        fun adamIdFor(trackId: String, keyUri: String): String = if (isPrefetch(keyUri)) "0" else trackId

        fun parse(text: String): KeyPlan {
            val root = JSONObject(text)
            val array = root.getJSONArray("segments")
            val segments = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SegmentKey(item.getString("keyUri"), item.optString("adamId", "0"))
            }
            return KeyPlan(root.optString("adamId", ""), segments)
        }

        private fun dir(context: Context) = File(context.filesDir, "keyplans").apply { mkdirs() }

        private fun fileFor(context: Context, name: String) =
            File(dir(context), name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

        fun save(context: Context, name: String, plan: KeyPlan) {
            runCatching { fileFor(context, name).writeText(plan.toJson()) }
                .onFailure { AppLog.error("KeyPlan", "save failed", it) }
        }

        fun load(context: Context, name: String): KeyPlan? = runCatching {
            fileFor(context, name).takeIf { it.isFile }?.readText()?.let(::parse)
        }.getOrNull()
    }
}

/** Turns a KeyPlan into one KD handle per segment, reusing contexts per key. */
object KdResolver {
    /**
     * One exchange at a time: downloads and streaming playback both ask for keys. Waiting for it
     * is interruptible, so a caller that gives up (see [resolveWithTimeout]) leaves instead of
     * staying blocked; and a lock held past a caller's deadline is replaced, so a native call
     * that never returns cannot stop every later exchange from reaching the native side again.
     */
    private val exchangeLock = java.util.concurrent.atomic.AtomicReference(java.util.concurrent.locks.ReentrantLock())

    /** The native key exchange. Tests replace it; nothing else should. */
    @Volatile internal var exchange: (adamId: String, keyUri: String) -> Long = { adamId, keyUri -> NativeWrapper.kd(adamId, keyUri) }

    fun resolve(plan: KeyPlan): LongArray {
        val cache = HashMap<String, Long>()
        val handles = LongArray(plan.segments.size) { index ->
            val segment = plan.segments[index]
            cache.getOrPut("${segment.adamId}|${segment.keyUri}") {
                AppLog.log("Kd", "exchange adam=${segment.adamId} uri=${segment.keyUri}")
                val lock = exchangeLock.get()
                lock.lockInterruptibly()
                val handle = try { exchange(segment.adamId, segment.keyUri) } finally { lock.unlock() }
                AppLog.log("Kd", "handle=$handle for ${segment.keyUri}")
                handle
            }
        }
        val failed = handles.indexOfFirst { it == 0L }
        if (failed >= 0) throw IllegalStateException("Key exchange failed for segment ${failed + 1}")
        return handles
    }

    /**
     * Runs [resolve] on a daemon thread with a hard deadline.
     *
     * `NativeWrapper.kd` is a blocking JNI call. A stuck native KD call cannot be
     * cancelled by a coroutine timeout, so without this wrapper the download job
     * stays in "Fetching keys" forever and never reaches DrmService. On timeout we
     * abort the job with a real error; the abandoned native thread is a daemon, so
     * it cannot keep the process alive.
     */
    fun resolveWithTimeout(plan: KeyPlan, timeoutMs: Long = 45_000L): LongArray {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cadence-kd").apply { isDaemon = true }
        }
        val future = executor.submit(Callable { resolve(plan) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // Someone has held the exchange lock for this caller's whole deadline (this caller,
            // stuck in native, or whoever it was waiting behind). Later exchanges get a new lock.
            val held = exchangeLock.get()
            if (held.isLocked && exchangeLock.compareAndSet(held, java.util.concurrent.locks.ReentrantLock())) {
                AppLog.log("Kd", "exchange lock held past ${timeoutMs}ms; later exchanges use a new one")
            }
            AppLog.log("Kd", "timeout after ${timeoutMs}ms; native KD did not return")
            throw IllegalStateException("FairPlay key exchange timed out after ${timeoutMs}ms", e)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            executor.shutdownNow()
        }
    }
}

