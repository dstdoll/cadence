package app.cadence.core.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.media.CdnHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * Cover-art loader that downloads each cover once and reuses it at every size.
 *
 * Apple serves artwork from a `.../<w>x<h>bb.jpg` template, so the same cover appears under many
 * sized URLs. Keying a cache by the sized URL (the old behaviour) re-downloaded and re-decoded the
 * same image for every size on screen and held full-resolution bitmaps in a tiny count-bounded LRU,
 * which showed up as scroll jank and the odd missing cover.
 *
 * Instead: strip the size token to a stable [coverId], fetch **one** high-resolution source per
 * cover to a disk cache, and for each on-screen size decode that source down with `inSampleSize`
 * into a small [ImageBitmap] held in a byte-bounded memory cache. One network request per cover; a
 * handful of cheap downsampled decodes; memory bounded by bytes, not entries.
 */
private object CoverStore {
    private const val SOURCE_SIZE = 1024 // single high-res source fetched per cover.

    // Downsampled bitmaps keyed by "id@bucketPx"; bounded by bytes so large covers evict small ones.
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Serialises the download of a given source so concurrent composables don't fetch it twice.
    private val locks = object : LruCache<String, Mutex>(256) {
        override fun create(key: String) = Mutex()
    }

    /** Display buckets (px). A request is served from the smallest bucket that still covers it. */
    private val BUCKETS = intArrayOf(128, 256, 512, 1024)
    private fun bucketFor(targetPx: Int) = BUCKETS.firstOrNull { it >= targetPx } ?: BUCKETS.last()

    private val _networkEpoch = MutableStateFlow(0L)
    val networkEpoch: StateFlow<Long> = _networkEpoch
    private val callbackRegistered = AtomicBoolean(false)

    fun cached(id: String, targetPx: Int): Bitmap? = mem.get("$id@${bucketFor(targetPx)}")

    suspend fun load(context: Context, url: String, targetPx: Int): Bitmap? {
        val id = coverId(url)
        val bucket = bucketFor(targetPx)
        val memKey = "$id@$bucket"
        mem.get(memKey)?.let { return it }

        locks.get(id).withLock {
            mem.get(memKey)?.let { return it }
            val source = sourceFile(context, id)
            if (!source.exists()) downloadSource(context.applicationContext, url, source)
            if (!source.exists()) return null
            val scaled = withContext(Dispatchers.IO) { decodeDownsampled(source, bucket) } ?: return null
            mem.put(memKey, scaled)
            return scaled
        }
    }

    private fun ensureNetworkCallback(appContext: Context) {
        if (!callbackRegistered.compareAndSet(false, true)) return
        runCatching {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return
            val initialEvent = AtomicBoolean(true)
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (initialEvent.compareAndSet(true, false)) return
                    _networkEpoch.value += 1L
                }
            })
        }
    }

    private fun sourceFile(context: Context, id: String): File {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        return File(dir, Integer.toHexString(id.hashCode()) + ".jpg")
    }

    private val RETRY_DELAYS_MS = longArrayOf(400L, 1200L)

    private suspend fun downloadSource(appContext: Context, url: String, dest: File) {
        val src = coverAtSize(url, "${SOURCE_SIZE}x$SOURCE_SIZE") ?: url
        var sawTransient = false
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                val code = CdnHttp.downloadToFile(src, dest)
                if (code in 200..299) return
                val msg = "$code $src"
                if (isTransientHttpStatus(code) && attempt < RETRY_DELAYS_MS.size) {
                    sawTransient = true
                    delay(RETRY_DELAYS_MS[attempt])
                    continue
                }
                if (isTransientHttpStatus(code)) sawTransient = true
                CadenceLog.e("cover", msg)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val transient = isTransientNetworkError(e)
                if (transient && attempt < RETRY_DELAYS_MS.size) {
                    sawTransient = true
                    delay(RETRY_DELAYS_MS[attempt])
                    continue
                }
                if (transient) sawTransient = true
                CadenceLog.e("cover", "${e.javaClass.simpleName}: ${e.message} $src")
                break
            }
        }
        if (sawTransient) ensureNetworkCallback(appContext)
    }

    /** Decode the on-disk source downsampled to ~[bucket] px (power-of-two subsample is cheap). */
    private fun decodeDownsampled(file: File, bucket: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null } // corrupt/partial download
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= bucket) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}

/**
 * Loads [coverUrl] at roughly [targetPx] display pixels, reusing a single downloaded source across
 * sizes. Returns null (so callers fall back to procedural art) until the bitmap is ready.
 */
@Composable
fun rememberCover(coverUrl: String?, targetPx: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    val id = coverUrl?.let { coverId(it) }
    // A target of zero means "not measured yet": nothing is looked up or decoded until there is a
    // real size, so a cover is decoded once, at the size it is drawn at.
    var bitmap by remember(id, targetPx) {
        mutableStateOf(if (targetPx > 0) id?.let { CoverStore.cached(it, targetPx) } else null)
    }
    val epoch by CoverStore.networkEpoch.collectAsState()
    LaunchedEffect(id, targetPx, epoch) {
        if (targetPx <= 0) return@LaunchedEffect
        val url = coverUrl ?: return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        bitmap = CoverStore.load(context, url, targetPx)
    }
    return bitmap?.asImageBitmap()
}

/**
 * The cover at roughly [targetPx] pixels, outside Compose (the notification artwork), from the same
 * memory and disk cache the screens use: a cover seen once loads offline and is fetched only once.
 */
suspend fun loadCoverBitmap(context: Context, coverUrl: String, targetPx: Int): Bitmap? =
    CoverStore.load(context.applicationContext, coverUrl, targetPx)

/** The size-independent identity of an Apple cover URL, so every size shares one download/cache. */
private fun coverId(url: String): String = coverAtSize(url, "src") ?: url

internal fun isTransientHttpStatus(code: Int): Boolean = code == 429 || code in 500..599

internal fun isTransientNetworkError(t: Throwable): Boolean = when (t) {
    is UnknownHostException,
    is SocketTimeoutException,
    is ConnectException,
    is SSLException -> true
    is IOException -> t.cause?.let(::isTransientNetworkError) ?: false
    else -> false
}
