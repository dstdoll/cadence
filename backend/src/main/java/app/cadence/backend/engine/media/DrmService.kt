package app.cadence.backend.engine.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object DrmService {

    /**
     * Fails a download whose output doesn't match the input package. `rc == 0` only means the native
     * muxer finished; comparing the output's sample tables against what the package declared catches
     * a parser that silently skipped samples here, instead of publishing a file that plays a sliver
     * of every segment. Shared by the decrypt ([decryptWithKds]) and clear-mux ([muxClearPackage])
     * paths. Throws [IOException] on a mismatch; a check that cannot run is logged and tolerated.
     */
    private fun verifyIntegrity(inputPackage: File, output: File) {
        runCatching { Mp4Integrity.mismatch(inputPackage, output) }
            .onFailure { AppLog.error("DRM", "integrity check could not run", it) }
            .getOrNull()
            ?.let { reason ->
                AppLog.log("DRM", "integrity FAILED: $reason")
                throw IOException("Download is incomplete: $reason")
            }
        AppLog.log("DRM", "integrity ok")
    }

    /**
     * Runs optional (network) tag work with a hard deadline. `HttpURLConnection`
     * connect/read timeouts do not cover DNS, so a flaky network can park the
     * download in metadata forever and the decrypt never starts. Tags are
     * optional: when this times out the file is still written with basic tags.
     */
    private fun <T> optionalWithTimeout(millis: Long, block: () -> T): T? {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cadence-tags").apply { isDaemon = true } }
        val future = executor.submit(Callable { block() })
        return try {
            future.get(millis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            AppLog.log("DRM", "tag build timed out after ${millis}ms; decrypting without lyrics/cover")
            future.cancel(true)
            null
        } catch (t: Throwable) {
            AppLog.error("DRM", "tag build failed", t)
            null
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Tags, lyrics and cover for one track, within the deadline above. Network only, so a
     * download can run it while the package is still coming in.
     */
    fun prepareTags(settings: AppSettings, session: Session?, track: Track?, album: Album?, dateStyle: String): Triple<String, ByteArray?, String?> =
        optionalWithTimeout(45_000) { buildTags(settings, session, track, album, dateStyle) } ?: Triple("{}", null, null)

    /**
     * Builds the `udta/meta/ilst` payload: basic tags always, lyrics and cover
     * per [AppSettings] (the amdl `metadata.*` knobs).
     */
    private fun buildTags(
        settings: AppSettings,
        session: Session?,
        track: Track?,
        album: Album?,
        dateStyle: String,
    ): Triple<String, ByteArray?, String?> {
        val lyrics = if (settings.lyricsEmbed && session != null && track != null) {
            runCatching {
                Lyrics.fetch(session, track.id, track.hasLyrics)?.let { ttml ->
                    if (settings.lyricsFormat == "ttml") ttml else Lyrics.toLrc(Lyrics.parseLines(ttml))
                }
            }.getOrNull()
        } else {
            null
        }

        val cover = if (settings.artworkEmbed) {
            Artwork.fetchBest(album?.artworkTemplate ?: track?.artworkTemplate, settings)
        } else {
            null
        }

        val json = org.json.JSONObject().apply {
            track?.let {
                put("title", it.title)
                put("artist", it.artist)
                if (it.trackNumber > 0) put("track", it.trackNumber)
                if (it.discNumber > 0) put("disc", it.discNumber)
                it.isrc?.let { v -> put("isrc", v) }
            }
            album?.let {
                put("album", it.title)
                put("albumArtist", it.artist)
                if (it.trackCount > 0) put("trackTotal", it.trackCount)
                // `©day`: full ISO date, year only, or omitted per Settings.
                it.releaseDate?.takeIf { dateStyle != "none" }?.let { v ->
                    put("year", if (dateStyle == "year") v.take(4) else v)
                }
                it.copyright?.let { v -> put("copyright", v) }
            }
            // Genre can come from either level; album wins.
            (album?.genre ?: track?.genre)?.let { put("genre", it) }
            if (track?.isCompilation == true) put("compilation", true)
            lyrics?.let { put("lyrics", it) }
            // No encoder tag: a shared file should not say which app made it.
        }.toString()
        AppLog.log(
            "DRM",
            "tags lyrics=${lyrics?.length ?: 0}B cover=${cover?.first?.size ?: 0}B size=${settings.artworkRequestSize()}",
        )
        return Triple(json, cover?.first, cover?.second)
    }

    /**
     * Decrypts a downloaded, encrypted HLS package with its per-segment KD plan, tags the result and
     * publishes it (via [TrackPublisher]: the user's SAF folder, then the public Music collection,
     * then app-private storage). The two-phase download path; [muxClearPackage] is its streaming
     * counterpart. Throws [IOException] if the native decrypt or the integrity check fails.
     */
    suspend fun decryptWithKds(
        context: Context,
        savedUri: String,
        kds: LongArray,
        settings: AppSettings = AppSettings(null, AppSettings.DEFAULT_TEMPLATE),
        session: Session? = null,
        track: Track? = null,
        album: Album? = null,
        /** >1 decrypts fragments concurrently; used by experiment E5. */
        workers: Int = 1,
        /** "full" | "year" | "none" for the `©day` atom. */
        dateStyle: String = "full",
        /** MediaStore collection, e.g. `Music/Cadence/<artist>/<album>`. */
        relativeDir: String = "Music/Cadence",
        /** Final file name, e.g. `07 Touch.m4a` (falls back to the cache file's stem). */
        outputName: String? = null,
        /** Folder inside [treeUri], e.g. `Daft Punk/Random Access Memories`. */
        folder: String = "",
        /** SAF tree to publish into; null publishes to MediaStore. */
        treeUri: String? = null,
        /** 0..1 while the native decrypt runs, so the UI can draw a real bar. */
        onProgress: ((Float) -> Unit)? = null,
        /** Tags built ahead of time (see [prepareTags]); null builds them here. */
        tags: Triple<String, ByteArray?, String?>? = null,
    ): String = withContext(Dispatchers.IO) {
        AppLog.log("DRM", "start native-kd saved=$savedUri segments=${kds.size} distinct=${kds.toSet().size}")
        val uri = Uri.parse(savedUri)
        var cacheIn = File(context.cacheDir, "cadence_${System.nanoTime()}.enc.mp4")
        val cacheOut = File(context.cacheDir, "cadence_${System.nanoTime()}.m4a")
        try {
            val name: String
            if (uri.scheme == "file") {
                val input = File(uri.path ?: throw IOException("No file path"))
                if (!input.isFile) throw IOException("Downloaded file not found: ${input.absolutePath}")
                // Already a private file: decrypt it where it is instead of copying the package.
                cacheIn = input
                name = input.name
            } else {
                val doc = DocumentFile.fromSingleUri(context, uri)
                    ?: throw IOException("Downloaded file not found")
                name = doc.name ?: "track.encrypted.m4a"
                context.contentResolver.openInputStream(uri)?.use { i ->
                    cacheIn.outputStream().use { i.copyTo(it) }
                } ?: throw IOException("Cannot read downloaded file")
            }
            AppLog.log("DRM", "cache in=${cacheIn.length()}")

            // Generous budget: large artwork (e.g. 5000×5000) legitimately takes a while to pull,
            // and the fallback sizes in Artwork.fetchBest need room to run. Tags are still optional,
            // so a genuinely stuck fetch here is abandoned and the file is written with basic tags.
            val (metaJson, coverBytes, coverMime) = tags ?: prepareTags(settings, session, track, album, dateStyle)
            // The native decrypt is one blocking call. The muxer streams decrypted
            // samples to `cacheOut` as it goes, so its growth against the encrypted
            // input size is a good progress signal. Cap below 1 until it returns:
            // the final moov/faststart rewrite happens at the very end.
            val rc = if (onProgress == null) {
                NativeDrm.decryptFileWithKdPlanMeta(
                    kds, cacheIn.absolutePath, cacheOut.absolutePath,
                    metaJson, coverBytes, coverMime, workers,
                )
            } else {
                coroutineScope {
                    val work = async(Dispatchers.IO) {
                        NativeDrm.decryptFileWithKdPlanMeta(
                            kds, cacheIn.absolutePath, cacheOut.absolutePath,
                            metaJson, coverBytes, coverMime, workers,
                        )
                    }
                    val expected = cacheIn.length().coerceAtLeast(1L)
                    while (!work.isCompleted) {
                        onProgress((cacheOut.length().toFloat() / expected).coerceIn(0f, 0.97f))
                        delay(120)
                    }
                    work.await()
                }
            }
            AppLog.log("DRM", "decrypt rc=$rc outBytes=${cacheOut.length()}")
            if (rc != 0) throw IOException("Native KP decrypt failed: $rc")
            verifyIntegrity(cacheIn, cacheOut)

            val stem = name.removeSuffix(".encrypted.m4a.bin").removeSuffix(".encrypted.m4a")
            val outName = outputName?.takeIf { it.isNotBlank() }
                ?: if (stem.endsWith(".m4a")) stem else "$stem.m4a"

            // An explicit "save location" wins; the encrypted package was only in
            // the app cache, so here we create the final folder/file in the tree.
            TrackPublisher.publish(context, cacheOut, outName, relativeDir, folder, treeUri)
        } finally {
            cacheIn.delete()
            cacheOut.delete()
        }
    }

    /**
     * The streaming-download counterpart of [decryptWithKds]: muxes an already-clear package
     * (clear init + every clear segment, assembled by the stream cache) into a tagged `.m4a` and
     * publishes it. No key plan - each segment was decrypted as it was fetched - and no whole-file
     * decrypt pass, so a track whose segments are already cached is published almost immediately.
     * The output is identical to decrypting the same track in one pass.
     */
    suspend fun muxClearPackage(
        context: Context,
        clearPackage: File,
        settings: AppSettings = AppSettings(null, AppSettings.DEFAULT_TEMPLATE),
        session: Session? = null,
        track: Track? = null,
        album: Album? = null,
        dateStyle: String = "full",
        relativeDir: String = "Music/Cadence",
        outputName: String? = null,
        folder: String = "",
        treeUri: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        tags: Triple<String, ByteArray?, String?>? = null,
    ): String = withContext(Dispatchers.IO) {
        AppLog.log("DRM", "start clear-mux package=${clearPackage.length()}")
        val cacheOut = File(context.cacheDir, "cadence_${System.nanoTime()}.m4a")
        try {
            if (!clearPackage.isFile || clearPackage.length() == 0L) {
                throw IOException("Clear package missing or empty: ${clearPackage.absolutePath}")
            }
            val (metaJson, coverBytes, coverMime) = tags ?: prepareTags(settings, session, track, album, dateStyle)
            onProgress?.invoke(0.1f)
            // The mux is a single native call: parse fragments, write moov/mdat + tags, faststart.
            // It is fast relative to the fetch/decrypt that already happened, so progress jumps.
            val rc = NativeDrm.muxClearWithMeta(
                clearPackage.absolutePath, cacheOut.absolutePath, metaJson, coverBytes, coverMime,
            )
            AppLog.log("DRM", "clear-mux rc=$rc outBytes=${cacheOut.length()}")
            if (rc != 0) throw IOException("Native clear mux failed: $rc")
            verifyIntegrity(clearPackage, cacheOut)
            onProgress?.invoke(0.98f)

            val outName = outputName?.takeIf { it.isNotBlank() }?.let {
                if (it.endsWith(".m4a")) it else "$it.m4a"
            } ?: "track.m4a"
            TrackPublisher.publish(context, cacheOut, outName, relativeDir, folder, treeUri)
        } finally {
            cacheOut.delete()
        }
    }

    /**
     * Decrypts a non-fragmented `.m4p` asset via `SVSampleDecryptor` ([m4pCtx]), writes tags +
     * faststarts into a temporary `.m4a`, and publishes it into the configured folder/tree.
     */
    suspend fun decryptM4p(
        context: Context,
        m4pFile: File,
        m4pCtx: Long,
        settings: AppSettings = AppSettings(null, AppSettings.DEFAULT_TEMPLATE),
        session: Session? = null,
        track: Track? = null,
        album: Album? = null,
        dateStyle: String = "full",
        relativeDir: String = "Music/Cadence",
        outputName: String? = null,
        folder: String = "",
        treeUri: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        tags: Triple<String, ByteArray?, String?>? = null,
    ): String = withContext(Dispatchers.IO) {
        AppLog.log("DRM", "start m4p-decrypt file=${m4pFile.length()} ctx=$m4pCtx")
        val cacheOut = File(context.cacheDir, "cadence_${System.nanoTime()}.m4a")
        try {
            if (!m4pFile.isFile || m4pFile.length() == 0L) {
                throw IOException("M4P file missing or empty: ${m4pFile.absolutePath}")
            }
            val (metaJson, coverBytes, coverMime) = tags ?: prepareTags(settings, session, track, album, dateStyle)
            onProgress?.invoke(0.1f)
            val rc = NativeDrm.decryptM4pWithMeta(
                m4pCtx, m4pFile.absolutePath, cacheOut.absolutePath, metaJson, coverBytes, coverMime,
            )
            AppLog.log("DRM", "m4p-decrypt rc=$rc outBytes=${cacheOut.length()}")
            if (rc != 0) throw IOException("Native m4p decrypt failed: $rc")
            onProgress?.invoke(0.98f)

            val outName = outputName?.takeIf { it.isNotBlank() }?.let {
                if (it.endsWith(".m4a")) it else "$it.m4a"
            } ?: "track.m4a"
            TrackPublisher.publish(context, cacheOut, outName, relativeDir, folder, treeUri)
        } finally {
            cacheOut.delete()
        }
    }
}
