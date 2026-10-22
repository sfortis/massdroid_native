package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Where a TV content area stands before it has anything to show. */
enum class TvLoad { LOADING, FAILED, READY }

/** A line of text in place of content: loading, empty, or why it is missing. */
@Composable
fun TvContentMessage(text: String, edge: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalContentColor.current.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = edge)
    )
}

/**
 * Content that did not load, with a Retry that takes the cursor, since nothing else on the
 * screen may be focusable and an empty screen otherwise leaves OK with nowhere to go.
 */
@Composable
fun TvLoadFailed(message: String, edge: Dp, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    Column {
        TvContentMessage(message, edge)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(horizontal = edge)
                .focusRequester(retryFocus)
                .pointerClick(onClick = onRetry)
        ) {
            Text("Retry")
        }
    }
}
