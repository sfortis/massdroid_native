package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the host window's view as keep-screen-on for the duration of the
 * composition. Restores the previous flag on dispose so we don't override a
 * keep-screen-on that was already requested elsewhere.
 */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previous
        }
    }
}
