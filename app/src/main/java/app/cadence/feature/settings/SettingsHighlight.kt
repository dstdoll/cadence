package app.cadence.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cadence.LocalContainer
import kotlinx.coroutines.delay

/**
 * The option a settings search opened. Its page scrolls until the option's row is laid out; the row
 * then moves into view and pulses in the accent colour, and the target clears.
 */
internal class SettingsHighlight {
    /** The row title to find, or null. */
    var target by mutableStateOf<String?>(null)

    /** Set by the row once it is laid out, so the page stops scrolling. */
    var found by mutableStateOf(false)

    fun open(title: String) {
        found = false
        target = title
    }
}

internal val LocalSettingsHighlight = staticCompositionLocalOf { SettingsHighlight() }

/**
 * The modifier for a settings row titled [title]: nothing, unless it is the search target. Then it
 * brings the row into view and pulses twice (one steady tint with Reduce motion on).
 */
@Composable
internal fun rememberHighlight(title: String): Modifier {
    val highlight = LocalSettingsHighlight.current
    if (highlight.target != title.substringBefore("·").trim()) return Modifier
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val reduceMotion = LocalContainer.current.settings.state.collectAsStateWithLifecycle().value.reduceMotion
    LaunchedEffect(title) {
        highlight.found = true
        delay(300) // the page's own entrance first
        requester.bringIntoView()
        if (reduceMotion) {
            glow.snapTo(1f)
            delay(1200)
            glow.animateTo(0f, tween(400))
        } else {
            repeat(2) {
                glow.animateTo(1f, tween(280))
                glow.animateTo(0f, tween(520))
            }
        }
        highlight.target = null
    }
    val accent = MaterialTheme.colorScheme.primary
    return Modifier
        .bringIntoViewRequester(requester)
        .drawBehind { drawRect(accent.copy(alpha = 0.16f * glow.value)) }
}

/**
 * The list state for a settings page: while a search target is pending and not yet laid out, the list
 * scrolls down a screen at a time so the target's row gets composed and can bring itself into view.
 */
@Composable
internal fun rememberSettingsListState(): LazyListState {
    val state = rememberLazyListState()
    val highlight = LocalSettingsHighlight.current
    LaunchedEffect(highlight.target) {
        if (highlight.target == null) return@LaunchedEffect
        delay(350)
        while (highlight.target != null && !highlight.found && state.canScrollForward) {
            state.animateScrollBy(state.layoutInfo.viewportSize.height * 0.6f)
        }
    }
    return state
}
