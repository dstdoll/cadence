package app.cadence.backend.engine.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Publishes a finished `.m4a` to persistent storage - the I/O boundary of the download pipeline.
 *
 * Extracted from [DrmService] so decryption/muxing (a pure transform over bytes) is separated from
 * where the result is written (Storage Access Framework tree, the public MediaStore collection, or
 * app-private storage). [DrmService] decrypts; [TrackPublisher] decides where the bytes land.
 *
 * [publish] tries each destination in order and returns the URI of the first that succeeds; it
 * always returns a URI because the app-private fallback cannot fail.
 */
internal object TrackPublisher {

    /**
     * Writes [source] to the first destination that works: the user's chosen SAF [treeUri], then the
     * public `Music/Cadence` collection ([relativeDir]), then app-private storage. Shared by the
     * decrypt and the streaming-download (clear-mux) paths so both land files identically.
     */
    fun publish(
        context: Context,
        source: File,
        outName: String,
        relativeDir: String,
        folder: String,
        treeUri: String?,
    ): String {
        if (treeUri != null) {
            publishToTree(context, treeUri, folder, outName, source)?.let {
                AppLog.log("DRM", "done tree uri=$it")
                return it
            }
            AppLog.log("DRM", "tree publish failed; falling back to Music/Cadence")
        }
        publishToMusic(context, source, outName, relativeDir)?.let {
            AppLog.log("DRM", "done public uri=$it")
            return it
        }
        return appPrivateFallback(context, source, outName)
    }

    /**
     * Writes a text sidecar file (e.g. `.lrc`, `.ttml`, `.srt`, `.txt`) beside the published track
     * using the same destination order as [publish]: SAF tree, public `MediaStore.Files`, then
     * app-private fallback. Returns the written URI/path, or null on failure (non-fatal).
     */
    fun publishSidecar(
        context: Context,
        content: String,
        baseName: String,
        ext: String,
        relativeDir: String,
        folder: String,
        treeUri: String?,
    ): String? {
        if (content.isBlank()) return null
        val fileName = "${baseName.removeSuffix(".m4a")}.${ext.lowercase()}"
        val mime = when (ext.lowercase()) {
            "ttml" -> "application/ttml+xml"
            else -> "text/plain"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (treeUri != null) {
            publishSidecarToTree(context, treeUri, folder, fileName, mime, bytes)?.let {
                AppLog.log("DRM", "sidecar tree uri=$it")
                return it
            }
        }
        publishSidecarToMediaStore(context, fileName, mime, relativeDir, bytes)?.let {
            AppLog.log("DRM", "sidecar public uri=$it")
            return it
        }
        return runCatching {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir,
                "Cadence",
            ).apply { mkdirs() }
            val target = File(dir, fileName)
            target.writeBytes(bytes)
            AppLog.log("DRM", "sidecar app-private out=${target.absolutePath}")
            Uri.fromFile(target).toString()
        }.getOrNull()
    }

    private fun publishSidecarToTree(
        context: Context,
        treeUri: String,
        folder: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): String? = runCatching {
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        for (part in folder.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(part) ?: dir.createDirectory(part) ?: return null
        }
        dir.findFile(name)?.delete()
        val doc = dir.createFile(mime, name) ?: return null
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(bytes) } ?: return null
        doc.uri.toString()
    }.getOrElse {
        AppLog.error("DRM", "sidecar tree failed", it)
        null
    }

    private fun publishSidecarToMediaStore(
        context: Context,
        name: String,
        mime: String,
        relativeDir: String,
        bytes: ByteArray,
    ): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri("external")
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw java.io.IOException("MediaStore gave no sidecar stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null,
            )
            uri.toString()
        } catch (t: Throwable) {
            AppLog.error("DRM", "sidecar publish threw", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Publishes into the public Music collection. Insert, stream, finalise, then
     * verify the row and its size: a pending row that is never finalised is
     * reclaimed by MediaProvider, which previously left an empty
     * `Music/Cadence` behind and silently lost the track.
     */
    private fun publishToMusic(
        context: Context,
        source: File,
        name: String,
        relativeDir: String,
    ): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw java.io.IOException("MediaStore gave no output stream")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null, null,
            )
            val size = publishedSize(context, uri)
            if (size != source.length()) {
                AppLog.log("DRM", "publish verify failed size=$size expected=${source.length()}")
                runCatching { resolver.delete(uri, null, null) }
                null
            } else {
                AppLog.log("DRM", "published $uri bytes=$size")
                uri.toString()
            }
        } catch (t: Throwable) {
            AppLog.error("DRM", "publish threw", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Writes the decrypted master into the chosen SAF tree, creating the template
     * folder path. The encrypted package is never written here.
     */
    private fun publishToTree(
        context: Context,
        treeUri: String,
        folder: String,
        name: String,
        source: File,
    ): String? = runCatching {
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        for (part in folder.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(part) ?: dir.createDirectory(part) ?: return null
        }
        dir.findFile(name)?.delete()
        val doc = dir.createFile("audio/mp4", name) ?: return null
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return null
        if (doc.length() != source.length()) {
            AppLog.log("DRM", "tree write short: ${doc.length()} of ${source.length()}")
            doc.delete()
            return null
        }
        doc.uri.toString()
    }.getOrElse {
        AppLog.error("DRM", "publish tree failed", it)
        null
    }

    /** Last-resort destination inside app-private external storage; cannot fail, so [publish] ends here. */
    private fun appPrivateFallback(context: Context, source: File, name: String): String {
        val dir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "Cadence",
        ).apply { mkdirs() }
        val target = File(dir, name)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        AppLog.log("DRM", "done app-private fallback out=${target.absolutePath} size=${target.length()}")
        return Uri.fromFile(target).toString()
    }

    /** Reads back the size MediaStore stored, or -1 when the row is gone or still pending. */
    private fun publishedSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.IS_PENDING),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return -1L
            if (c.getInt(1) != 0) return -1L
            c.getLong(0)
        } ?: -1L
    }.getOrDefault(-1L)
}
