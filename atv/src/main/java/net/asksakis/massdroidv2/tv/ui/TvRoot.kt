package net.asksakis.massdroidv2.tv.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import net.asksakis.massdroidv2.data.websocket.ConnectionState

/** Routes that show the mini player bar (browsing surfaces; never under the full player). */
private val MINI_PLAYER_ROUTES = setOf("home", "browse", "serverbrowse")

/**
 * Top-level TV destination switch:
 *  - Connected            -> navigable home / now-playing graph
 *  - connected before, now dropping -> the same graph, kept with its back stack and
 *    focus, under a "Reconnecting" notice
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
    val sessionEstablished by viewModel.sessionEstablished.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected

    when {
        connected || (sessionEstablished && hasSavedServer && !changeServer) -> {
            val nav = rememberNavController()
            val backStackEntry by nav.currentBackStackEntryAsState()
            val route = backStackEntry?.destination?.route
            val miniPlayerVisible = route in MINI_PLAYER_ROUTES || route?.startsWith("artist/") == true
            val miniPlayerFocus = remember { FocusRequester() }
            // True while the mini player bar holds the cursor: it owns BACK and the D-pad then,
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
            // Both deliberate ways into the mini player bar: holding BACK, and DOWN with nothing
            // below in the content.
            val miniPlayerEntry = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
            if (miniPlayerShortcut != null) {
                LaunchedEffect(miniPlayerShortcut) { miniPlayerShortcut.collect { miniPlayerEntry.tryEmit(Unit) } }
            }
            // The bar is drawn over the screens instead of below them, so opening the full
            // player is one step: the bar fades out together with the screen it belongs to,
            // and the player fades in at full height. Before, the bar was removed at once and
            // the screen grew, and only then did the crossfade start. The screens that show
            // the bar leave its height empty at their bottom (MiniPlayerSpace), so nothing
            // they draw or focus sits behind it.
            var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
            val miniPlayerHeight = with(LocalDensity.current) { miniPlayerHeightPx.toDp() }
            Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTvFocusMemory provides focusMemory) {
            NavHost(
                navController = nav,
                startDestination = "home",
                enterTransition = { fadeIn(tween(NAV_FADE_MS)) },
                exitTransition = { fadeOut(tween(NAV_FADE_MS)) },
                popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
                popExitTransition = { fadeOut(tween(NAV_FADE_MS)) },
                modifier = Modifier
                    .fillMaxSize()
                    // DOWN in the content moves focus down as usual; only when nothing is below
                    // does it enter the bar. The bar is never a candidate of the focus search
                    // itself (it is not focusable until entered), so content focus can never
                    // fall onto it by accident.
                    .onPreviewKeyEvent { event ->
                        if (!miniPlayerVisible || miniPlayerActive) return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
                            return@onPreviewKeyEvent false
                        }
                        // Only a fresh press enters the bar: a held DOWN scrolling a list that is
                        // still loading its next page must not drop into it.
                        val moved = focusManager.moveFocus(FocusDirection.Down)
                        if (!moved && event.nativeKeyEvent.repeatCount == 0) miniPlayerEntry.tryEmit(Unit)
                        true
                    }
            ) {
                composable("home") {
                    ExitConfirmation(enabled = !miniPlayerActive)
                    MiniPlayerSpace(miniPlayerHeight) {
                        TvHomeScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenArtist = { itemId, provider ->
                                nav.navigate("artist/${Uri.encode(itemId)}/${Uri.encode(provider)}")
                            },
                            onOpenBrowse = { nav.navigate("browse") }
                        )
                    }
                }
                composable("browse") {
                    MiniPlayerSpace(miniPlayerHeight) {
                        TvBrowseScreen(
                            onOpenArtist = { itemId, provider ->
                                nav.navigate("artist/${Uri.encode(itemId)}/${Uri.encode(provider)}")
                            },
                            onOpenFolders = { nav.navigate("serverbrowse") }
                        )
                    }
                }
                composable("serverbrowse") { MiniPlayerSpace(miniPlayerHeight) { TvServerBrowseScreen() } }
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
                    MiniPlayerSpace(miniPlayerHeight) { TvArtistScreen() }
                }
                composable("settings") {
                    TvSettingsScreen(
                        serverLabel = viewModel.serverLabel(),
                        onChangeServer = { viewModel.changeServer() },
                    )
                }
            }
            }
            // The mini player bar over the bottom of the browsing screens. It fades with the
            // same timing as the screens, so it leaves and returns together with them.
            // While the connection is down the reconnect notice takes the bar's place.
            AnimatedVisibility(
                visible = miniPlayerVisible && connected,
                enter = fadeIn(tween(NAV_FADE_MS)),
                exit = fadeOut(tween(NAV_FADE_MS)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvMiniPlayer(
                    onOpenPlayer = { playerId -> nav.navigate("nowplaying/$playerId") },
                    entrySignal = miniPlayerEntry,
                    entryFocus = miniPlayerFocus,
                    onExitToContent = restoreContentFocus,
                    onActiveChange = { miniPlayerActive = it },
                    modifier = Modifier
                        .onSizeChanged { miniPlayerHeightPx = it.height }
                        .padding(start = BAR_EDGE, end = BAR_EDGE, top = 4.dp, bottom = 16.dp)
                )
            }
            // Volume OSD: pops on any volume change of the selected player, fades on its own.
            TvVolumeOsd(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
            )
            // Offers a new release once the updater has found one. Only builds with the updater
            // compose it, so R8 drops the download and install path from the play and fdroid builds.
            if (net.asksakis.massdroidv2.tv.BuildConfig.ENABLE_UPDATE_CHECK) TvUpdatePrompt()
            if (!connected) {
                TvReconnectingNotice(
                    server = viewModel.serverLabel(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = BAR_EDGE, end = BAR_EDGE, top = 4.dp, bottom = 16.dp)
                )
            }
            }
        }
        !initialized -> TvStartupScreen()
        hasSavedServer && !changeServer -> {
            TvConnectingScreen(server = viewModel.serverLabel(), onChangeServer = { viewModel.changeServer() })
        }
        else -> TvOnboardingScreen()
    }
}

private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/**
 * Says the connection dropped while the screens stay as they were. It sits where the mini
 * player bar does, in the same bar, because that is where the eye goes when the bar's
 * content disappears; a small grey line at the top went unnoticed on a real TV. Not
 * focusable, so the D-pad keeps working on the screen underneath, and the user can still
 * go to Change server from Settings.
 */
