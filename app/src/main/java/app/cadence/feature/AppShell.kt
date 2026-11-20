package app.cadence.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.cadence.LocalContainer
import app.cadence.core.design.LibraryKind
import app.cadence.core.design.LocalBottomInset
import app.cadence.core.design.LocalNavigator
import app.cadence.core.design.ChromeState
import app.cadence.core.design.LocalChrome
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import app.cadence.core.design.Navigator
import app.cadence.feature.browse.BrowseScreen
import app.cadence.feature.detail.AlbumScreen
import app.cadence.feature.detail.ArtistScreen
import app.cadence.feature.detail.PlaylistScreen
import app.cadence.feature.home.HomeScreen
import app.cadence.feature.profile.ProfileScreen
import app.cadence.feature.radio.RadioScreen
import app.cadence.feature.library.LibraryScreen
import app.cadence.feature.library.LibrarySectionScreen
import app.cadence.feature.nowplaying.MiniPlayer
import app.cadence.feature.nowplaying.NowPlaying
import app.cadence.feature.search.SearchScreen
import app.cadence.feature.settings.SettingsScreen
import app.cadence.feature.settings.EqualizerScreen
import app.cadence.feature.settings.ToneControlsScreen
import app.cadence.feature.settings.ReplayGainScreen
import app.cadence.feature.settings.BalanceScreen
import app.cadence.feature.settings.DitherScreen
import app.cadence.feature.download.DownloadsWindow
import app.cadence.backend.engine.DownloadEngine
import app.cadence.feature.detail.CreditsScreen
import app.cadence.feature.detail.GenreScreen
import app.cadence.feature.library.EditLibraryScreen
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object BrowseRoute
@Serializable data object RadioRoute
@Serializable data object LibraryRoute
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data class AlbumRoute(val id: String, val songId: String? = null)
@Serializable data class ArtistRoute(val id: String)
@Serializable data class PlaylistRoute(val id: String)
@Serializable data class LibrarySectionRoute(val kind: LibraryKind)
@Serializable data class CreditsRoute(val trackId: String)
@Serializable data class GenreRoute(val name: String)
@Serializable data object EditLibraryRoute
@Serializable data object EqualizerRoute
@Serializable data object ToneRoute
@Serializable data object ReplayGainRoute
@Serializable data object BalanceRoute
@Serializable data object DitherRoute
@Serializable data object ProfileRoute

