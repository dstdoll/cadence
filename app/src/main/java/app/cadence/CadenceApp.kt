package app.cadence

import app.cadence.state.settings.downloadPolicy
import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import app.cadence.state.JsonStore
import app.cadence.backend.api.MusicRepository
import app.cadence.backend.api.Backend
import app.cadence.backend.api.BackendServices
import app.cadence.state.library.UserLibrary
import app.cadence.state.settings.UserSettings
import app.cadence.playback.PlayerController
import app.cadence.backend.engine.DownloadEngine
import app.cadence.backend.engine.DownloadPreferences
import app.cadence.state.settings.AlbumDateStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Hand-rolled DI: one graph for the process, shared by the UI and the playback service. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The only line that knows which backend is in use; see app.cadence.backend.api.Backend. */
    val backend: BackendServices = Backend.create(context)
    val repository: MusicRepository = backend.catalog
    val settings = JsonStore(context, scope, "settings", UserSettings.serializer(), UserSettings())
    val updates = app.cadence.state.UpdateChecker(context, settings, scope)
    val library = JsonStore(context, scope, "library", UserLibrary.serializer(), UserLibrary())
    val player by lazy { PlayerController(context, repository, backend.recommendations, library, settings, scope) }


    /** Audio analysis from the playback service (levels and waveform energy), read by Now Playing. */
    val wave = app.cadence.playback.WaveHub()

    /** Global downloads window (ModalBottomSheet) so any screen can open it. */
    val downloadsOpen = MutableStateFlow(false)

    init {
        // Feed the download engine the moment settings exist, then on every edit.
        DownloadEngine.configure(settings.state.value.toDownloadPreferences())
        scope.launch {
            settings.state.collect { DownloadEngine.configure(it.toDownloadPreferences()) }
        }
        updates.checkIfDue()
        // Every download (song, album, playlist) goes through this, which may ask which quality to use.
        app.cadence.feature.download.DownloadRequests.attach(context, settings, scope)
        // What each song's masters offer (cached), so album pages can say "Hi-Res Lossless" when every song has it.
        app.cadence.backend.engine.media.QualityIndex.attach(context)
        app.cadence.backend.engine.media.DownloadRegistry.attach(context, scope)
    }
}


class CadenceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NativeNoticeToasts.install(this)
    }
}

val Context.container get() = (applicationContext as CadenceApp).container

/** Maps the app's persisted settings onto the engine's amdl-style options. */
internal fun UserSettings.toDownloadPreferences() = DownloadPreferences(
    defaultTreeUri = downloadTreeUri,
    albumTreeUri = albumTreeUri,
    playlistTreeUri = playlistTreeUri,
    singleTreeUri = singleTreeUri,
    albumFolderTemplate = albumFolderTemplate,
    trackFileTemplate = trackFileTemplate,
    playlistFolderTemplate = playlistFolderTemplate,
    playlistTrackFileTemplate = playlistTrackFileTemplate,
    albumDate = when (albumDateTag) {
        AlbumDateStyle.FullDate -> "full"
        AlbumDateStyle.YearOnly -> "year"
        AlbumDateStyle.None -> "none"
    },
    writeMetadata = writeMetadata,
    embedCover = embedCover,
    coverSize = coverSize,
    coverFormat = coverFormat,
    embedLyrics = embedLyrics,
    lyricsFormat = lyricsFormat,
    bitDepth = bitDepth,
    maxSampleRate = maxSampleRate,
    codec = downloadCodec,
    streamCodec = streamCodec,
    streamAtmos = streamAtmos,
    atmos = downloadAtmos,
    shareStreamCache = shareDownloadCache,
    quality = downloadPolicy(),
)

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }
