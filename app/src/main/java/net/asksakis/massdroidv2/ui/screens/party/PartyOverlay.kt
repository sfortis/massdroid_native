package net.asksakis.massdroidv2.ui.screens.party

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * The Party screen, slid in over Now Playing from the side and out again on Back, on the
 * idle close and when the plugin goes away.
 *
 * It is drawn over Now Playing instead of being a destination of its own. The expanding
 * player is an overlay above every destination, so a destination opened from it could only
 * be shown with the player closed, and coming back meant a frame of the screen underneath and
 * the player opening again. Here the player never moves, in the sheet and on the full-screen
 * route alike.
 */
@Composable
fun PartyOverlay(visible: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { it },
        exit = slideOutHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { it }
    ) {
        val activity = LocalContext.current.componentActivity()
        // Bound to the activity, like the NavHost's own Back handler. On the full-screen route
        // the destination's lifecycle restarts before the NavHost re-adds its handler, so a
        // handler bound to the destination would lose to it after the app came back from the
        // background, and Back would leave Now Playing as well.
        CompositionLocalProvider(LocalLifecycleOwner provides activity) {
            BackHandler(onBack = onClose)
        }
        val owner = rememberPartyViewModelStoreOwner(activity)
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Nothing of Party is drawn beside the sliding panel.
                .clipToBounds()
                // Vertical drags the screen does not use itself would otherwise reach the
                // expanding player under it and pull it closed with Party inside.
                .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> change.consume() } }
        ) {
            PartyScreen(onBack = onClose, viewModel = hiltViewModel(owner))
        }
    }
}

/**
 * A ViewModel store that lives as long as the open Party screen. In the expanding player the
 * nearest owner is the activity, which would keep the party's reads going on every reconnect
 * long after the screen closed.
 */
@Composable
private fun rememberPartyViewModelStoreOwner(activity: ComponentActivity): ViewModelStoreOwner {
    val owner = remember(activity) { PartyViewModelStoreOwner(activity) }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

/** Creates its ViewModels through the activity's Hilt factory, in a store of its own. */
private class PartyViewModelStoreOwner(
    private val activity: ComponentActivity
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras
        get() = activity.defaultViewModelCreationExtras
}

private tailrec fun Context.componentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> error("Party needs an activity")
}

private const val SLIDE_MS = 300
