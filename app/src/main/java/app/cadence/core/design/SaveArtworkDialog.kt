package app.cadence.core.design

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cadence.R
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.media.MotionArtFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

@Composable
fun SaveArtworkDialog(
    title: String,
    itemId: String,
    coverUrl: String?,
    motionUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizePx by remember { mutableIntStateOf(3000) }
    var asPng by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val safeName = remember(title) { title.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim().ifBlank { itemId } }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.artwork_save_title), maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (coverUrl != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3000, 1500, 600).forEach { px ->
                            FilterChip(
                                selected = sizePx == px,
                                onClick = { sizePx = px },
                                label = { Text("${px}px") },
                                enabled = !saving,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !asPng, onClick = { asPng = false }, label = { Text("JPG") }, enabled = !saving)
                        FilterChip(selected = asPng, onClick = { asPng = true }, label = { Text("PNG") }, enabled = !saving)
                    }
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = saveStillCover(context, itemId, safeName, coverUrl, sizePx, asPng)
                                saving = false
                                Toast.makeText(
                                    context,
                                    context.getString(if (ok) R.string.artwork_saved else R.string.artwork_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) onDismiss()
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.artwork_save_cover))
                    }
                }
                if (motionUrl != null) {
                    OutlinedButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = saveMotionVideo(context, itemId, safeName, motionUrl)
                                saving = false
                                Toast.makeText(
                                    context,
                                    context.getString(if (ok) R.string.artwork_saved else R.string.artwork_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) onDismiss()
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.artwork_save_motion))
                    }
                }
                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.artwork_saving))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private suspend fun saveStillCover(
    context: Context,
    itemId: String,
    baseName: String,
    coverUrl: String,
    sizePx: Int,
    asPng: Boolean,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val url = coverAtSize(coverUrl, "${sizePx}x${sizePx}") ?: return@withContext false
        val raw = fetchUrlBytes(url)
        val outBytes = if (asPng) {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@withContext false
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bmp.recycle()
                bos.toByteArray()
            }
        } else raw
        val ext = if (asPng) "png" else "jpg"
        val mime = if (asPng) "image/png" else "image/jpeg"
        val name = "${baseName}_${sizePx}.$ext"
        writeMediaStoreBytes(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir = Environment.DIRECTORY_PICTURES + "/Cadence",
            displayName = name,
            mimeType = mime,
            bytes = outBytes,
        )
        CadenceLog.i("ArtworkSave", "kind=cover id=$itemId format=$ext bytes=${outBytes.size} dest=Pictures/Cadence/$name")
        true
    }.onFailure {
        CadenceLog.e("ArtworkSave", "cover save failed id=$itemId", it)
    }.getOrDefault(false)
}

private suspend fun saveMotionVideo(
    context: Context,
    itemId: String,
    baseName: String,
    motionUrl: String,
): Boolean = withContext(Dispatchers.IO) {
    val tmpFmp4 = File.createTempFile("motion_src_", ".mp4", context.cacheDir)
    val tmpOut = File.createTempFile("motion_out_", ".mp4", context.cacheDir)
    try {
        val isHls = MotionArtFetcher.fetchToFile(motionUrl, tmpFmp4)
        if (isHls) {
            remuxVideoOnlyMp4(tmpFmp4, tmpOut)
        } else {
            tmpFmp4.copyTo(tmpOut, overwrite = true)
        }
        val bytes = tmpOut.readBytes()
        val name = "${baseName}_motion.mp4"
        writeMediaStoreBytes(
            context = context,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir = Environment.DIRECTORY_MOVIES + "/Cadence",
            displayName = name,
            mimeType = "video/mp4",
            bytes = bytes,
        )
        CadenceLog.i("ArtworkSave", "kind=motion id=$itemId format=mp4 bytes=${bytes.size} dest=Movies/Cadence/$name")
        true
    } catch (e: Exception) {
        CadenceLog.e("ArtworkSave", "motion save failed id=$itemId", e)
        false
    } finally {
        tmpFmp4.delete()
        tmpOut.delete()
    }
}

private fun remuxVideoOnlyMp4(src: File, dst: File) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(src.absolutePath)
        var videoTrack = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) {
                videoTrack = i
                break
            }
        }
        if (videoTrack < 0) throw IOException("No video track in motion artwork")
        extractor.selectTrack(videoTrack)
        muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(extractor.getTrackFormat(videoTrack))
        muxer.start()
        val buf = ByteBuffer.allocateDirect(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buf, info)
            extractor.advance()
        }
    } finally {
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        extractor.release()
    }
}

private fun writeMediaStoreBytes(
    context: Context,
    collection: android.net.Uri,
    relativeDir: String,
    displayName: String,
    mimeType: String,
    bytes: ByteArray,
) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Open output stream failed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

private fun fetchUrlBytes(url: String, offset: Long? = null, length: Long? = null): ByteArray {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "Music/5.7 Android/10")
        if (offset != null && length != null && length > 0) {
            setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        }
    }
    return try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code for $url")
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}
