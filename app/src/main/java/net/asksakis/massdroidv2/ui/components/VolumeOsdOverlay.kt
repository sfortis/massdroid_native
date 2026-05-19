package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

/**
 * Transient on-screen volume display for remote MA players. The phone's
 * STREAM_MUSIC system bar handles local Sendspin volume; this overlay covers
 * the case where the user is steering a remote speaker (or a group) via the
 * hardware volume keys and needs visual feedback for the level change.
 *
 * The repository owns the auto-hide timer and emits null once the timer
 * expires (~2.5 s). The composable just renders whatever it sees, with a
 * slide+fade transition so successive emissions feel smooth.
 */
@Composable
fun VolumeOsdOverlay(flow: StateFlow<PlayerRepository.VolumeOsdState?>) {
    val state by flow.collectAsStateWithLifecycle()
    // Cache the last non-null snapshot so the exit animation has data to
    // render after the upstream flow has already emitted null. The trick:
    // when visible the content reads `state` directly (synchronously
    // available on the first emission so the slide-in animation starts with
    // real data, not a blank Box); when hidden it falls back to the cache.
    val cached = remember { mutableStateOf<PlayerRepository.VolumeOsdState?>(null) }
    LaunchedEffect(state) { state?.let { cached.value = it } }
    val toRender = state ?: cached.value
    // Symmetric enter/exit: slide the card from above-screen down + fade in,
    // and on dismiss slide back up + fade out together so the card visibly
    // departs the way it arrived.
    val animSpec = tween<Float>(durationMillis = 280)
    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 280)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = state != null,
            enter = slideInVertically(
                animationSpec = slideSpec,
                initialOffsetY = { -it },
            ) + fadeIn(animationSpec = animSpec),
            exit = slideOutVertically(
                animationSpec = slideSpec,
                targetOffsetY = { -it },
            ) + fadeOut(animationSpec = animSpec),
        ) {
            toRender?.let { VolumeOsdCard(it) }
        }
    }
}

@Composable
private fun VolumeOsdCard(state: PlayerRepository.VolumeOsdState) {
    Surface(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, start = 16.dp, end = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when {
                    state.isMuted || state.volume == 0 -> Icons.Filled.VolumeMute
                    state.isGroup -> Icons.Filled.GroupWork
                    else -> Icons.Filled.Speaker
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.playerName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.volume}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { state.volume / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
