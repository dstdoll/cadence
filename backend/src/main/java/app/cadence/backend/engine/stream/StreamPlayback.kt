package app.cadence.backend.engine.stream

import android.content.Context
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.media.EngineDownloader
import app.cadence.backend.engine.media.Http
import app.cadence.backend.engine.media.KdResolver
import app.cadence.backend.engine.media.KeyPlan
import app.cadence.backend.engine.media.NativeDrm
import app.cadence.backend.engine.media.Track
import app.cadence.backend.engine.media.WrapperKeyClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming playback of catalogue tracks: the app's handle on the decrypted stream cache.
 *
 * The catalogue only has Apple's protected HLS master for a track, which no player can read.
 * Playback therefore addresses tracks by id ([uriFor]); the player's data source resolves that
 * through [cache], which downloads, decrypts and stores the audio segment by segment.
 */
object StreamPlayback {
    const val SCHEME = "cadence-stream"
    private const val PREFIX = "$SCHEME://track/"

    /** The URI the player is given for a catalogue track. */
    fun uriFor(trackId: String): String = PREFIX + trackId

    /** The track id of a [uriFor] URI, or null for any other URI. */
    fun trackIdOf(uri: String): String? = uri.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    /**
     * The HLS master URL the catalogue already holds for a track, if any. Set by the backend
     * that owns the catalogue; resolving from it saves the lookup request on every first play.
     */
    @Volatile var knownMasterUrl: (trackId: String) -> String? = { null }

    @Volatile private var instance: StreamCache? = null
    @Volatile private var source: NativeSegmentSource? = null

    /** Bit depth and sample rate Apple declares for what is streamed for [trackId], once known. */
    fun declaredQuality(trackId: String): Pair<Int, Int>? = instance?.declaredQuality(trackId)

    /** Whether [trackId] is currently streaming a spatial/Dolby Atmos rendition (E-AC-3 or binaural). */
    fun isSpatial(trackId: String): Boolean = instance?.isSpatial(trackId) == true

    fun cache(context: Context): StreamCache = instance ?: synchronized(this) {
        instance ?: run {
            val native = NativeSegmentSource(context.applicationContext).also { source = it }
            StreamCache(
                root = File(context.applicationContext.cacheDir, "stream"),
                resolver = AmpStreamResolver(context.applicationContext),
                source = native,
                log = { CadenceLog.i("stream", it) },
            ).also { instance = it }
        }
    }

    /**
     * Gets the shared "prefetch" key in the background. Every track's first segment is under
     * that one key, so having it ready removes a licence round trip from the first play.
     */
    fun warmUp(context: Context) {
        cache(context)
        source?.warmUp()
    }
}

/** Resolves a track to its media playlist (lossless, AAC or Dolby Atmos), honouring the user's quality preferences. */
internal class AmpStreamResolver(private val context: Context) : StreamResolver {
    override val variant: String
        get() = DownloadEngine.preferences.streamVariant()

    override fun media(trackId: String): HlsMedia = runBlocking {
        val started = System.currentTimeMillis()
        // The catalogue's master URL is a plain asset path and normally still valid, which saves
        // a lookup. Assets do get replaced over time, so a failure falls back to asking again.
        val known = StreamPlayback.knownMasterUrl(trackId)?.takeIf { it.startsWith("https://") }
        val fromCatalogue = known?.let { url ->
            runCatching { fromMaster(trackId, url) }
                .onFailure { CadenceLog.i("stream", "catalogue URL for $trackId no longer works (${it.message}); looking it up") }
                .getOrNull()
        }
        val media = fromCatalogue ?: fromMaster(
            trackId,
            EngineDownloader.freshHlsUrl(context, trackId) ?: throw IOException("Track $trackId has no stream"),
        )
        CadenceLog.i("stream", "resolved $trackId segments=${media.segments.size} in ${System.currentTimeMillis() - started}ms" +
            if (fromCatalogue != null) " (catalogue URL)" else "")
        media
    }

    private suspend fun fromMaster(trackId: String, master: String): HlsMedia {
        val prefs = DownloadEngine.preferences
        val plan = WrapperKeyClient.resolve(Track(trackId, "", "", 0, 1, 0L, master), prefs.bitDepth, prefs.maxSampleRate, prefs.streamCodec, prefs.streamAtmos)
        val playlist = plan.mediaPlaylist.ifBlank { Http.text(plan.mediaUrl) }
        return HlsMediaParser.parse(playlist, plan.mediaUrl).copy(
            declaredBitDepth = plan.bitDepth,
            declaredSampleRate = plan.sampleRate,
            declaredSpatial = plan.kind == "spatial" || plan.kind == "atmos",
        )
    }
}

/** Whether a fresh FairPlay context could fix a failed segment: not when the container itself is the problem. */
internal fun keyCanFix(message: String?): Boolean {
    val m = message.orEmpty()
    return !(m.startsWith("parse init") || m.startsWith("parse fragment") || "UnsupportedCodec" in m)
}

