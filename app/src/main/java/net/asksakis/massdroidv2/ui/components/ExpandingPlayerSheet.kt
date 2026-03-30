package net.asksakis.massdroidv2.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import net.asksakis.massdroidv2.ui.navigation.Routes
import net.asksakis.massdroidv2.ui.screens.home.MiniPlayerViewModel
import net.asksakis.massdroidv2.ui.screens.nowplaying.NowPlayingScreen

/**
 * Floating expanding player overlay.
 * Collapsed: rounded badge above bottom nav.
 * Expanded: fills screen (10% top margin), top corners rounded, scrim behind.
 * Drag or tap to toggle. Crossfade between mini and full content.
 */
@Composable
fun ExpandingPlayerSheet(
    miniPlayerViewModel: MiniPlayerViewModel,
    navController: NavHostController,
    showMiniPlayer: Boolean,
    bottomNavHeightDp: Float = 80f
) {
    val miniPlayerUiState by miniPlayerViewModel.miniPlayerUiState.collectAsStateWithLifecycle()
    val hasMiniPlayer = showMiniPlayer && miniPlayerUiState.connected && miniPlayerUiState.hasPlayer
    if (!hasMiniPlayer) return

    val density = LocalDensity.current
    val screenHDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val screenWDp = LocalConfiguration.current.screenWidthDp.toFloat()

    // Collapsed: badge floating above nav bar with margin
    val cTop = screenHDp - bottomNavHeightDp - 56f - 24f
    val cLeft = 8f
    val cWidth = screenWDp - 16f
    val cHeight = 56f

    // Expanded: near full screen
    val eTop = screenHDp * 0.08f
    val eLeft = 0f
    val eWidth = screenWDp
    val eHeight = screenHDp - eTop

    val animatable = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    BackHandler(enabled = expanded) {
        expanded = false
        scope.launch { animatable.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) }
    }
    val range = (cTop - eTop).coerceAtLeast(1f)

    // f = current fraction (0=collapsed, 1=expanded), driven by drag OR animation
    val f = animatable.value.coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float) = a + (b - a) * f
    val top = lerp(cTop, eTop)
    val left = lerp(cLeft, eLeft)
    val w = lerp(cWidth, eWidth)
    val h = lerp(cHeight, eHeight)
    val topR = lerp(16f, 28f)
    val botR = lerp(16f, 0f)
    val miniA = (1f - f / 0.4f).coerceIn(0f, 1f)
    val fullA = ((f - 0.2f) / 0.8f).coerceIn(0f, 1f)
    val scrimA = f * 0.5f

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim
        if (scrimA > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = false; scope.launch { animatable.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) } }
            )
        }

        // Player surface
        Surface(
            modifier = Modifier
                .offset(x = left.dp, y = top.dp)
                .width(w.dp)
                .height(h.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val deltaDp = with(density) { delta / this.density }
                        val newF = (animatable.value + (-deltaDp / range) * 2.5f).coerceIn(0f, 1f)
                        scope.launch { animatable.snapTo(newF) }
                    },
                    onDragStopped = {
                        val target = if (expanded) {
                            if (f > 0.8f) 1f else 0f // from expanded: small swipe down = close
                        } else {
                            if (f > 0.2f) 1f else 0f // from collapsed: small swipe up = open
                        }
                        expanded = target == 1f
                        scope.launch {
                            animatable.animateTo(target, tween(400, easing = FastOutSlowInEasing))
                        }
                    }
                ),
            shape = RoundedCornerShape(topR.dp, topR.dp, botR.dp, botR.dp),
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Full player (behind, always composed to avoid ViewModel recreation glitch)
                if (expanded || f > 0.01f) {
                    Box(modifier = Modifier.graphicsLayer { alpha = fullA }.fillMaxSize()) {
                        NowPlayingScreen(
                            onBack = { expanded = false; scope.launch { animatable.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) } },
                            onNavigateToQueue = {
                                scope.launch {
                                    expanded = false
                                    animatable.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                                    navController.navigate(Routes.QUEUE) { launchSingleTop = true }
                                }
                            },
                            onNavigateToArtist = { id, prov, name ->
                                scope.launch {
                                    expanded = false
                                    animatable.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                                    navController.navigate(Routes.artistDetail(id, prov, name))
                                }
                            },
                            onNavigateToAlbum = { id, prov, name ->
                                scope.launch {
                                    expanded = false
                                    animatable.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                                    navController.navigate(Routes.albumDetail(id, prov, name))
                                }
                            }
                        )
                    }
                }

                // Mini player (in front, stays at bottom during expand)
                if (miniA > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .graphicsLayer { alpha = miniA }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { expanded = true; scope.launch { animatable.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) } }
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        MiniPlayer(
                            title = miniPlayerUiState.title,
                            artist = miniPlayerUiState.artist,
                            imageUrl = miniPlayerUiState.imageUrl,
                            isPlaying = miniPlayerUiState.isPlaying,
                            onPlayPause = { miniPlayerViewModel.playPause() },
                            onNext = { miniPlayerViewModel.next() },
                            onQueue = { navController.navigate(Routes.QUEUE) { launchSingleTop = true } },
                            onClick = { expanded = true; scope.launch { animatable.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) } }
                        )
                    }
                }
            }
        }
    }
}
