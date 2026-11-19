package app.cadence.core.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    fun cached(id: String, targetPx: Int): Bitmap? = mem.get("$id@${bucketFor(targetPx)}")

    suspend fun load(context: Context, url: String, targetPx: Int): Bitmap? {
        val id = coverId(url)
        val bucket = bucketFor(targetPx)
        val memKey = "$id@$bucket"
        mem.get(memKey)?.let { return it }

        locks.get(id).withLock {
            mem.get(memKey)?.let { return it }
            val source = sourceFile(context, id)
            if (!source.exists()) downloadSource(url, source)
            if (!source.exists()) return null
            val scaled = withContext(Dispatchers.IO) { decodeDownsampled(source, bucket) } ?: return null
            mem.put(memKey, scaled)
            return scaled
        }
    }

    private fun sourceFile(context: Context, id: String): File {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        return File(dir, Integer.toHexString(id.hashCode()) + ".jpg")
    }

    private suspend fun downloadSource(url: String, dest: File) = withContext(Dispatchers.IO) {
        val src = coverAtSize(url, "${SOURCE_SIZE}x$SOURCE_SIZE") ?: url
        runCatching {
            val conn = (URL(src).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Music/5.7 Android/10")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    android.util.Log.e("Cadence", "cover: ${conn.responseCode} $src")
                    return@runCatching
                }
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(dest)
            } finally {
                conn.disconnect()
            }
        }.onFailure { android.util.Log.e("Cadence", "cover: ${it.javaClass.simpleName}: ${it.message} $src") }
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
    var bitmap by remember(id, targetPx) {
        mutableStateOf(id?.let { CoverStore.cached(it, targetPx) })
    }
    LaunchedEffect(id, targetPx) {
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
