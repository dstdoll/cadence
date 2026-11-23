
package app.cadence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.cadence.backend.api.Backend
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.CadenceLog
import app.cadence.core.design.coverTemplate
import app.cadence.backend.search.SearchHit
import app.cadence.backend.engine.media.KeyPlan
import app.cadence.backend.engine.media.KdResolver
import app.cadence.backend.engine.media.NativeData
import app.cadence.backend.engine.media.NativeDrm
import app.cadence.backend.engine.media.NativeWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import app.cadence.backend.api.SearchScope
import app.cadence.container
import app.cadence.LogExport
import app.cadence.state.library.restoreFromBackupJson
import app.cadence.state.library.toBackupJson
import kotlinx.coroutines.delay

/**
 * Headless hook for verification:
 *   adb shell am broadcast -a app.cadence.DEBUG_SEARCH --es q "daft punk"
 * Writes progress and results to logcat and to files/logs/cadence.log.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CadenceLog.init(context)
        val appContext = context.applicationContext
        // Test hook: `--es codec aac|alac` switches the format for downloads and playback until
        // the settings change again.
        intent.getStringExtra("codec")?.takeIf { it == "aac" || it == "alac" }?.let { codec ->
            DownloadEngine.configure(DownloadEngine.preferences.copy(codec = codec, streamCodec = codec))
            CadenceLog.i("download", "debug codec override -> $codec")
        }
        // Test hook: `--es atmos off|spatial|atmos` sets the Dolby Atmos preference (the real
        // setting, so Settings shows what is being tested).
        intent.getStringExtra("atmos")?.takeIf { it in listOf("off", "spatial", "atmos") }?.let { mode ->
            val was = appContext.container.settings.state.value.streamAtmos
            appContext.container.settings.update { it.copy(streamAtmos = mode) }
            DownloadEngine.configure(DownloadEngine.preferences.copy(streamAtmos = mode))
            // "was" lets a test put the user's own choice back afterwards.
            CadenceLog.i("player", "debug atmos -> $mode (was $was)")
        }
        // Test hook: `--es replayGain on` turns measured replay gain on (track mode, no preamps) and
        // `--es replayGain restore` puts the user's own replay gain settings back.
        intent.getStringExtra("replayGain")?.let { v ->
            val settings = appContext.container.settings
            when (v) {
                "on" -> {
                    if (savedReplayGain == null) savedReplayGain = settings.state.value.dspReplayGain
                    settings.update { it.copy(dspReplayGain = app.cadence.state.settings.ReplayGainConfig(enabled = true)) }
                }
                "restore" -> savedReplayGain?.let { saved ->
                    settings.update { it.copy(dspReplayGain = saved) }
                    savedReplayGain = null
                }
            }
            CadenceLog.i("player", "debug replay gain -> $v")
        }
        // Test hook: `--es downloadAtmos off|spatial|atmos` sets the Dolby Atmos choice for downloads,
        // in memory only (a restart goes back to Settings).
        intent.getStringExtra("downloadAtmos")?.takeIf { it in listOf("off", "spatial", "atmos") }?.let { mode ->
            DownloadEngine.configure(DownloadEngine.preferences.copy(atmos = mode))
            CadenceLog.i("download", "debug download atmos -> $mode")
        }
        if (intent.action == "app.cadence.DEBUG_DOWNLOAD") {
            CadenceLog.i("download", "receiver hit trackId=" + intent.getStringExtra("trackId"))
            val trackId = intent.getStringExtra("trackId").orEmpty()
            val appContext = context.applicationContext
            // Test hook: `--es size 1400x1400` overrides the embedded-artwork size for this download
            // so the metadata can be verified headlessly without the Settings UI.
            intent.getStringExtra("size")?.takeIf { it.isNotBlank() }?.let { size ->
                DownloadEngine.configure(
                    DownloadEngine.preferences.copy(coverSize = size, embedCover = true, writeMetadata = true)
                )
                CadenceLog.i("download", "debug cover-size override -> $size")
            }
            // Test hook: `--es mode stream|twophase` picks the download pipeline, and
            // `--es shareCache true|false` whether the streaming path reuses playback's cache, so
            // the two paths can be compared for byte parity headlessly.
            intent.getStringExtra("mode")?.takeIf { it.isNotBlank() }?.let { mode ->
                DownloadEngine.configure(DownloadEngine.preferences.copy(streamingDownload = mode != "twophase"))
                CadenceLog.i("download", "debug download mode -> ${if (mode != "twophase") "streaming" else "twophase"}")
            }
            intent.getStringExtra("shareCache")?.takeIf { it.isNotBlank() }?.let { v ->
                DownloadEngine.configure(DownloadEngine.preferences.copy(shareStreamCache = v == "true"))
                CadenceLog.i("download", "debug shareCache -> $v")
            }
            GlobalScope.launch {
                // Prefer the live app graph: it is already seeded, so the track is
                // resolvable immediately (a fresh RealBackend seeds asynchronously).
                val backend = runCatching { appContext.container.backend }.getOrNull()
                    ?: Backend.create(appContext)
                var info = backend.catalog.track(trackId)
                var waited = 0
                while (info == null && waited < 10_000) {
                    delay(500)
                    waited += 500
                    info = backend.catalog.track(trackId)
                }
                if (info == null) {
                    CadenceLog.e("download", "no such track $trackId")
                    return@launch
                }
                CadenceLog.i("download", "track=" + info.track.title + " hls=" + info.track.audio.take(60))
                DownloadEngine.downloadTrack(
                    context = appContext,
                    trackId = info.id,
                    title = info.track.title,
                    artist = info.artist.name,
                    albumTitle = info.album.title,
                    hlsUrl = info.track.audio,
                    artworkTemplate = coverTemplate(info.album.cover),
                    kind = app.cadence.backend.engine.DownloadKind.SINGLE,
                    releaseDate = info.album.released,
                    genre = info.album.genre,
                    trackNumber = info.track.number,
                )
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_PLAY") {
            // Headless playback: queue catalogue tracks and play them through the real player.
            //   adb shell am broadcast -n app.cadence/.DebugReceiver -a app.cadence.DEBUG_PLAY \
            //       --es trackIds 1055140614,1055140611 --ef volume 0
            val ids = intent.getStringExtra("trackIds").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val volume = intent.getFloatExtra("volume", 1f)
            GlobalScope.launch(Dispatchers.Main) {
                val c = appContext.container
                var waited = 0
                c.repository.prepareTracks(ids) // any catalogue id, not only those already loaded
                var tracks = ids.mapNotNull(c.repository::track)
                while (tracks.size < ids.size && waited < 10_000) {
                    delay(500)
                    waited += 500
                    tracks = ids.mapNotNull(c.repository::track)
                }
                while (!c.player.state.value.connected && waited < 15_000) {
                    delay(250)
                    waited += 250
                }
                CadenceLog.i("play", "queue=${tracks.map { it.track.title }} missing=${ids.size - tracks.size} volume=$volume connected=${c.player.state.value.connected}")
                if (tracks.isEmpty()) return@launch
                c.player.setVolume(volume)
                c.player.play(tracks)
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_PLAYER") {
            //   --es cmd next|previous|pause|play|seek|status   [--el ms 30000]
            val cmd = intent.getStringExtra("cmd").orEmpty()
            val ms = intent.getLongExtra("ms", 0L)
            GlobalScope.launch(Dispatchers.Main) {
                val p = appContext.container.player
                when (cmd) {
                    "next" -> p.next()
                    "previous" -> p.previous()
                    "pause" -> p.pause()
                    "play" -> p.togglePlay()
                    "seek" -> p.seekTo(ms)
                }
                delay(400)
                val s = p.state.value
                CadenceLog.i("play", "status after '$cmd' index=${s.index} title='${s.current?.track?.title}' playing=${s.isPlaying} buffering=${s.buffering} pos=${p.position()}ms dur=${s.durationMs}ms format=${s.format}")
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_IDENTITY") {
            // Replace the id the Apple libraries are configured with, or go back to the platform's.
            // It takes effect on the next start: the old provisioning is set aside then.
            //   adb shell am broadcast -n app.cadence/.DebugReceiver -a app.cadence.DEBUG_IDENTITY --es cmd rotate
            //   adb shell am broadcast ... --es cmd clear | --es cmd show
            val identity = app.cadence.backend.engine.media.DeviceIdentity
            val done = when (intent.getStringExtra("cmd").orEmpty()) {
                "rotate" -> identity.rotate(appContext)
                "clear" -> identity.clear(appContext)
                else -> true
            }
            // Never the id itself: only that there is one and whether it is a replacement.
            CadenceLog.i("e2e", "identity ok=$done rotated=${identity.isRotated(appContext)} id=<${identity.describe(identity.id(appContext))}>")
            return
        }
        if (intent.action == "app.cadence.DEBUG_DNS") {
            // The engine's own resolver (the one the sign-in uses), for the device check:
            //   ... -a app.cadence.DEBUG_DNS --es cmd probe [--es host init.itunes.apple.com]
            //   ... --es cmd set --es servers 192.0.2.1   (a server that never answers)
            //   ... --es cmd refresh                       (back to the active network's servers)
            val net = app.cadence.backend.engine.media.ProcessNetwork
            // Whichever path started the engine (app start, a sign-in, a download) has called ProcessNetwork.follow.
            if (!net.engineReady) {
                CadenceLog.i("e2e", "dns engine not ready")
                return
            }
            GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                when (intent.getStringExtra("cmd").orEmpty()) {
                    "set" -> net.install(intent.getStringExtra("servers").orEmpty(), "debug")
                    "refresh" -> net.refresh(appContext, "debug")
                }
                val host = intent.getStringExtra("host") ?: "init.itunes.apple.com"
                val start = System.nanoTime()
                val ok = app.cadence.backend.engine.media.NativeWrapper.resolves(host)
                CadenceLog.i("e2e", "dns probe host=$host resolves=$ok ms=${(System.nanoTime() - start) / 1_000_000}")
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_SESSION") {
            // Session file round trip for the device check (debug builds only).
            //   adb shell am broadcast -n app.cadence/.DebugReceiver -a app.cadence.DEBUG_SESSION --es cmd export
            //   adb shell am broadcast ... --es cmd import --es path <file in the app's cache> [--ez restart false]
            val cmd = intent.getStringExtra("cmd").orEmpty()
            val path = intent.getStringExtra("path").orEmpty()
            val restart = intent.getBooleanExtra("restart", true)
            GlobalScope.launch(Dispatchers.IO) {
                when (cmd) {
                    "export" -> {
                        val f = java.io.File(appContext.cacheDir, "e2e/session.zip").apply { parentFile?.mkdirs() }
                        app.cadence.backend.engine.SessionTransfer.exportTo(appContext, f)
                            .onSuccess { CadenceLog.i("e2e", "session export entries=${it.entries} bytes=${it.bytes} file=${f.length()}") }
                    }
                    "import" -> app.cadence.backend.engine.SessionTransfer.importFrom(appContext, java.io.File(path))
                        .onSuccess {
                            CadenceLog.i("e2e", "session import entries=${it.entries} bytes=${it.bytes} restart=$restart")
                            if (restart) app.cadence.feature.settings.restartApp(appContext)
                        }
                    else -> CadenceLog.e("e2e", "session: unknown cmd '$cmd'")
                }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_LIBRARY") {
            // What Library shows, for the end-to-end check: the account library and each section's count.
            //   adb shell am broadcast -n app.cadence/.DebugReceiver -a app.cadence.DEBUG_LIBRARY
            GlobalScope.launch(Dispatchers.Main) {
                val c = appContext.container
                var waited = 0
                while (c.repository.accountLibrary.trackIds.isEmpty() && waited < 90_000) { delay(500); waited += 500 }
                val repo = c.repository
                val lib = c.library.state.value
                repo.prepareTracks(lib.savedTracks + lib.favorites)
                val account = repo.accountLibrary
                val counts = app.cadence.core.design.LibraryKind.entries.joinToString(" ") { kind ->
                    "${kind.name}=${app.cadence.feature.library.libraryCount(kind, repo, lib)}"
                }
                CadenceLog.i("e2e", "library account songs=${account.trackIds.size} albums=${account.albumIds.size} artists=${account.artistIds.size} waited=${waited}ms")
                CadenceLog.i("e2e", "library sections $counts")
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_PALETTE") {
            // The neon palette the app computes for a track's cover, to compare with the reference.
            //   adb shell am broadcast -n app.cadence/.DebugReceiver -a app.cadence.DEBUG_PALETTE --es trackId 1055140614
            val trackId = intent.getStringExtra("trackId").orEmpty()
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    val repo = appContext.container.repository
                    repo.prepareTracks(listOf(trackId))
                    val cover = repo.track(trackId)?.album?.cover ?: error("no cover for $trackId")
                    val url = coverTemplate(cover)?.replace("{w}x{h}", "128x128") ?: cover
                    val bitmap = java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    // The exact pixels Android decoded, so the check can run the reference code on the same input.
                    val dump = java.io.File(appContext.cacheDir, "e2e/palette-$trackId.rgba").apply { parentFile?.mkdirs() }
                    dump.outputStream().buffered().use { out ->
                        for (px in pixels) { out.write((px shr 16) and 0xFF); out.write((px shr 8) and 0xFF); out.write(px and 0xFF); out.write(0xFF) }
                    }
                    val p = app.cadence.core.design.CoverPalette.of(pixels, bitmap.width, bitmap.height)
                    CadenceLog.i("e2e", "palette track=$trackId size=${bitmap.width}x${bitmap.height} accent=%06x palette=%s".format(p.accent, p.palette.joinToString(",") { "%06x".format(it) }))
                }.onFailure { CadenceLog.e("e2e", "palette failed", it) }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_EXPORT_LOGS") {
            GlobalScope.launch(Dispatchers.IO) {
                val r = LogExport.export(appContext)
                CadenceLog.i("logs", "export=" + (r?.displayPath ?: "failed"))
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_DECRYPT") {
            // Untagged reproduce-the-reference hook (parity with the reference
            // HeadlessDecrypt task=decrypt): input package + KeyPlan JSON -> output.
            val input = intent.getStringExtra("input").orEmpty()
            val output = intent.getStringExtra("output").orEmpty()
            val planPath = intent.getStringExtra("planPath").orEmpty()
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    NativeData.seed(appContext)
                    val deviceId = android.provider.Settings.Secure.getString(
                        appContext.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID,
                    ) ?: "unknown"
                    val dns = app.cadence.backend.engine.media.ProcessNetwork.activeDnsServers(appContext)
                    val ready = NativeWrapper.init(appContext.filesDir.absolutePath, deviceId, dns)
                    if (ready) app.cadence.backend.engine.media.ProcessNetwork.follow(appContext, dns)
                    CadenceLog.i("decrypt", "native init=$ready")
                    val plan = KeyPlan.parse(java.io.File(planPath).readText())
                    CadenceLog.i("decrypt", "plan segments=" + plan.segments.size + " input=" + java.io.File(input).length())
                    val t0 = System.currentTimeMillis()
                    val kds = KdResolver.resolve(plan)
                    // Modes must all produce the reference bytes: batch (untagged),
                    // streaming (the download path), and parallel workers.
                    val mode = intent.getStringExtra("mode").orEmpty()
                    val rc = when (mode) {
                        "stream" -> NativeDrm.decryptFileWithKdPlanMeta(kds, input, output, "{}", null, null, 1)
                        "w4" -> NativeDrm.decryptFileWithKdPlanMeta(kds, input, output, "{}", null, null, 4)
                        else -> NativeDrm.decryptFileWithKdPlan(kds, input, output)
                    }
                    val out = java.io.File(output)
                    CadenceLog.i(
                        "decrypt",
                        "RESULT rc=" + rc + " bytes=" + out.length() + " ms=" + (System.currentTimeMillis() - t0) + " out=" + output,
                    )
                }.onFailure { CadenceLog.e("decrypt", "failed", it) }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_LOGIN") {
            val email = intent.getStringExtra("email").orEmpty()
            val password = intent.getStringExtra("password").orEmpty()
            GlobalScope.launch {
                CadenceLog.i("login", "start email=${email.substringBefore('@').take(2)}***")
                val r = app.cadence.backend.real.NativeAuth.start(email, password, appContext.filesDir.absolutePath)
                CadenceLog.i(
                    "login",
                    "start status=" + r.status + " kind=" + r.kind + " delivery=" + r.delivery +
                        " phones=" + r.phones.map { it.id.toString() + ":" + it.number } +
                        " msg=" + (r.message ?: "").take(120),
                )
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_LOGIN_CODE") {
            val code = intent.getStringExtra("code").orEmpty()
            GlobalScope.launch {
                val r = app.cadence.backend.real.NativeAuth.submit(code)
                CadenceLog.i("login", "submit status=" + r.status + " kind=" + r.kind +
                        " storefront=" + r.storefront + " tokenLen=" + (r.mediaUserToken?.length ?: 0) +
                        " msg=" + (r.message ?: "").take(160))
                if (r.status != "ok") {
                    // the code was already consumed; retry the token exchange on the live session
                    val again = app.cadence.backend.real.NativeAuth.resume(appContext.filesDir.absolutePath)
                    CadenceLog.i("login", "resume status=" + again.status + " kind=" + again.kind +
                            " msg=" + (again.message ?: "").take(160))
                }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_LIBRARY_ROUNDTRIP") {
            val appCtx = context.applicationContext
            GlobalScope.launch {
                runCatching {
                    val cur = appCtx.container.library.state.value
                    val json = cur.toBackupJson()
                    val restored = cur.restoreFromBackupJson(json, merge = true).getOrThrow()
                    CadenceLog.i(
                        "library_roundtrip",
                        "ok=true bytes=${json.length} playlists=${restored.playlists.size} tracks=${restored.savedTracks.size}",
                    )
                }.onFailure { CadenceLog.e("library_roundtrip", "failed", it) }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_ALAC_CHECK" || intent.action == "app.cadence.DEBUG_ALAC_ANALYZE") {
            val trackId = intent.getStringExtra("trackId") ?: ""
            val analyzePcm = intent.action == "app.cadence.DEBUG_ALAC_ANALYZE" ||
                intent.getBooleanExtra("analyzePcm", false)
            val app = context.applicationContext
            GlobalScope.launch {
                runCatching {
                    val repo = Backend.create(app).catalog
                    val report = repo.checkAlacIntegrity(trackId, analyzePcm = analyzePcm)
                    if (report == null) {
                        CadenceLog.i("alac_check", "trackId=$trackId report=null")
                    } else {
                        CadenceLog.i(
                            "alac_check",
                            "trackId=$trackId status=${report.status} frames=${report.totalFrames} " +
                                "escape=${report.escapeFrames} missingEnd=${report.missingEndFrames} " +
                                "dirtyPad=${report.dirtyPaddingFrames} bits=${report.bits} rate=${report.sampleRate} " +
                                "effBits=${report.effectiveBits} bwHz=${report.bandwidthHz} upsampled=${report.upsampledSuspect} " +
                                "sw=${report.software} source=${report.source}",
                        )
                    }
                }.onFailure { CadenceLog.e("alac_check", "failed trackId=$trackId", it) }
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_AUTH") {
            // Proves the JNI bridge resolves and reaches Apple, without real credentials.
            GlobalScope.launch {
                CadenceLog.i("auth", "probe available=" + app.cadence.backend.real.NativeAuth.available())
                val r = app.cadence.backend.real.NativeAuth.start("probe@example.invalid", "not-a-real-password", appContext.filesDir.absolutePath)
                CadenceLog.i("auth", "probe status=" + r.status + " kind=" + r.kind + " msg=" + (r.message ?: "").take(120))
                CadenceLog.i("auth", "probe done")
            }
            return
        }
        if (intent.action == "app.cadence.DEBUG_AUTOMIX_PLAN") {
            val outIdArg = intent.getStringExtra("outId")
            val inIdArg = intent.getStringExtra("inId")
            val crossfadeSec = intent.getIntExtra("crossfadeSec", 5).coerceIn(1, 12)
            GlobalScope.launch {
                runCatching {
                    val backend = runCatching { appContext.container.backend }.getOrNull() ?: Backend.create(appContext)
                    val tracks = backend.catalog.tracks
                    val outTrack = outIdArg?.let { id -> tracks.firstOrNull { it.track.id == id } } ?: tracks.getOrNull(0)
                    val inTrack = inIdArg?.let { id -> tracks.firstOrNull { it.track.id == id } } ?: tracks.getOrNull(1)
                    if (outTrack == null || inTrack == null) {
                        CadenceLog.i("AutoMix", "debug plan skipped: need at least 2 tracks in catalog (size=${tracks.size})")
                        return@launch
                    }
                    val outAnalysis = backend.catalog.audioAnalysis(outTrack.track.id)
                    val inAnalysis = backend.catalog.audioAnalysis(inTrack.track.id)
                    val sameAlbum = outTrack.album.id.isNotBlank() && outTrack.album.id == inTrack.album.id
                    val plan = app.cadence.backend.engine.media.AutoMixPlanner.plan(
                        outAnalysis = outAnalysis,
                        inAnalysis = inAnalysis,
                        outDurationMs = outTrack.track.durationMs.takeIf { it > 0L } ?: 180_000L,
                        inDurationMs = inTrack.track.durationMs.takeIf { it > 0L } ?: 180_000L,
                        sameAlbumConsecutive = sameAlbum,
                        fallbackCrossfadeMs = crossfadeSec * 1_000L,
                    )
                    CadenceLog.i(
                        "AutoMix",
                        "debug plan ${outTrack.track.id}->${inTrack.track.id} kind=${plan.kind} dur=${plan.durationMs}ms fadeOutAt=${plan.fadeOutStartMs}ms fadeInAt=${plan.fadeInOffsetMs}ms speed=${plan.speedRatio} reason=${plan.reason}",
                    )
                }.onFailure { CadenceLog.e("AutoMix", "debug plan failed", it) }
            }
            return
        }
        val query = intent.getStringExtra("q") ?: "daft punk"
        CadenceLog.i("debug", "DEBUG_SEARCH q='$query'")
        val app = context.applicationContext
        GlobalScope.launch {
            runCatching {
                val backend = Backend.create(app)
                CadenceLog.i("debug", "backend=${backend.javaClass.simpleName}")
                CadenceLog.i("debug", "session=${backend.auth.session.value?.displayName ?: "null"}")
                kotlinx.coroutines.delay(4_000) // let the browse seed finish
                CadenceLog.i(
                    "debug",
                    "catalog albums=${backend.catalog.albums.size} artists=${backend.catalog.artists.size} tracks=${backend.catalog.tracks.size}",
                )
                backend.catalog.albums.take(3).forEach { a ->
                    CadenceLog.i("debug", "  album ${a.title} / ${a.artistId} / tracks=${backend.catalog.tracksOfAlbum(a.id).size}")
                }
                val res = backend.search.search(query, SearchScope(libraryTrackIds = null))
                CadenceLog.i("debug", "results top=${res.top.size} byType=${res.byType.mapValues { it.value.size }}")
                res.top.take(5).forEach { hit ->
                    val label = when (hit) {
                        is SearchHit.AlbumHit -> "album:${hit.album.title} by ${hit.artistName}"
                        is SearchHit.ArtistHit -> "artist:${hit.artist.name}"
                        is SearchHit.SongHit -> "song:${hit.track.track.title}"
                        else -> hit.key
                    }
                    CadenceLog.i("debug", "  $label")
                }
                val firstAlbum = res.top.filterIsInstance<SearchHit.AlbumHit>().firstOrNull()
                if (firstAlbum != null) {
                    val tracks = backend.catalog.tracksOfAlbum(firstAlbum.album.id)
                    CadenceLog.i("debug", "album '${firstAlbum.album.title}' tracks=${tracks.size}")
                    tracks.take(3).forEach { t ->
                        CadenceLog.i("debug", "  ${t.track.number}. ${t.track.title} [${t.track.format?.codec}] audio=${t.track.audio.take(40)}")
                    }
                }
                (firstAlbum?.let { backend.catalog.tracksOfAlbum(it.album.id).firstOrNull() }
                    ?: backend.catalog.tracks.firstOrNull())?.let { t ->
                        val lyr = backend.catalog.lyrics(t)
                        val n = when (lyr) {
                            is app.cadence.backend.model.Lyrics.Synced -> lyr.lines.size
                            is app.cadence.backend.model.Lyrics.Plain -> lyr.text.lines().size
                            else -> 0
                        }
                    CadenceLog.i("debug", "lyrics '${t.track.title}' -> ${lyr::class.simpleName} lines=$n")
                }
                CadenceLog.i("debug", "DONE")
            }.onFailure { CadenceLog.e("debug", "search failed", it) }
        }
    }
}

/** The user's replay gain settings while a test has them overridden (for the life of the process). */
private var savedReplayGain: app.cadence.state.settings.ReplayGainConfig? = null
