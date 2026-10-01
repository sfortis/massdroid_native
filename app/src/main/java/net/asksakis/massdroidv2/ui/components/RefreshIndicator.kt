package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

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
