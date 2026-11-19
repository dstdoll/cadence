package app.cadence

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.core.design.ArtworkRenderer
import app.cadence.core.theme.CadenceTheme
import app.cadence.feature.AppShell
import app.cadence.feature.auth.AuthFlow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith

class MainActivity : ComponentActivity() {
    private val openPlayer = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        handle(intent)
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)

        val container = container
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            // Only the album id matters at the root; a full PlayerState here would recompose the whole tree on every player event.
            val seedFlow = remember { container.player.state.map { it.current?.album?.colors }.distinctUntilChanged() }
            val colors by seedFlow.collectAsStateWithLifecycle(null)
            val seed = remember(colors) { colors?.let(ArtworkRenderer::seed) }
            CompositionLocalProvider(LocalContainer provides container) {
                CadenceTheme(settings, seed) {
                    // Signed out (first launch or after Sign Out) -> sign-in flow; signed in -> the app.
                    val session by container.backend.auth.session.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        AnimatedContent(session != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "gate") { signedIn ->
                            if (signedIn) AppShell(openPlayer.intValue) else AuthFlow()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * Android 15+ defaults apps to the "normal" frame-rate category (60 Hz on many panels).
     * Pin the window to the panel's fastest mode at native resolution so scrolling and springs run at 120 Hz.
     */
    private fun requestHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.intValue++
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
