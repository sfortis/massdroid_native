package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.max
import net.asksakis.massdroidv2.ui.components.MediaArtwork

private enum class SwipeCommitDirection { NEXT, PREVIOUS }

/**
 * Album-art surface with horizontal-swipe gesture for navigating to the
 * previous/next track. While dragging, the incoming art slides in from
 * the opposite edge with a scale + alpha curve so the user feels they
 * are revealing the new track. The commit phase plays a brief settle
 * animation back to centre before invoking [onNext] / [onPrevious] so
 * the new track's album art lands without a hard jump.
 */
@Composable
internal fun SwipeableAlbumArt(
    imageUrl: String?,
    previousImageUrl: String?,
    nextImageUrl: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    canSwipePrevious: Boolean = true,
    onHaptic: () -> Unit = {},
    fillMaxWidth: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableIntStateOf(1) }
    var pendingCommittedImageUrl by remember { mutableStateOf<String?>(null) }
    var pendingCommitDirection by remember { mutableStateOf<SwipeCommitDirection?>(null) }
    var committedOffsetX by remember { mutableFloatStateOf(0f) }
    val canSwipeNext = nextImageUrl != null
    val incomingTravelFactor = 0.94f

    val shape = MaterialTheme.shapes.medium

    val outerModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
    } else {
        Modifier.fillMaxWidth(0.82f).heightIn(max = 196.dp).aspectRatio(1f)
    }
    val artworkModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth(0.915f).aspectRatio(1f)
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = outerModifier
            // Symmetric on every side. Android draws two shadows, an ambient one that
            // surrounds the shape evenly and a spot one cast from above, and the spot
            // is what makes an ordinary elevated card heavier along its bottom edge.
            // Only the ambient one is wanted here, so the spot is turned off outright
            // rather than balanced against.
            .shadow(
                elevation = ALBUM_ART_SHADOW_ELEVATION,
                shape = shape,
                spotColor = Color.Transparent,
                ambientColor = ALBUM_ART_SHADOW_COLOR
            )
            .clip(shape)
            .clipToBounds()
            .pointerInput(canSwipePrevious, canSwipeNext, previousImageUrl, nextImageUrl) {
                containerWidth = size.width
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = containerWidth * 0.25f
                        val current = offsetX
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        scope.launch {
                            suspend fun animateOffsetTo(target: Float, durationMs: Int) {
                                val start = offsetX
                                animate(
                                    initialValue = start,
                                    targetValue = target,
                                    animationSpec = tween(durationMillis = durationMs)
                                ) { value, _ ->
                                    offsetX = value
                                }
                            }
                            if (current < -threshold) {
                                animateOffsetTo(-width, 180)
                                pendingCommittedImageUrl = nextImageUrl
                                committedOffsetX = -width + (width * incomingTravelFactor)
                                pendingCommitDirection = SwipeCommitDirection.NEXT
                            } else if (current > threshold) {
                                if (!canSwipePrevious) {
                                    animateOffsetTo(0f, 200)
                                    return@launch
                                }
                                animateOffsetTo(width, 180)
                                pendingCommittedImageUrl = previousImageUrl
                                committedOffsetX = width - (width * incomingTravelFactor)
                                pendingCommitDirection = SwipeCommitDirection.PREVIOUS
                            } else {
                                animateOffsetTo(0f, 200)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            val start = offsetX
                            animate(
                                initialValue = start,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                            ) { value, _ ->
                                offsetX = value
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        val minOffset = if (canSwipeNext) -width else 0f
                        val maxOffset = if (canSwipePrevious) width else 0f
                        offsetX = (offsetX + dragAmount).coerceIn(minOffset, maxOffset)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(imageUrl, pendingCommittedImageUrl) {
            if (pendingCommittedImageUrl != null && imageUrl == pendingCommittedImageUrl) {
                val start = committedOffsetX
                animate(
                    initialValue = start,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                ) { value, _ ->
                    committedOffsetX = value
                }
                offsetX = 0f
                committedOffsetX = 0f
                pendingCommittedImageUrl = null
            }
        }

        LaunchedEffect(pendingCommitDirection) {
            when (pendingCommitDirection) {
                SwipeCommitDirection.NEXT -> {
                    onHaptic()
                    onNext()
                    pendingCommitDirection = null
                }
                SwipeCommitDirection.PREVIOUS -> {
                    onHaptic()
                    onPrevious()
                    pendingCommitDirection = null
                }
                null -> Unit
            }
        }

        fun buildImageRequest(url: String?) = ImageRequest.Builder(context)
            .data(url)
            .size(max(containerWidth, 512))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        val previousImageRequest = remember(previousImageUrl, containerWidth) { buildImageRequest(previousImageUrl) }
        val nextImageRequest = remember(nextImageUrl, containerWidth) { buildImageRequest(nextImageUrl) }

        val progress = if (containerWidth > 0) {
            (offsetX / containerWidth).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val targetProgress = kotlin.math.abs(progress)
        val easedProgress = FastOutSlowInEasing.transform(targetProgress)
        val currentScale = 1f - 0.20f * easedProgress
        val currentAlpha = 1f - 0.88f * easedProgress
        val targetScale = 0.74f + 0.26f * easedProgress
        val targetAlpha = (0.02f + 0.98f * easedProgress).coerceIn(0f, 1f)
        val width = containerWidth.toFloat().coerceAtLeast(1f)
        val incomingTravel = width * incomingTravelFactor
        val inCommittedPhase = pendingCommittedImageUrl != null
        val commitProgress = if (inCommittedPhase) {
            (1f - (kotlin.math.abs(offsetX) / width)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val committedScale = 1f
        val committedAlpha = 0.94f + 0.06f * commitProgress

        val displayedCurrentImageUrl = pendingCommittedImageUrl ?: imageUrl
        val displayedCurrentRequest = remember(displayedCurrentImageUrl, containerWidth) {
            buildImageRequest(displayedCurrentImageUrl)
        }

        if (!inCommittedPhase && offsetX > 0f && previousImageUrl != null && canSwipePrevious) {
            MediaArtwork(
                model = previousImageRequest,
                contentDescription = "Previous album art",
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier
                    .then(artworkModifier)
                    .graphicsLayer {
                        translationX = offsetX - incomingTravel
                        scaleX = targetScale
                        scaleY = targetScale
                        this.alpha = targetAlpha
                    },
                shape = shape,
                iconSize = 64.dp,
                contentScale = ContentScale.Crop
            )
        } else if (!inCommittedPhase && offsetX < 0f && nextImageUrl != null && canSwipeNext) {
            MediaArtwork(
                model = nextImageRequest,
                contentDescription = "Next album art",
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier
                    .then(artworkModifier)
                    .graphicsLayer {
                        translationX = offsetX + incomingTravel
                        scaleX = targetScale
                        scaleY = targetScale
                        this.alpha = targetAlpha
                    },
                shape = shape,
                iconSize = 64.dp,
                contentScale = ContentScale.Crop
            )
        }

        MediaArtwork(
            model = displayedCurrentRequest,
            contentDescription = "Album art",
            fallbackIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .then(artworkModifier)
                .graphicsLayer {
                    translationX = if (inCommittedPhase) committedOffsetX else offsetX
                    scaleX = if (inCommittedPhase) committedScale else currentScale
                    scaleY = if (inCommittedPhase) committedScale else currentScale
                    this.alpha = if (inCommittedPhase) committedAlpha else currentAlpha
                },
            shape = shape,
            iconSize = 64.dp,
            contentScale = ContentScale.Crop
        )
    }
}

/**
 * Wide on purpose. The elevation sets the blur radius as well as the depth, so a small
 * value gives a short, dense falloff that reads as a dark outline around the cover
 * rather than as shade. A large radius spreads the same darkness over a long distance,
 * which is what lets it arrive at nothing.
 */
private val ALBUM_ART_SHADOW_ELEVATION = 28.dp

/**
 * Held well below opaque so the widened shadow stays shade rather than becoming a grey
 * field. Honoured from API 28; below that the platform uses its own black and the
 * shadow is correspondingly stronger.
 */
private val ALBUM_ART_SHADOW_COLOR = Color.Black.copy(alpha = 0.55f)
