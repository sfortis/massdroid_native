package net.asksakis.massdroidv2.tv.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.asksakis.massdroidv2.data.websocket.ConnectionState

/** Routes that float the corner mini player (browsing surfaces; never over the full player). */
private val MINI_PLAYER_ROUTES = setOf("home", "browse", "serverbrowse")

/**
 * Top-level TV destination switch:
 *  - Connected            -> navigable home / now-playing graph
 *  - saved server, not yet -> "connecting" (auto-retry, no forced re-login)
 *  - no saved server       -> onboarding/login
 */
@Composable
fun TvRoot(
    miniPlayerShortcut: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    viewModel: TvRootViewModel = hiltViewModel()
) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val hasSavedServer by viewModel.hasSavedServer.collectAsStateWithLifecycle()
    val changeServer by viewModel.changeServerRequested.collectAsStateWithLifecycle()
    val initialized by viewModel.initialized.collectAsStateWithLifecycle()

    when {
        connection is ConnectionState.Connected -> {
            val nav = rememberNavController()
            val backStackEntry by nav.currentBackStackEntryAsState()
            val route = backStackEntry?.destination?.route
            val miniPlayerVisible = route in MINI_PLAYER_ROUTES || route?.startsWith("artist/") == true
            val miniPlayerFocus = remember { FocusRequester() }
            // True while the mini player is expanded or holds the cursor: it owns BACK then,
            // and the home exit-confirmation must stay out of the way regardless of the order
            // the two BackHandlers were (re)registered in.
            var miniPlayerActive by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current
            // Screens register their own last-focused-item restore hook here (they already
            // track it for back-stack restoration); the mini player exit uses it, falling
            // back to a spatial move on screens that don't.
            val focusMemory = remember { TvFocusMemory() }
            val restoreContentFocus: () -> Unit = {
                val restored = runCatching { focusMemory.restoreToLastFocused?.invoke() == true }
                if (restored.getOrDefault(false) != true) {
                    if (!focusManager.moveFocus(FocusDirection.Up)) {
                        focusManager.clearFocus(force = true)
                        focusManager.moveFocus(FocusDirection.Up)
                    }
                }
            }
            // The pill is reachable ONLY by the deliberate hold-BACK shortcut — never by
            // scrolling/navigating the content (it must not behave like a "last row"), so
            // there is no DPAD-DOWN interception here.
            Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTvFocusMemory provides focusMemory) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    ExitConfirmation(enabled = !miniPlayerActive)
                    TvHomeScreen(
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenArtist = { itemId, provider ->
                            nav.navigate("artist/${Uri.encode(itemId)}/${Uri.encode(provider)}")
                        },
                        onOpenBrowse = { nav.navigate("browse") }
                    )
                }
                composable("browse") {
                    TvBrowseScreen(
                        onOpenArtist = { itemId, provider ->
                            nav.navigate("artist/${Uri.encode(itemId)}/${Uri.encode(provider)}")
                        },
                        onOpenFolders = { nav.navigate("serverbrowse") }
                    )
                }
                composable("serverbrowse") { TvServerBrowseScreen() }
                composable(
                    route = "nowplaying/{playerId}",
                    arguments = listOf(navArgument("playerId") { type = NavType.StringType })
                ) {
                    TvNowPlayingScreen(onOpenQueue = { nav.navigate("queue") })
                }
                composable("queue") { TvQueueScreen() }
                composable(
                    route = "artist/{itemId}/{provider}",
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.StringType },
                        navArgument("provider") { type = NavType.StringType }
                    )
                ) {
                    TvArtistScreen()
                }
                composable("settings") { TvSettingsScreen() }
            }
            }
            // Floating corner mini player over the browsing surfaces: collapsed pill,
            // expand on OK, player switching + open-full-player from there.
            if (miniPlayerVisible) {
                TvMiniPlayer(
                    onOpenPlayer = { playerId -> nav.navigate("nowplaying/$playerId") },
                    expandSignal = miniPlayerShortcut,
                    entryFocus = miniPlayerFocus,
                    onExitToContent = restoreContentFocus,
                    onActiveChange = { miniPlayerActive = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 48.dp, bottom = 32.dp)
                )
            }
            // Volume OSD: pops on any volume change of the selected player, fades on its own.
            TvVolumeOsd(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
            )
            }
        }
        (!initialized || hasSavedServer) && !changeServer -> {
            TvConnectingScreen(server = viewModel.serverLabel(), onChangeServer = { viewModel.changeServer() })
        }
        else -> TvOnboardingScreen()
    }
}

private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/** Double-BACK to exit: the root screen asks for a second press instead of quitting outright. */
@Composable
private fun ExitConfirmation(enabled: Boolean = true) {
    val context = LocalContext.current
    var armedAtMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = enabled) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - armedAtMs < EXIT_CONFIRM_WINDOW_MS) {
            (context as? Activity)?.finish()
        } else {
            armedAtMs = now
            Toast.makeText(context, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
        }
    }
}
