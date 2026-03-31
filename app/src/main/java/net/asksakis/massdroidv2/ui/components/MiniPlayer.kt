package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState

@Composable
fun MiniPlayer(
    title: String,
    artist: String,
    playerName: String = "",
    imageUrl: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onClick: () -> Unit,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val slideOffset = remember { Animatable(0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onSwipeLeft, onSwipeRight) {
                val widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onDragEnd = {
                        val threshold = widthPx * 0.2f
                        if (dragAccumulator < -threshold && onSwipeLeft != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                slideOffset.animateTo(-widthPx, tween(150))
                                onSwipeLeft()
                                slideOffset.snapTo(widthPx)
                                slideOffset.animateTo(0f, tween(200))
                            }
                        } else if (dragAccumulator > threshold && onSwipeRight != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                slideOffset.animateTo(widthPx, tween(150))
                                onSwipeRight()
                                slideOffset.snapTo(-widthPx)
                                slideOffset.animateTo(0f, tween(200))
                            }
                        } else {
                            scope.launch { slideOffset.animateTo(0f, tween(150)) }
                        }
                        dragAccumulator = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        dragAccumulator += delta
                        scope.launch { slideOffset.snapTo(dragAccumulator) }
                    }
                )
            }
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer { translationX = slideOffset.value }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaArtwork(
                model = imageUrl,
                contentDescription = null,
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.small,
                iconSize = 24.dp,
                variant = ArtworkPlaceholderVariant.TRACK,
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (playerName.isNotBlank() && playerName != title) {
                    Text(
                        text = "\uD83D\uDD0A $playerName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPause()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNext()
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onQueue()
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    @Suppress("DEPRECATION")
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
