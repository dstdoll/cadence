package app.cadence.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import app.cadence.backend.engine.CadenceLog
import app.cadence.core.design.ArtworkRenderer
import app.cadence.core.design.loadCoverBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Artwork for the notification, the lock screen and Bluetooth/car controllers.
 *
 * Covers come from the same memory and disk cache the app's screens use, so a cover that was seen
 * once also shows offline and is never downloaded twice. When a cover cannot be loaded (no network
 * and not cached yet), the track's procedural art is drawn instead of leaving the tile blank.
 */
@OptIn(UnstableApi::class)
internal class CoverBitmapLoader(
    private val context: Context,
    private val decoder: BitmapLoader = DataSourceBitmapLoader.Builder(context).build(),
) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = decoder.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = decoder.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        loadCoverBitmap(context, uri.toString(), ART_PX) ?: throw IOException("cover unavailable")
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val uri = metadata.artworkUri
        val data = metadata.artworkData
        return when {
            uri != null -> scope.future {
                var source = "cover"
                artworkOrFallback(
                    cover = { loadCoverBitmap(context, uri.toString(), ART_PX) },
                    fallback = { source = "procedural"; proceduralArt(metadata.extras) },
                ).also { CadenceLog.i("player", "artwork source=${if (it == null) "none" else source}") }
                    ?: throw IOException("no artwork")
            }
            data != null -> decoder.decodeBitmap(data)
            else -> proceduralArt(metadata.extras)?.let { Futures.immediateFuture(it) }
        }
    }

    fun release() = scope.cancel()

    companion object {
        /** Notification and lock-screen art size; the cache serves it from a 512 px bucket. */
        const val ART_PX = 512
        const val EXTRA_ALBUM_ID = "app.cadence.albumId"
        const val EXTRA_ALBUM_COLORS = "app.cadence.albumColors"

        /** What the loader needs to draw the procedural fallback for a track's album. */
        fun extras(albumId: String, colors: List<String>) = Bundle().apply {
            putString(EXTRA_ALBUM_ID, albumId)
            putStringArrayList(EXTRA_ALBUM_COLORS, ArrayList(colors))
        }

        private fun proceduralArt(extras: Bundle?): Bitmap? {
            val id = extras?.getString(EXTRA_ALBUM_ID) ?: return null
            val colors = extras.getStringArrayList(EXTRA_ALBUM_COLORS).orEmpty()
            return runCatching { ArtworkRenderer.bitmap(id, colors) }.getOrNull()
        }
    }
}

/**
 * The cover when it loads, otherwise the fallback. A failed or empty cover load is not an error;
 * cancellation still propagates. Generic so the decision is tested on the JVM.
 */
internal suspend fun <T : Any> artworkOrFallback(cover: suspend () -> T?, fallback: () -> T?): T? {
    val loaded = try {
        cover()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return loaded ?: fallback()
}
