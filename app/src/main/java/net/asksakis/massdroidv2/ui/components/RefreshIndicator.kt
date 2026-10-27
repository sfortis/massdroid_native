package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The app's pull to refresh: [PullToRefreshBox] with [MdRefreshIndicator].
 *
 * The Material box hides its spinner only when it sees [isRefreshing] go from true to false.
 * A refresh that finishes within the same frame never shows that change, and the spinner stayed
 * on screen where the finger let go. That is every library refresh of an account that is not an
 * admin (2026-10-27, Mi MIX 2S on the Play review account): the server refuses `music/sync` at
 * once and the tab reloads in a few milliseconds. So once a refresh the user started has ended,
 * the spinner is put away here as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable BoxScope.() -> Unit,
) {
    var refreshesStarted by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshesStarted, isRefreshing) {
        if (refreshesStarted > 0 && !isRefreshing) state.animateToHidden()
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            refreshesStarted++
            onRefresh()
        },
        modifier = modifier,
        state = state,
        indicator = { MdRefreshIndicator(state, isRefreshing) },
        content = content,
    )
}

/**
 * The pull to refresh spinner, in colours that work in both themes.
 *
 * Material fills it with `surfaceContainerHigh` and draws the arrow in `onSurfaceVariant`.
 * Against this app's light palette that is a dark grey disc with a dark grey arrow sitting
 * on a lighter background, which reads as a smudge rather than as a control. The disc here
 * stays close to the background and the arrow takes the full text colour, so what the eye
 * follows is the arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.MdRefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean) {
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = isRefreshing,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.TopCenter)
    )
}