/**
 * Remembers keys whose native exchange just failed. Apple's key service can reject a track (the
 * native layer then returns no context, or in the worst case throws and skips the step), and every
 * segment and every prefetch would otherwise start another licence round trip for the same key.
 * Entries expire so a transient failure is retried soon, and a success clears the entry at once.
 */
internal class FailedKeyMemory(
    private val ttlMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val until = ConcurrentHashMap<String, Long>()

    fun failed(key: String) { until[key] = now() + ttlMs }

    fun succeeded(key: String) { until.remove(key) }

    fun isFailing(key: String): Boolean {
        val deadline = until[key] ?: return false
        if (now() >= deadline) {
            until.remove(key, deadline)
            return false
        }
        return true
    }
}

/** The key Apple puts on the first segment of every track, so playback can start at once. */
private const val PREFETCH_KEY = "skd://itunes.apple.com/P000000000/s1/e1"

/** Fetches segments from the CDN and decrypts them with the native FairPlay bridge. */
internal class NativeSegmentSource(private val context: Context) : SegmentSource {
    /** FairPlay contexts by `adamId|keyUri`. The prefetch key is one entry shared by every track. */
    private val handles = ConcurrentHashMap<String, Long>()
    private val handleLocks = ConcurrentHashMap<String, Any>()
    private val failures = FailedKeyMemory()
    private val keyThreads = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "cadence-stream-keys").apply { isDaemon = true }
    }

    override fun prepare(trackId: String, segment: HlsSegment) {
        val adamId = KeyPlan.adamIdFor(trackId, segment.keyUri)
        acquireInBackground(adamId, segment.keyUri)
    }

    fun warmUp() = acquireInBackground("0", PREFETCH_KEY)

    private fun acquireInBackground(adamId: String, keyUri: String) {
        val key = "$adamId|$keyUri"
        if (handles.containsKey(key) || failures.isFailing(key)) return
        keyThreads.execute {
            runCatching {
                if (EngineDownloader.initNative(context)) handle(key, adamId, keyUri)
            }.onFailure { CadenceLog.i("stream", "key ${keyUri.substringAfterLast('/')} not ready yet: ${it.message}") }
        }
    }

    override fun fetch(url: String, offset: Long?, length: Long?): ByteArray {
        return try {
            Http.bytes(url, offset, length)
        } catch (first: IOException) {
            // One retry covers the transient resolver/route failures seen on mobile data.
            Thread.sleep(250)
            Http.bytes(url, offset, length)
        }
    }

    override fun clearInit(originalInit: ByteArray): ByteArray = NativeDrm.clearInit(originalInit)

    override fun clear(trackId: String, segment: HlsSegment, originalInit: ByteArray, encrypted: ByteArray): ByteArray {
        if (!EngineDownloader.initNative(context)) throw IOException("Native engine failed to initialise")
        val adamId = KeyPlan.adamIdFor(trackId, segment.keyUri)
        val key = "$adamId|${segment.keyUri}"
        val kd = handle(key, adamId, segment.keyUri)
        return try {
            NativeDrm.clearSegment(kd, originalInit, encrypted)
        } catch (stale: IOException) {
            // A malformed or unsupported container is not a key problem: renewing would only drop
            // a working context and cost a licence round trip.
            if (!keyCanFix(stale.message)) throw stale
            // A context can stop working after a while; one fresh exchange, then the error stands.
            CadenceLog.i("stream", "decrypt failed for $trackId (${stale.message}); renewing key")
            handles.remove(key, kd)
            NativeDrm.clearSegment(handle(key, adamId, segment.keyUri), originalInit, encrypted)
        }
    }

    private fun handle(key: String, adamId: String, keyUri: String): Long =
        handles[key] ?: synchronized(handleLocks.computeIfAbsent(key) { Any() }) {
            handles[key] ?: run {
                if (failures.isFailing(key)) {
                    throw IOException("Key exchange for ${keyUri.substringAfterLast('/')} failed moments ago; not retrying yet")
                }
                val kd = try {
                    exchange(adamId, keyUri)
                } catch (e: IOException) {
                    failures.failed(key)
                    throw e
                }
                failures.succeeded(key)
                handles[key] = kd
                kd
            }
        }

    private fun exchange(adamId: String, keyUri: String): Long {
        val started = System.currentTimeMillis()
        val kd = try {
            KdResolver.resolveWithTimeout(KeyPlan(adamId, listOf(KeyPlan.SegmentKey(keyUri, adamId))), 30_000L)[0]
        } catch (e: IllegalStateException) {
            throw IOException(e.message, e)
        }
        CadenceLog.i("stream", "key ${keyUri.substringAfterLast('/')} adam=$adamId ready in ${System.currentTimeMillis() - started}ms")
        return kd
    }
}
