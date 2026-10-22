package net.asksakis.massdroidv2.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/**
 * A [LaunchedEffect] that runs [block] only while the screen is visible (lifecycle STARTED),
 * and starts it again from the top when the screen comes back.
 *
 * The continuous animations step with delay(50) + snapTo() instead of the frame clock, so
 * nothing stopped them when the screen went off: each kept waking the CPU 20 times a second
 * until the app was closed.
 */
@Composable
fun LaunchedWhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED, block)
    }
}