@Composable
private fun TvReconnectingNotice(server: String, modifier: Modifier = Modifier) {
    val colors = androidx.tv.material3.MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RECONNECT_BAR_MIN_HEIGHT)
            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        ReconnectSpinner(color = colors.onSurface, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        androidx.tv.material3.Text(
            text = if (server.isBlank()) "Reconnecting to Music Assistant…" else "Reconnecting to $server…",
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            color = colors.onSurface
        )
    }
}

/**
 * An open arc turning at a fixed ~20 fps: the angle is stepped with delay + snapTo and read
 * only in graphicsLayer, so the spinner never recomposes.
 */
@Composable
private fun ReconnectSpinner(color: Color, modifier: Modifier = Modifier) {
    val angle = remember { Animatable(0f) }
    // Only while the screen is on: the loop steps with delay, not the frame clock, so
    // nothing else would stop it while the TV sleeps with the connection down.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                delay(SPINNER_FRAME_MS)
                angle.snapTo((angle.value + SPINNER_STEP_DEGREES) % FULL_TURN_DEGREES)
            }
        }
    }
    Canvas(modifier = modifier.graphicsLayer { rotationZ = angle.value }) {
        val stroke = size.minDimension * SPINNER_STROKE_FRACTION
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = SPINNER_SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** The mini player bar's height: 44 dp artwork inside two 6 dp paddings on each side. */
private val RECONNECT_BAR_MIN_HEIGHT = 68.dp
private const val SPINNER_FRAME_MS = 50L
private const val SPINNER_STEP_DEGREES = 18f
private const val SPINNER_SWEEP_DEGREES = 270f
private const val SPINNER_STROKE_FRACTION = 0.12f
private const val FULL_TURN_DEGREES = 360f

/**
 * Side inset of the mini player bar and the reconnect notice: the 56 dp content inset of the
 * screens above it, so the bar's edges line up with the first and last card.
 */
private val BAR_EDGE = 56.dp

/** The screen crossfade, the same as Navigation's default, shared with the mini player bar. */
private const val NAV_FADE_MS = 700

/** A screen that shows the mini player bar, ending above the bar's height. */
@Composable
private fun MiniPlayerSpace(barHeight: Dp, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) { content() }
        Spacer(Modifier.height(barHeight))
    }
}

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
