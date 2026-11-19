package app.cadence.core.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow

/** Top-level tab or pushed screen: an expressive large, collapsing title over a lazy list. */
@Composable
fun ListScaffold(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    showSettings: Boolean = !showBack,
    /** Overrides the default back action (used by Settings sub-pages). */
    onBack: (() -> Unit)? = null,
    /** Detail pages draw their own hero header; the bar stays compact and shows the title once it scrolls away. */
    largeTitle: Boolean = true,
    /** When non-null, the list supports pull-to-refresh and calls this on a pull. */
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    actions: @Composable () -> Unit = {},
    /** The list's scroll state, for pages that scroll themselves (Settings search targets). */
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNavigator.current
    val scroll = if (largeTitle) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!largeTitle) {
                val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
                TopAppBar(
                    title = {
                        AnimatedVisibility(showTitle, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack ?: nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                    },
                    actions = { actions() },
                    scrollBehavior = scroll,
                )
            } else LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = subtitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack ?: nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                },
                actions = {
                    actions()
                    if (showSettings) IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val cp = PaddingValues(top = padding.calculateTopPadding(), bottom = LocalBottomInset.current)
        if (onRefresh != null) {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = cp, content = content)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = cp, content = content)
        }
    }
}
