package app.cadence.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import app.cadence.backend.engine.stream.ClearStreamReader
import app.cadence.backend.engine.stream.StreamCache
import app.cadence.backend.engine.stream.StreamPlayback
import app.cadence.backend.engine.stream.WebPlayback
import java.io.IOException

/**
 * Feeds the player one catalogue track as a single clear fragmented MP4, read from the decrypted
 * stream cache. Opening resolves the track if it is not open yet; reading into a part that has
 * not been downloaded blocks the loader thread until that segment is fetched and decrypted.
 */
@OptIn(UnstableApi::class)
class ClearStreamDataSource(private val cache: StreamCache) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var reader: ClearStreamReader? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val trackId = StreamPlayback.trackIdOf(dataSpec.uri.toString())
            ?: throw IOException("Not a stream URI: ${dataSpec.uri}")
        val stream = cache.stream(trackId)
        val total = stream.layout.totalSize
        if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val available = total - dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        reader = ClearStreamReader(stream, dataSpec.position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = reader?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: return C.RESULT_END_OF_INPUT
        if (count < 0) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.close()
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

@OptIn(UnstableApi::class)
class WebPlaylistDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var bytes: ByteArray? = null
    private var offset = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val trackId = WebPlayback.trackIdOf(dataSpec.uri.toString())
            ?: throw IOException("Not a web playback URI: ${dataSpec.uri}")
        val data = WebPlayback.resolvePlaylistBytes(context, trackId)
        val pos = dataSpec.position.toInt().coerceAtLeast(0)
        if (pos > data.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        bytes = data
        offset = pos
        opened = true
        transferStarted(dataSpec)
        return (data.size - pos).toLong()
    }

    override fun read(buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (length == 0) return 0
        val data = bytes ?: return C.RESULT_END_OF_INPUT
        val rem = data.size - offset
        if (rem <= 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, rem)
        System.arraycopy(data, offset, buffer, dstOffset, count)
        offset += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        bytes = null
        offset = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/**
 * Data sources for the player: catalogue tracks ([StreamPlayback.SCHEME]) come from the decrypted
 * stream cache, AAC-only tracks ([WebPlayback.SCHEME]) resolve via Web Playback, and everything else
 * (local files, plain URLs, Widevine segments) comes from the default.
 */
@OptIn(UnstableApi::class)
class CadenceDataSourceFactory(context: Context, private val cache: StreamCache) : DataSource.Factory {
    private val appContext = context.applicationContext
    private val fallback = DefaultDataSource.Factory(appContext)

    override fun createDataSource(): DataSource =
        Routing(fallback.createDataSource(), ClearStreamDataSource(cache), WebPlaylistDataSource(appContext))

    private class Routing(
        private val fallback: DataSource,
        private val stream: DataSource,
        private val web: DataSource,
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            fallback.addTransferListener(transferListener)
            stream.addTransferListener(transferListener)
            web.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = when (dataSpec.uri.scheme) {
                StreamPlayback.SCHEME -> stream
                WebPlayback.SCHEME -> web
                else -> fallback
            }
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: throw IOException("Data source is not open")

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            active?.close()
            active = null
        }
    }
}
