package app.cadence.backend.engine.media

import android.content.Context
import java.io.File
import java.io.IOException
import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.DownloadKind
import app.cadence.backend.engine.DownloadPreferences
import app.cadence.backend.engine.PathTemplate
import app.cadence.backend.engine.TemplateValues
import app.cadence.backend.engine.stream.HlsMedia
import app.cadence.backend.engine.stream.HlsMediaParser
import app.cadence.backend.engine.stream.NativeSegmentSource
import app.cadence.backend.engine.stream.StreamCache
import app.cadence.backend.engine.stream.StreamPlayback
import app.cadence.backend.engine.stream.StreamResolver
import app.cadence.backend.real.SessionStore as AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * In-process download + decrypt. Everything lives
 * inside Cadence now: FairPlay key exchange via the Apple libraries in jniLibs,
 * HLS fetch, then native sample decryption and metadata tagging.
 *
 * The pipeline:
 *
 *   enhancedHls master -> ALAC variant -> media playlist -> segments
 *     -> KeyPlan (one key per segment; segment 1 uses the prefetch key)
 *     -> KD context per key (NativeWrapper.kd)
 *     -> NativeDrm decrypt + tag -> publish
 *
 * Decryption starts as soon as the download ends; there is no separate button.
 */
object EngineDownloader {

    @Volatile private var nativeReady = false

    @Synchronized
    internal fun initNative(context: Context): Boolean {
        if (nativeReady) return true
        // Ported engine logs (HLS/Kd/DRM) go to logcat and to files/logs/cadence.log,
        // which is the only record when a download fails on a user's device.
        AppLog.init(context)
        NativeData.seed(context)
        val deviceId = DeviceIdentity.id(context)
        // The wrapper/StoreServices binds its state to the base dir it was seeded in.
        val baseDir = context.filesDir.absolutePath
        AppLog.log("Cadence", "native base=$baseDir")
        // The native resolver uses these (the active network's DNS) instead of a fixed public
        // resolver, so FairPlay key lookups work where external UDP/53 is blocked (some carriers).
        val dnsServers = ProcessNetwork.activeDnsServers(context)
        AppLog.log("Cadence", "native dns=${ProcessNetwork.describe(dnsServers)}")
        // The wrapper is initialized on a dedicated thread; StoreServices/DeviceGUID
        // capture that thread's state. Initialize the same way instead of on a
        // coroutine pool thread, then let the KD run from the download coroutine.
        var ready = false
        val initThread = Thread({
            app.cadence.backend.engine.Provisioning.dir = java.io.File(baseDir)
            // A missing/unloadable native library must degrade (no engine), not kill the process: NativeLibrary has already logged why.
            ready = runCatching { NativeWrapper.init(baseDir, deviceId, dnsServers) }
                .onFailure { AppLog.error("Cadence", "native init failed (engine unavailable)", it) }
                .getOrDefault(false)
        }, "cadence-native-init")
        initThread.start()
        initThread.join()
        if (ready) ProcessNetwork.follow(context, dnsServers)
        nativeReady = ready
        AppLog.log("Cadence", "native init=$nativeReady")
        return nativeReady
    }