private data class Tab(val route: Any, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(BrowseRoute, R.string.nav_browse, Icons.Outlined.Explore, Icons.Rounded.Explore),
    Tab(RadioRoute, R.string.nav_radio, Icons.Outlined.Radio, Icons.Rounded.Radio),
    Tab(LibraryRoute, R.string.nav_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Tab(SearchRoute, R.string.nav_search, Icons.Outlined.Search, Icons.Rounded.Search),
)

@Composable
fun AppShell(openPlayerRequest: Int, linkText: String? = null, linkRequest: Int = 0) {
    val c = LocalContainer.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val hasTrack = playerState.current != null
    val downloadJobs by DownloadEngine.jobs.collectAsStateWithLifecycle()
    val downloadsOpen by c.downloadsOpen.collectAsStateWithLifecycle()
    val enqueueEvents by DownloadEngine.enqueueEvents.collectAsStateWithLifecycle()
    var knownEnqueueEvent by remember { androidx.compose.runtime.mutableLongStateOf(enqueueEvents) }

    // 0 = collapsed into the mini player, 1 = full-screen Now Playing.
    val expand = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val springSpec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    fun settle(to: Float) = scope.launch { expand.animateTo(to, springSpec) }

    val navigator = remember(nav) {
        // Opening a page from the full-screen player lowers the player first; otherwise the page
        // opened behind it and the tap looked like it did nothing.
        fun lower() { if (expand.targetValue > 0.5f) settle(0f) }
        object : Navigator {
            override fun album(id: String) { lower(); nav.navigate(AlbumRoute(id)) }
            override fun artist(id: String) { lower(); nav.navigate(ArtistRoute(id)) }
            override fun playlist(id: String) { lower(); nav.navigate(PlaylistRoute(id)) }
            override fun library(kind: LibraryKind) { lower(); nav.navigate(LibrarySectionRoute(kind)) }
            override fun settings() { lower(); nav.navigate(SettingsRoute) }
            override fun credits(trackId: String) { lower(); nav.navigate(CreditsRoute(trackId)) }
            override fun genre(name: String) { lower(); nav.navigate(GenreRoute(name)) }
            override fun editLibrary() { lower(); nav.navigate(EditLibraryRoute) }
            override fun equalizer() { lower(); nav.navigate(EqualizerRoute) }
            override fun toneControls() { lower(); nav.navigate(ToneRoute) }
            override fun replayGain() { lower(); nav.navigate(ReplayGainRoute) }
            override fun balance() { lower(); nav.navigate(BalanceRoute) }
            override fun dither() { lower(); nav.navigate(DitherRoute) }
            override fun profile() { lower(); nav.navigate(ProfileRoute) }
            override fun back() { nav.popBackStack() }
        }
    }
    val linkCtx = androidx.compose.ui.platform.LocalContext.current
    // A shared/opened Apple Music link: look it up, then open its page (latest link wins; handled once per delivery).
    LaunchedEffect(linkRequest) {
        val link = app.cadence.backend.links.AppleLinks.parse(linkText) ?: return@LaunchedEffect
        val target = c.repository.resolveLink(link)
        app.cadence.backend.engine.CadenceLog.i("link", "kind=${link::class.simpleName} -> ${target::class.simpleName}")
        when (target) {
            is app.cadence.backend.links.LinkTarget.Open -> {
                if (expand.targetValue > 0.5f) settle(0f)
                when (target.kind) {
                    app.cadence.backend.links.LinkTarget.Kind.Album -> nav.navigate(AlbumRoute(target.id, target.selectSongId))
                    app.cadence.backend.links.LinkTarget.Kind.Artist -> nav.navigate(ArtistRoute(target.id))
                    app.cadence.backend.links.LinkTarget.Kind.Playlist -> nav.navigate(PlaylistRoute(target.id))
                }
            }
            is app.cadence.backend.links.LinkTarget.NotFound -> android.widget.Toast.makeText(linkCtx, R.string.link_not_found, android.widget.Toast.LENGTH_LONG).show()
            is app.cadence.backend.links.LinkTarget.Unsupported -> android.widget.Toast.makeText(linkCtx, R.string.link_unsupported, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(openPlayerRequest) { if (openPlayerRequest > 0 && hasTrack) settle(1f) }
    LaunchedEffect(hasTrack) { if (!hasTrack) expand.snapTo(0f) }
    // Any enqueue (single, album, playlist, re-download) opens the window. Progress
    // ticks do not, so a dismissal stays dismissed until something new is queued.
    LaunchedEffect(enqueueEvents) {
        if (enqueueEvents > knownEnqueueEvent) c.downloadsOpen.value = true
        knownEnqueueEvent = enqueueEvents
    }

    // Predictive back: the sheet follows the back gesture, then collapses (or snaps back if cancelled).
    // It is an overlay handler, so while the player is up, back always lowers it first instead of
    // popping the page hidden underneath (page navigation registers at the default priority).
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val playerBack = remember {
        PlayerBackHandler(
            onProgress = { p -> scope.launch { expand.snapTo(1f - p * 0.25f) } },
            onCommit = { settle(0f) },
            onCancel = { settle(1f) },
        )
    }
    DisposableEffect(dispatcher) {
        dispatcher?.addHandler(playerBack, NavigationEventDispatcher.PRIORITY_OVERLAY)
        onDispose { playerBack.remove() }
    }
    LaunchedEffect(playerBack) { snapshotFlow { expand.targetValue > 0.5f }.collect { playerBack.isBackEnabled = it } }

    val chrome = remember { ChromeState() }
    CompositionLocalProvider(LocalNavigator provides navigator, LocalChrome provides chrome) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heightPx = constraints.maxHeight.toFloat()
            // Undispatched so the sheet tracks the finger in the same frame (no stale reads between events).
            fun snap(v: Float) = scope.launch(start = CoroutineStart.UNDISPATCHED) { expand.snapTo(v.coerceIn(0f, 1f)) }
            // Asymmetric thresholds: a short pull or any downward flick dismisses; opening needs a clear intent.
            fun release(velocityY: Float) {
                val wasOpen = expand.targetValue >= 1f || expand.value > 0.5f
                settle(
                    when {
                        velocityY > 500f -> 0f
                        velocityY < -500f -> 1f
                        wasOpen -> if (expand.value < 0.8f) 0f else 1f
                        else -> if (expand.value > 0.25f) 1f else 0f
                    },
                )
            }

            val sheetDrag = Modifier.pointerInput(heightPx) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { release(tracker.calculateVelocity().y) },
                    onDragCancel = { release(0f) },
                ) { change, dy ->
                    change.consume()
                    tracker.addPointerInputChange(change) // includes historical samples, so quick flicks register
                    snap(expand.value - dy / heightPx)
                }
            }
            // Lets scrolling lists (lyrics, queue) hand off to the sheet: pulling down past their top collapses it,
            // and scrolling back up re-expands the sheet before the list moves.
            val sheetScroll = remember(heightPx) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput || available.y >= 0f || expand.value >= 1f) return Offset.Zero
                        val before = expand.value
                        val after = (before - available.y / heightPx).coerceAtMost(1f)
                        snap(after)
                        return Offset(0f, -(after - before) * heightPx)
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                        snap(expand.value - available.y / heightPx)
                        return Offset(0f, available.y)
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (expand.value >= 1f) return Velocity.Zero
                        release(available.y)
                        return available
                    }
                }
            }

            val adaptiveType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
            NavigationSuiteScaffold(
                layoutType = if (chrome.immersive) NavigationSuiteType.None else adaptiveType,
                navigationSuiteItems = {
                    tabs.forEach { tab ->
                        val selected = entry?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                        item(
                            selected = selected,
                            onClick = {
                                settle(0f)
                                c.downloadsOpen.value = false
                                // Pressing the tab you are already "under" (e.g. Home while
                                // Settings is open) must behave like back: pop the stacked
                                // destination first. navigate(Home) alone is a no-op there.
                                nav.popBackStack(tab.route, inclusive = false)
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomInset provides if (hasTrack) 88.dp else 16.dp) {
                        NavHost(
                            nav, startDestination = HomeRoute,
                            enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
                            exitTransition = { fadeOut() + scaleOut(targetScale = 0.96f) },
                            popEnterTransition = { fadeIn() + scaleIn(initialScale = 0.96f) },
                            popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
                        ) {
                            composable<HomeRoute> { HomeScreen() }
                            composable<BrowseRoute> { BrowseScreen() }
                            composable<RadioRoute> { RadioScreen() }
                            composable<LibraryRoute> { LibraryScreen() }
                            composable<SearchRoute> { SearchScreen() }
                            composable<SettingsRoute> { SettingsScreen() }
                            composable<AlbumRoute> { it.toRoute<AlbumRoute>().let { r -> AlbumScreen(r.id, r.songId) } }
                            composable<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id) }
                            composable<PlaylistRoute> { PlaylistScreen(it.toRoute<PlaylistRoute>().id) }
                            composable<LibrarySectionRoute> { LibrarySectionScreen(it.toRoute<LibrarySectionRoute>().kind) }
                            composable<CreditsRoute> { CreditsScreen(it.toRoute<CreditsRoute>().trackId) }
                            composable<GenreRoute> { GenreScreen(it.toRoute<GenreRoute>().name) }
                            composable<EditLibraryRoute> { EditLibraryScreen() }
                            composable<EqualizerRoute> { EqualizerScreen() }
                            composable<ToneRoute> { ToneControlsScreen() }
                            composable<ReplayGainRoute> { ReplayGainScreen() }
                            composable<BalanceRoute> { BalanceScreen() }
                            composable<DitherRoute> { DitherScreen() }
                            composable<ProfileRoute> { ProfileScreen() }
                        }
                    }
                    if (hasTrack && !chrome.immersive) {
                        MiniPlayer(
                            onExpand = { settle(1f) },
                            dragModifier = sheetDrag,
                            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - (expand.value * 3f).coerceAtMost(1f) },
                        )
                    }
                }
            }

            // Only the visibility flip recomposes; the per-frame sheet position is read in the layer lambda below.
            val sheetVisible by remember { derivedStateOf { expand.value > 0.001f } }
            if (hasTrack && sheetVisible) {
                val cornerPx = with(LocalDensity.current) { 32.dp.toPx() }
                NowPlaying(
                    onCollapse = { settle(0f) },
                    modifier = Modifier
                        .then(sheetDrag)
                        .nestedScroll(sheetScroll)
                        .graphicsLayer {
                            val p = expand.value
                            translationY = (1f - p) * heightPx
                            val s = 0.9f + 0.1f * p
                            scaleX = s; scaleY = s
                            alpha = (p * 2.5f).coerceAtMost(1f)
                            shape = RoundedCornerShape(((1f - p) * 4f).coerceIn(0f, 1f) * cornerPx)
                            clip = true
                        },
                )
            }

            if (downloadsOpen) {
                DownloadsWindow(downloadJobs) { c.downloadsOpen.value = false }
            }
            app.cadence.feature.download.DownloadRequestHost()
        }
    }
}

/** Shared header style for top-level tabs. */
@Composable
fun TabTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineLarge)

/** Back for the full-screen player, registered as an overlay above page navigation. */
private class PlayerBackHandler(
    private val onProgress: (Float) -> Unit,
    private val onCommit: () -> Unit,
    private val onCancel: () -> Unit,
) : NavigationEventHandler<NavigationEventInfo>(PlayerSheetInfo, false) {
    override fun onBackProgressed(event: NavigationEvent) = onProgress(event.progress)
    override fun onBackCompleted() = onCommit()
    override fun onBackCancelled() = onCancel()
}

private object PlayerSheetInfo : NavigationEventInfo()
