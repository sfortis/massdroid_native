package net.asksakis.massdroidv2.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

private const val INITIAL_FOCUS_TRIES = 25
private const val INITIAL_FOCUS_RETRY_MS = 60L

/** Ask for focus until the target is attached; a screen composed during a crossfade takes a few frames. */
suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(INITIAL_FOCUS_TRIES) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(INITIAL_FOCUS_RETRY_MS)
    }
}

/**
 * A focus target that takes the cursor when the screen first appears.
 *
 * Navigation crossfades for 700 ms, and a screen that asked for no focus left it on the previous
 * screen's button: a quick second OK navigated twice, and once that screen was gone nothing had
 * focus, so OK did nothing until a D-pad move. [onFocused] runs once the request went through,
 * for a screen that should take focus only on its first entry.
 */
@Composable
fun rememberInitialFocus(enabled: Boolean = true, onFocused: () -> Unit = {}): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        requester.requestFocusWhenAttached()
        onFocused()
    }
    return requester
}