    /**
     * The FairPlay/StoreServices session for lyrics. Cadence ships the credentials
     * in the imported session (`files/engine/amp.json`).
     */
    private fun session(context: Context): Session? {
        val amp = AmpConfig.load(context) ?: return null
        // The signed-in account wins over the provisioned fallback: the app's
        // SessionStore is written by RealAuthService after a successful login.
        val account = runCatching { AccountStore(context) }.getOrNull()
        val token = runCatching { account?.load()?.accessToken }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: amp.mediaUserToken
        val storefront = AmpEngine.latestStorefront?.takeIf { it.isNotBlank() }
            ?: runCatching { account?.storefront() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: amp.storefront
        return Session(
            devToken = amp.devToken,
            mediaUserToken = token,
            storefront = storefront,
            storefrontCode = storefront,
        )
    }

    /**
     * Resolves a fresh `enhancedHls` URL for the song with the current account
     * token. Catalogue rows are cached with their HLS URLs, which are tokenised
     * and expire; using them days later (or after a storefront change) makes the
     * CDN answer 403 and the download fail.
     */
    internal suspend fun freshHlsUrl(context: Context, trackId: String): String? {
        if (trackId.isBlank()) return null
        return runCatching {
            val amp = AmpConfig.load(context) ?: return null
            val account = AccountStore(context)
            val token = account.load()?.accessToken?.takeIf { it.isNotBlank() } ?: amp.mediaUserToken
            val storefront = AmpEngine.latestStorefront?.takeIf { it.isNotBlank() }
                ?: account.storefront().ifBlank { amp.storefront }
            val engine = AmpEngine.shared(amp.copy(mediaUserToken = token, storefront = storefront))
            val attributes = engine.song(trackId)
                ?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
                ?: return null
            attributes.optJSONObject("extendedAssetUrls")
                ?.optString("enhancedHls")
                ?.takeIf { it.isNotBlank() }
        }.onFailure {
            AppLog.error("Cadence", "fresh HLS lookup failed; using catalogue URL", it)
        }.getOrNull()
    }

    /**
     * Downloads one track, decrypts it with the per-segment key plan and publishes
     * the tagged file. Folder and file names come from the user's templates, and
     * the destination from the per-kind save location.
     */
    suspend fun download(
        context: Context,
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        onState: (DownloadState) -> Unit = {},
    ): String = finish(context, stage(context, item, prefs, onState), onState)

    /**
     * A track staged on this device, ready to finish. Two shapes: the two-phase path fills
     * [saved]+[kds] (an encrypted package to decrypt); the streaming path fills [clearPackage] (an
     * already-clear package to mux and tag). [tags] and the naming fields are common to both.
     */
    class Staged internal constructor(
        internal val item: DownloadEngine.DownloadItem,
        internal val track: Track,
        internal val album: Album,
        internal val settings: AppSettings,
        internal val dateStyle: String,
        internal val saved: String,
        internal val tags: Triple<String, ByteArray?, String?>,
        internal val kds: LongArray,
        internal val relativeDir: String,
        internal val folder: String,
        internal val fileName: String,
        /** The assembled clear package for a streaming download; null for the two-phase path. */
        internal val clearPackage: File? = null,
        /** Non-fragmented `.m4p` download + its `SVSampleDecryptor` context; null for HLS paths. */
        internal val m4pFile: File? = null,
        internal val m4pCtx: Long = 0L,
        /** The rendition this download is (`alac:24:96000`, `aac:128`, `atmos:768`, `spatial`), "" when unknown. */
        internal val quality: String = "",
        /** Formatted lyrics for sidecar file export when `settings.lyricsSaveFile` is enabled. */
        internal val sidecarLyrics: String? = null,
    ) {
        /** Removes the staged package; for a staged track that will not be finished. */
        fun discard() {
            clearPackage?.let { runCatching { it.delete() } }
            m4pFile?.let { runCatching { it.delete() } }
            if (m4pCtx != 0L) runCatching { NativeWrapper.releaseM4pContext(m4pCtx) }
            runCatching { android.net.Uri.parse(saved).path?.let { java.io.File(it).delete() } }
        }
    }

    /** The naming/metadata setup a job needs, resolved from the item and the user's templates. */
    internal class JobSetup(
        val track: Track,
        val album: Album,
        val settings: AppSettings,
        val relativeDir: String,
        val folder: String,
        val fileName: String,
    )

    /** The `{codec}` naming value: "atmos" and "spatial" name the Atmos renditions, so they never collide with stereo files. */
    private fun namingCodec(plan: FairPlayPlan): String = if (plan.kind == "atmos" || plan.kind == "spatial") plan.kind else plan.codec

    /** The resolved rendition for naming, and the plan it came from (null when resolving failed and the requested quality is used). */
    private class ResolvedQuality(val codec: String, val bitDepth: Int, val sampleRate: Int, val plan: FairPlayPlan?)

    private suspend fun resolveQuality(track: Track, prefs: DownloadPreferences): ResolvedQuality =
        runCatching {
            val p = WrapperKeyClient.resolve(track, prefs.policy())
            ResolvedQuality(namingCodec(p), p.bitDepth, p.sampleRate, p)
        }.getOrElse {
            AppLog.error("Cadence", "quality resolve for naming failed; using requested settings", it)
            val c = prefs.policy().ceiling
            ResolvedQuality(if (c.family == QualityFamily.LOSSLESS) "alac" else "aac", c.bitDepth, c.sampleRate, null)
        }

    private fun trackOf(item: DownloadEngine.DownloadItem, hls: String) = Track(
        item.trackId, item.title, item.artist, item.trackNumber, item.discNumber, 0L,
        hls.takeIf { it.isNotBlank() }, hasLyrics = item.hasLyrics,
    )

    /**
     * Builds the per-job track/album/settings and the folder/file names from the templates.
     * [codec]/[bitDepth]/[sampleRate] are the resolved rendition's real quality, used by the
     * `{codec}`/`{bitdepth}`/`{samplerate}`/`{quality}` tokens.
     */
    internal fun prepareJobForTest(
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        hls: String,
        codec: String = "",
        bitDepth: Int = 0,
        sampleRate: Int = 0,
    ): JobSetup = prepareJob(item, prefs, hls, codec, bitDepth, sampleRate)

    private fun prepareJob(
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        hls: String,
        codec: String = "",
        bitDepth: Int = 0,
        sampleRate: Int = 0,
    ): JobSetup {
        val track = trackOf(item, hls)
        val albumTitle = if (prefs.dropEpSingleSuffix) DownloadPreferences.stripEpSingleSuffix(item.albumTitle) else item.albumTitle
        val album = Album(
            id = item.trackId,
            title = albumTitle,
            artist = item.artist,
            artworkUrl = null,
            artworkTemplate = item.artworkTemplate,
            releaseDate = item.releaseDate,
            genre = item.genre,
            copyright = item.copyright,
            trackCount = item.trackTotal,
        )
        val values = TemplateValues(
            artist = item.artist,
            album = albumTitle,
            albumArtist = item.artist,
            title = item.title,
            track = item.trackNumber,
            disc = item.discNumber,
            date = item.releaseDate.orEmpty(),
            genre = item.genre.orEmpty(),
            playlist = item.collectionTitle,
            codec = codec,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
        )
        val folder = PathTemplate.folderPath(prefs.folderTemplateFor(item.kind), values)
        val fileName = PathTemplate.fileName(prefs.fileTemplateFor(item.kind), values)
        val namingTemplate = if (folder.isBlank()) fileName else "$folder/$fileName"
        val settings = AppSettings(
            saveTreeUri = prefs.treeFor(item.kind),
            namingTemplate = namingTemplate,
            lyricsEmbed = prefs.writeMetadata && prefs.embedLyrics,
            lyricsSaveFile = prefs.saveLyricsFile,
            lyricsFormat = prefs.lyricsFormat,
            artworkEmbed = prefs.writeMetadata && prefs.embedCover,
            artworkSize = prefs.coverSize,
            artworkFormat = prefs.coverFormat,
        )
        val relativeDir = if (folder.isBlank()) "Music/Cadence" else "Music/Cadence/$folder"
        return JobSetup(track, album, settings, relativeDir, folder, fileName)
    }

    /**
     * The network half of a download: resolves the variant, fetches the encrypted package and,
     * meanwhile, the lyrics and cover. Needs no key, so it can run while another track decrypts.
     */
    suspend fun stage(
        context: Context,
        item: DownloadEngine.DownloadItem,
        defaults: DownloadPreferences,
        onState: (DownloadState) -> Unit = {},
    ): Staged {
        // The quality picked for THIS download, if any, replaces the default for everything below (naming, cache, resolving).
        val prefs = defaults.forQuality(item.quality)
        if (item.quality != null) AppLog.log("QUALITY", "track=${item.trackId} picked ${prefs.downloadVariant()} (default would be ${defaults.downloadVariant()})")
        if (prefs.streamingDownload) {
            // Per-segment download (fetch + decrypt each segment as it arrives, reusing anything
            // playback already decrypted). Any failure falls back to the proven two-phase path;
            // cancellation is not a failure and must propagate.
            try {
                return stageStreaming(context, item, prefs, onState)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.error("Cadence", "streaming download failed for ${item.trackId}; falling back to two-phase", t)
            }
        }
        return stageTwoPhase(context, item, prefs, onState)
    }

    private suspend fun stageTwoPhase(
        context: Context,
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        onState: (DownloadState) -> Unit = {},
    ): Staged = withContext(Dispatchers.IO) {
        check(initNative(context)) { "native engine failed to initialise" }
        val hls = freshHlsUrl(context, item.trackId) ?: item.hlsUrl
        if (hls.isBlank()) return@withContext stageM4p(context, item, prefs, onState)
        if (hls != item.hlsUrl) AppLog.log("Cadence", "fresh HLS URL resolved for ${item.trackId}")

        // Resolve the ALAC/AAC variant first, exactly like the reference pipeline (the enhancedHls master
        // lists AAC before ALAC, and handing the master straight to HlsDownloader would download the
        // 256 kbps stream). Doing it before naming also lets the folder/file reflect the real quality.
        onState(DownloadState(running = true, phase = "downloading", message = "Resolving"))
        val plan = WrapperKeyClient.resolve(trackOf(item, hls), prefs.policy())
        AppLog.log("Cadence", "resolved media=${plan.mediaUrl} key=${plan.keyUri} quality=${plan.kind}/${plan.bitDepth}bit/${plan.sampleRate}Hz spec=${plan.spec}")

        val setup = prepareJob(item, prefs, hls, namingCodec(plan), plan.bitDepth, plan.sampleRate)
        val track = setup.track
        val album = setup.album
        val settings = setup.settings
        val relativeDir = setup.relativeDir
        val folder = setup.folder
        val fileName = setup.fileName
        AppLog.log(
            "Cadence",
            "download start track=${item.trackId} kind=${item.kind} dir=$relativeDir file=$fileName tree=${settings.saveTreeUri ?: "default"}",
        )

        coroutineScope {
            val tags = async(Dispatchers.IO) { DrmService.prepareTags(settings, session(context), track, album, prefs.albumDate) }
            val saved = try {
                HlsDownloader(context).download(track, album, settings, plan.mediaUrl, onState, plan.mediaPlaylist)
            } catch (t: Throwable) {
                tags.cancel()
                throw t
            }
            AppLog.log("Cadence", "downloaded cache=$saved")
            // The key exchange is a round trip to Apple too, so it belongs to this half: by the
            // time the previous track has finished decrypting, this one only has local work left.
            val kds = try {
                val keyPlan = KeyPlan.load(context, item.trackId) ?: error("no key plan written by download")
                onState(DownloadState(running = true, phase = "downloading", progress = 1f, message = "Fetching keys"))
                KdResolver.resolveWithTimeout(keyPlan, 45_000L).also {
                    AppLog.log("Cadence", "plan segments=${keyPlan.segments.size} keys=${keyPlan.segments.map { k -> k.keyUri }.distinct()}")
                }
            } catch (t: Throwable) {
                tags.cancel()
                runCatching { android.net.Uri.parse(saved).path?.let { java.io.File(it).delete() } }
                throw t
            }
            // Inside the cleanup as well: a failed or cancelled tag fetch must not leave the
            // encrypted package (up to ~150 MB) behind in the cache.
            val metadata = try {
                tags.await()
            } catch (t: Throwable) {
                runCatching { android.net.Uri.parse(saved).path?.let { java.io.File(it).delete() } }
                throw t
            }
            val sidecar = resolveSidecarLyrics(context, settings, track, metadata.first)
            Staged(item, track, album, settings, prefs.albumDate, saved, metadata, kds, relativeDir, folder, fileName, quality = plan.spec, sidecarLyrics = sidecar)
        }
    }

    // A dedicated decrypted-segment cache for downloads, used when the user turns off sharing
    // playback's cache or when the download quality differs from the streaming quality. Reused
    // across downloads so repeated downloads of the same track stay fast; trimmed by its own budget.
    @Volatile private var downloadCache: StreamCache? = null
    private val downloadCacheLock = Any()

    private fun dedicatedCache(context: Context): StreamCache = downloadCache ?: synchronized(downloadCacheLock) {
        downloadCache ?: StreamCache(
            root = File(context.applicationContext.cacheDir, "download-stream"),
            resolver = DownloadStreamResolver(context.applicationContext),
            source = NativeSegmentSource(context.applicationContext),
            log = { CadenceLog.i("download-stream", it) },
        ).also { downloadCache = it }
    }

    /**
     * Chooses the stream cache for a download. Sharing playback's cache (so a just-played track
     * downloads instantly) is only correct when the download and streaming qualities resolve to the
     * same rendition; otherwise, and when the user turns sharing off, a dedicated cache is used.
     */
    private fun pickCache(context: Context, prefs: DownloadPreferences): Pair<StreamCache, StreamResolver?> {
        val policy = prefs.policy()
        // A 16-bit limit is the one case where playback's older picker and the download planner can choose differently
        // (a song without 16-bit lossless), so it never shares: the file must be the rendition that was logged and recorded.
        val depthLimited = policy.ceiling.family == QualityFamily.LOSSLESS && policy.ceiling.bitDepth < 24
        val share = prefs.shareStreamCache && prefs.downloadVariant() == prefs.streamVariant() && !depthLimited
        // Not shared: the dedicated cache, resolved at THIS download's quality (never the global setting).
        return if (share) StreamPlayback.cache(context) to null else dedicatedCache(context) to DownloadStreamResolver(context, policy)
    }

    /**
     * The streaming download: resolve the track, then fetch + decrypt its segments one at a time
     * (reusing any already on disk) and assemble them into a single clear package. No whole-file
     * download and no separate decrypt pass - [finish] only muxes and tags what is already clear.
     */
    private suspend fun stageStreaming(
        context: Context,
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        onState: (DownloadState) -> Unit = {},
    ): Staged = withContext(Dispatchers.IO) {
        check(initNative(context)) { "native engine failed to initialise" }
        // Name from the real resolved quality. The stream cache does its own resolution, so this is
        // a best-effort extra lookup for the `{quality}` tokens; failure falls back to the settings.
        val hls = freshHlsUrl(context, item.trackId) ?: item.hlsUrl
        val resolved = resolveQuality(trackOf(item, hls), prefs)
        val setup = prepareJob(item, prefs, hls, resolved.codec, resolved.bitDepth, resolved.sampleRate)
        AppLog.log(
            "Cadence",
            "stream download start track=${item.trackId} kind=${item.kind} dir=${setup.relativeDir} " +
                "file=${setup.fileName} share=${prefs.shareStreamCache} variant=${prefs.downloadVariant()}",
        )
        onState(DownloadState(running = true, phase = "downloading", message = "Resolving"))
        val (cache, resolver) = pickCache(context, prefs)
        val clearPackage = File(context.cacheDir, "cadence_clear_${System.nanoTime()}.mp4")
        coroutineScope {
            // Tags (lyrics + cover) are network work with no key; build them while segments download.
            val tags = async(Dispatchers.IO) {
                DrmService.prepareTags(setup.settings, session(context), setup.track, setup.album, prefs.albumDate)
            }
            try {
                val stream = if (resolver == null) cache.stream(item.trackId) else cache.stream(item.trackId, resolver)
                AppLog.log("Cadence", "stream download segments=${stream.segmentCount} cached=${stream.cachedSegments()}")
                stream.writePackage(clearPackage) { done, total ->
                    onState(
                        DownloadState(
                            running = true,
                            phase = "downloading",
                            progress = if (total > 0) done.toFloat() / total else 0f,
                            message = "Downloading",
                        )
                    )
                }
                AppLog.log("Cadence", "stream download assembled clear=${clearPackage.length()}")
            } catch (t: Throwable) {
                tags.cancel()
                clearPackage.delete()
                throw t
            }
            val metadata = tags.await()
            val sidecar = resolveSidecarLyrics(context, setup.settings, setup.track, metadata.first)
            Staged(
                item, setup.track, setup.album, setup.settings, prefs.albumDate,
                saved = "", tags = metadata, kds = LongArray(0),
                setup.relativeDir, setup.folder, setup.fileName, clearPackage = clearPackage,
                quality = resolved.plan?.spec.orEmpty(), sidecarLyrics = sidecar,
            )
        }
    }

    private fun resolveSidecarLyrics(
        context: Context,
        settings: AppSettings,
        track: Track,
        metaJson: String,
    ): String? {
        if (!settings.lyricsSaveFile) return null
        val embedded = runCatching { org.json.JSONObject(metaJson).optString("lyrics") }.getOrNull()
        if (!embedded.isNullOrBlank()) return embedded
        val sess = session(context) ?: return null
        return runCatching {
            Lyrics.fetch(sess, track.id, track.hasLyrics)?.let { ttml ->
                Lyrics.format(ttml, settings.lyricsFormat).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** The local half: fetches the keys, decrypts, tags and publishes the staged track. */
    suspend fun finish(
        context: Context,
        staged: Staged,
        onState: (DownloadState) -> Unit = {},
    ): String {
        val uri = when {
            staged.clearPackage != null -> finishStreaming(context, staged, staged.clearPackage, onState)
            staged.m4pFile != null -> finishM4p(context, staged, staged.m4pFile, onState)
            else -> finishTwoPhase(context, staged, onState)
        }
        if (staged.settings.lyricsSaveFile && !staged.sidecarLyrics.isNullOrBlank()) {
            TrackPublisher.publishSidecar(
                context = context,
                content = staged.sidecarLyrics,
                baseName = staged.fileName,
                ext = staged.settings.lyricsFormat,
                relativeDir = staged.relativeDir,
                folder = staged.folder,
                treeUri = staged.settings.saveTreeUri,
            )
        }
        // Remember what quality this file is, so the next download of the song can say "already downloaded as ...".
        runCatching {
            DownloadRegistry.record(context, DownloadRecord(staged.item.trackId, staged.quality, uri, staged.fileName, System.currentTimeMillis()))
            AppLog.log("QUALITY", "downloaded track=${staged.item.trackId} as ${staged.quality.ifBlank { "unknown quality" }} -> $uri")
        }.onFailure { AppLog.error("QUALITY", "could not record the download", it) }
        return uri
    }

    private suspend fun stageM4p(
        context: Context,
        item: DownloadEngine.DownloadItem,
        prefs: DownloadPreferences,
        onState: (DownloadState) -> Unit = {},
    ): Staged = coroutineScope {
        onState(DownloadState(running = true, phase = "downloading", progress = 0f, message = "Resolving AAC asset"))
        val asset = NativeWrapper.m4pAsset(item.trackId)
            ?: throw IOException("No FairPlay HLS or M4P asset available for ${item.trackId}")
        val setup = prepareJob(item, prefs, hls = "", codec = "aac", bitDepth = 16, sampleRate = 44100)
        val sess = session(context)
        val m4pFile = File(context.cacheDir, "cadence_m4p_${item.trackId}_${System.nanoTime()}.m4p")
        try {
            val tagsJob = async(Dispatchers.IO) {
                DrmService.prepareTags(setup.settings, sess, setup.track, setup.album, prefs.albumDate)
            }
            val conn = (java.net.URL(asset.url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("M4P download failed: HTTP $code")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    m4pFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            onState(DownloadState(running = true, phase = "downloading", progress = frac, message = "Downloading"))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val tags = tagsJob.await()
            AppLog.log("Cadence", "staged m4p=${m4pFile.length()} ctx=${asset.decryptorCtx}")
            Staged(
                item = item,
                track = setup.track,
                album = setup.album,
                settings = setup.settings,
                dateStyle = prefs.albumDate,
                saved = m4pFile.absolutePath,
                tags = tags,
                kds = LongArray(0),
                relativeDir = setup.relativeDir,
                folder = setup.folder,
                fileName = setup.fileName,
                m4pFile = m4pFile,
                m4pCtx = asset.decryptorCtx,
                quality = "aac:256",
            )
        } catch (t: Throwable) {
            m4pFile.delete()
            NativeWrapper.releaseM4pContext(asset.decryptorCtx)
            throw t
        }
    }

    private suspend fun finishM4p(
        context: Context,
        staged: Staged,
        m4pFile: File,
        onState: (DownloadState) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        try {
            onState(DownloadState(running = true, phase = "decrypting", progress = 0f, message = "Decrypting"))
            val outName = if (staged.fileName.endsWith(".m4a")) staged.fileName else "${staged.fileName}.m4a"
            val out = DrmService.decryptM4p(
                context = context,
                m4pFile = m4pFile,
                m4pCtx = staged.m4pCtx,
                settings = staged.settings,
                session = session(context),
                track = staged.track,
                album = staged.album,
                dateStyle = staged.dateStyle,
                relativeDir = staged.relativeDir,
                outputName = outName,
                folder = staged.folder,
                treeUri = staged.settings.saveTreeUri,
                onProgress = { fraction ->
                    onState(DownloadState(running = true, phase = "decrypting", progress = fraction, message = "Decrypting"))
                },
                tags = staged.tags,
            )
            AppLog.log("Cadence", "m4p download published=$out")
            onState(DownloadState(running = false, done = true, phase = "done", progress = 1f, message = out))
            out
        } finally {
            m4pFile.delete()
            if (staged.m4pCtx != 0L) NativeWrapper.releaseM4pContext(staged.m4pCtx)
        }
    }

    /** Muxes and tags the assembled clear package from a streaming download, then publishes it. */
    private suspend fun finishStreaming(
        context: Context,
        staged: Staged,
        clearPackage: File,
        onState: (DownloadState) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        try {
            onState(DownloadState(running = true, phase = "decrypting", progress = 0f, message = "Finishing"))
            val outName = if (staged.fileName.endsWith(".m4a")) staged.fileName else "${staged.fileName}.m4a"
            val out = DrmService.muxClearPackage(
                context = context,
                clearPackage = clearPackage,
                settings = staged.settings,
                session = session(context),
                track = staged.track,
                album = staged.album,
                dateStyle = staged.dateStyle,
                relativeDir = staged.relativeDir,
                outputName = outName,
                folder = staged.folder,
                treeUri = staged.settings.saveTreeUri,
                onProgress = { fraction ->
                    onState(DownloadState(running = true, phase = "decrypting", progress = fraction, message = "Finishing"))
                },
                tags = staged.tags,
            )
            AppLog.log("Cadence", "stream download published=$out")
            onState(DownloadState(running = false, done = true, phase = "done", progress = 1f, message = out))
            out
        } finally {
            clearPackage.delete()
        }
    }

    private suspend fun finishTwoPhase(
        context: Context,
        staged: Staged,
        onState: (DownloadState) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val track = staged.track
        val album = staged.album
        val settings = staged.settings
        val saved = staged.saved
        val relativeDir = staged.relativeDir
        val folder = staged.folder
        val fileName = staged.fileName
        val cacheFile: java.io.File? = runCatching {
            val path = android.net.Uri.parse(saved).path
            if (path != null) java.io.File(path) else null
        }.getOrNull()

        try {
            val kds = staged.kds
            onState(DownloadState(running = true, phase = "decrypting", progress = 0f, message = "Decrypting"))
            val outName = if (fileName.endsWith(".m4a")) fileName else "$fileName.m4a"
            val out = DrmService.decryptWithKds(
                context = context,
                savedUri = saved,
                kds = kds,
                settings = settings,
                session = session(context),
                track = track,
                album = album,
                dateStyle = staged.dateStyle,
                tags = staged.tags,
                relativeDir = relativeDir,
                outputName = outName,
                folder = folder,
                treeUri = settings.saveTreeUri,
                onProgress = { fraction ->
                    onState(
                        DownloadState(
                            running = true,
                            phase = "decrypting",
                            progress = fraction,
                            message = "Decrypting",
                        )
                    )
                },
            )
            AppLog.log("Cadence", "decrypted published=$out")
            onState(DownloadState(running = false, done = true, phase = "done", progress = 1f, message = out))
            out
        } finally {
            // The encrypted package is cache-only by construction: always remove it.
            cacheFile?.delete()
        }
    }
}

/**
 * Resolves a track to its media playlist at the user's *download* quality, for the dedicated
 * download cache. The shared cache uses `AmpStreamResolver` (streaming quality) instead; this one
 * keeps a quality mismatch - downloading lossless while streaming AAC - from reusing the wrong
 * rendition. Mirrors `AmpStreamResolver.fromMaster` with `codec` in place of `streamCodec`.
 */
internal class DownloadStreamResolver(private val context: Context, private val policy: QualityPolicy? = null) : StreamResolver {
    /** The quality of one download when [policy] is given; otherwise the current default (only for the cache's own default resolver). */
    private fun wanted(): QualityPolicy = policy ?: DownloadEngine.preferences.policy()
    override val variant: String get() = wanted().cacheKey()

    override fun media(trackId: String): HlsMedia = runBlocking {
        val master = EngineDownloader.freshHlsUrl(context, trackId)
            ?: throw IOException("Track $trackId has no stream")
        val plan = WrapperKeyClient.resolve(Track(trackId, "", "", 0, 1, 0L, master), wanted())
        val playlist = plan.mediaPlaylist.ifBlank { Http.text(plan.mediaUrl) }
        HlsMediaParser.parse(playlist, plan.mediaUrl).copy(declaredBitDepth = plan.bitDepth, declaredSampleRate = plan.sampleRate)
    }
}
