package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import net.asksakis.massdroidv2.ui.components.MediaArtwork

private enum class SwipeCommitDirection { NEXT, PREVIOUS }

/**
 * Album-art surface with a horizontal-swipe gesture for changing track.
 *
 * A swipe always finishes. The art either side comes from the queue snapshot,
 * so the cover the swipe is heading towards is already known: it slides in, the
 * current one slides out, and the new cover settles at centre and stays there.
 *
 * The cover the swipe carries is copied once, at the moment of commit, into
 * [carriedImageUrl]. Everything else about the screen moves the instant the
 * command goes out, including the covers either side, and reading those live
 * during the animation made the incoming art change halfway through and flicker
 * through several covers before settling.
 *
 * The carried cover is given up only when the player reports that exact cover,
 * not on the first artwork change. A burst of swipes makes the server pass
 * through positions on its way, and giving up on the first of them would show
 * each one in turn. A bounded timeout releases it if the server never gets
 * there, so a jump that fails cannot leave the wrong cover on screen.
 */
@Composable
internal fun SwipeableAlbumArt(
    imageUrl: String?,
    previousImageUrl: String?,
    nextImageUrl: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    canSwipePrevious: Boolean = true,
    canSwipeNext: Boolean = true,
    onHaptic: () -> Unit = {},
    fillMaxWidth: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var offsetX by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableIntStateOf(1) }

    // One value drives the whole commit, from where the finger left the art to
    // the outgoing cover gone and the incoming one landed at centre.
    // [NO_COMMIT] means no commit is running and the finger owns the art.
    //
    // It used to be two animations end to end, a slide-out and then a settle
    // that crawled the last few percent, and the drop in speed between them was
    // a visible bump. One curve over the whole distance has no seam to bump at.
    var commitProgress by remember { mutableFloatStateOf(NO_COMMIT) }
    var commitStart by remember { mutableFloatStateOf(0f) }
    var commitEdge by remember { mutableFloatStateOf(0f) }

    // Which of the three slots below is the current track. A commit turns this
    // by one, so the cover that slid into centre keeps the slot it arrived in
    // and is never handed to a different one.
    //
    // Handing it over is what made the art blink: MediaArtwork is a
    // SubcomposeAsyncImage, and a new url puts it back into its loading state,
    // which draws the placeholder for a frame even when the image is already in
    // memory. That frame landed exactly as the cover reached centre.
    var slotRotation by remember { mutableIntStateOf(0) }
    var carriedImageUrl by remember { mutableStateOf<String?>(null) }

    // The two covers the slide-out draws, owned by the commit job alone and set
    // only while it runs. They are separate from carriedImageUrl because the
    // player can confirm the new track mid-animation, which releases the carried
    // cover; sharing one value made the incoming art vanish halfway through.
    var commitOutgoing by remember { mutableStateOf<String?>(null) }
    var commitIncoming by remember { mutableStateOf<String?>(null) }
    var commitJob by remember { mutableStateOf<Job?>(null) }

    // Read inside the gesture handlers, which are created once. Without this the
    // pointer input would restart on every artwork change and drop a gesture in
    // progress.
    val currentImageUrl by rememberUpdatedState(imageUrl)
    val currentPreviousImageUrl by rememberUpdatedState(previousImageUrl)
    val currentNextImageUrl by rememberUpdatedState(nextImageUrl)
    val currentCanSwipePrevious by rememberUpdatedState(canSwipePrevious)
    val currentCanSwipeNext by rememberUpdatedState(canSwipeNext)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnHaptic by rememberUpdatedState(onHaptic)

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

    suspend fun animateOffsetTo(target: Float, durationMs: Int, easing: androidx.compose.animation.core.Easing) {
        animate(
            initialValue = offsetX,
            targetValue = target,
            animationSpec = tween(durationMillis = durationMs, easing = easing)
        ) { value, _ -> offsetX = value }
    }

    Box(
        modifier = outerModifier
            // Both of Android's shadows are used. The ambient one surrounds the shape
            // evenly and the spot one is cast from above, and it is the spot that
            // carries most of the darkness: with it turned off, measured under the
            // cover, the surface went from 85 to 81 over fifty pixels, which is the
            // backdrop's own gradient and no shadow at all.
            .shadow(
                elevation = ALBUM_ART_SHADOW_ELEVATION,
                shape = shape,
                spotColor = ALBUM_ART_SHADOW_SPOT,
                ambientColor = ALBUM_ART_SHADOW_AMBIENT
            )
            .clip(shape)
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        // A settle still running owns the art. Cancel it and start
                        // this gesture from centre, keeping the carried cover so the
                        // art does not jump back to the track being left behind.
                        commitJob?.cancel()
                        commitJob = null
                        offsetX = 0f
                        // A cancelled commit has gone as far as it is going to.
                        // Drop the cover it was leaving behind, so the art shows
                        // the one it was carrying rather than jumping backwards.
                        commitProgress = NO_COMMIT
                        commitOutgoing = null
                        commitIncoming = null
                    },
                    onDragEnd = {
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        val threshold = width * SWIPE_COMMIT_FRACTION
                        val direction = when {
                            offsetX < -threshold && currentCanSwipeNext -> SwipeCommitDirection.NEXT
                            offsetX > threshold && currentCanSwipePrevious -> SwipeCommitDirection.PREVIOUS
                            else -> null
                        }
                        // Copy both ends of the animation before telling anyone,
                        // because the command moves the screen's idea of where it
                        // is and the covers either side move with it. Reading the
                        // incoming one live while the slide-out ran is what made
                        // the track after the destination flash by first.
                        val incoming = when (direction) {
                            SwipeCommitDirection.NEXT -> currentNextImageUrl
                            SwipeCommitDirection.PREVIOUS -> currentPreviousImageUrl
                            null -> null
                        }
                        val outgoing = carriedImageUrl ?: currentImageUrl
                        // The command goes out before the animation. It used to wait for
                        // the slide-out to finish, which cost nearly 200 ms on every
                        // swipe before the app even spoke to the server, and is why
                        // swiping felt slower than the transport buttons.
                        when (direction) {
                            SwipeCommitDirection.NEXT -> {
                                currentOnHaptic()
                                currentOnNext()
                            }
                            SwipeCommitDirection.PREVIOUS -> {
                                currentOnHaptic()
                                currentOnPrevious()
                            }
                            null -> Unit
                        }
                        commitJob?.cancel()
                        commitJob = scope.launch {
                            if (direction == null || incoming == null) {
                                animateOffsetTo(0f, RETURN_DURATION_MS, FastOutSlowInEasing)
                                return@launch
                            }
                            commitOutgoing = outgoing
                            commitIncoming = incoming
                            carriedImageUrl = incoming
                            commitStart = offsetX
                            commitEdge = if (direction == SwipeCommitDirection.NEXT) -width else width
                            commitProgress = 0f
                            // Decelerating, so the art carries on at the speed the
                            // finger left it with and eases to a stop.
                            animate(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = COMMIT_DURATION_MS,
                                    easing = LinearOutSlowInEasing
                                )
                            ) { value, _ -> commitProgress = value }
                            // The incoming cover has landed at centre and is the
                            // current one now, so the finger's offset starts from
                            // centre again. Leaving it at the edge is what used to
                            // strand the art off screen.
                            offsetX = 0f
                            commitProgress = NO_COMMIT
                            // The slot holding the arriving cover becomes the
                            // current one. Every slot keeps its image: the old
                            // current is the new track's previous, and only the
                            // slot that rotates out of sight takes a new url.
                            slotRotation += if (direction == SwipeCommitDirection.NEXT) -1 else 1
                            commitOutgoing = null
                            commitIncoming = null
                        }
                    },
                    onDragCancel = {
                        commitJob?.cancel()
                        commitJob = scope.launch {
                            animateOffsetTo(0f, RETURN_DURATION_MS, FastOutSlowInEasing)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        val minOffset = if (currentCanSwipeNext) -width else 0f
                        val maxOffset = if (currentCanSwipePrevious) width else 0f
                        offsetX = (offsetX + dragAmount).coerceIn(minOffset, maxOffset)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(imageUrl, carriedImageUrl) {
            val carried = carriedImageUrl ?: return@LaunchedEffect
            if (imageUrl == carried) {
                // The player is on the track the swipe carried us to. Nothing to
                // hold on to any more, and nothing changes on screen either.
                carriedImageUrl = null
                return@LaunchedEffect
            }
            delay(CARRIED_ARTWORK_TTL_MS)
            carriedImageUrl = null
        }

        fun buildImageRequest(url: String?) = ImageRequest.Builder(context)
            .data(url)
            .size(max(containerWidth, 512))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        // While a commit runs, both ends are the copies taken when it started.
        // Outside a commit they are the live values.
        val committing = commitOutgoing != null
        val currentImage = commitOutgoing ?: carriedImageUrl ?: imageUrl
        val previousImage =
            if (committing && commitEdge > 0f) commitIncoming else previousImageUrl
        val nextImage =
            if (committing && commitEdge < 0f) commitIncoming else nextImageUrl

        // Three slots in fixed code positions, each keeping its own image across
        // a commit. Every position, scale and alpha is worked out inside a
        // graphicsLayer, which runs in the draw phase, so nothing here reads the
        // offset or the progress and dragging recomposes nothing. Reading them
        // in composition meant recomposing the whole surface on every frame,
        // which at 120Hz is 120 times a second.
        //
        // At rest the two covers either side sit a whole INCOMING_TRAVEL_FACTOR
        // of the width out, past the clip, so neither shows until a drag pulls
        // one in. Laying them out early also gives Coil both covers in advance.
        repeat(SLOT_COUNT) { slot ->
            // Keyed on the slot, not on the loop position, so a slot with no
            // image to show does not let the ones after it shift up and inherit
            // each other's images, which is the same handover that blinks.
            key(slot) {
                val role = roleOfSlot(slot, slotRotation)
                val url = when (role) {
                    SlotRole.PREVIOUS -> previousImage
                    SlotRole.CURRENT -> currentImage
                    SlotRole.NEXT -> nextImage
                }
                if (url != null) {
                    val request = remember(url, containerWidth) { buildImageRequest(url) }
                    MediaArtwork(
                        model = request,
                        contentDescription = when (role) {
                            SlotRole.PREVIOUS -> "Previous album art"
                            SlotRole.CURRENT -> "Album art"
                            SlotRole.NEXT -> "Next album art"
                        },
                        fallbackIcon = Icons.Default.MusicNote,
                        modifier = Modifier
                            .then(artworkModifier)
                            // The track being played belongs on top of the two beside it,
                            // whichever slot is holding it at the time.
                            .zIndex(if (role == SlotRole.CURRENT) 1f else 0f)
                            .graphicsLayer {
                                val drive = driveOffset(commitProgress, commitStart, commitEdge, offsetX)
                                val eased = easedProgressOf(drive, size.width)
                                if (role == SlotRole.CURRENT) {
                                    translationX = drive
                                    scaleX = 1f - 0.20f * eased
                                    scaleY = scaleX
                                    this.alpha = 1f - 0.88f * eased
                                } else {
                                    val travel = size.width * INCOMING_TRAVEL_FACTOR
                                    translationX = incomingTranslation(
                                        commitProgress,
                                        commitStart,
                                        commitEdge,
                                        drive,
                                        travel,
                                        leading = role == SlotRole.PREVIOUS
                                    )
                                    scaleX = incomingScaleOf(eased)
                                    scaleY = scaleX
                                    this.alpha = incomingAlphaOf(eased)
                                }
                            },
                        shape = shape,
                        iconSize = 64.dp,
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

private enum class SlotRole { PREVIOUS, CURRENT, NEXT }

private const val SLOT_COUNT = 3

/**
 * Which track a slot is holding, given how far the carousel has turned. Slot
 * order in the code never changes, so an image stays with its slot and only the
 * slot that turns out of sight is ever given a different url.
 */
private fun roleOfSlot(slot: Int, rotation: Int): SlotRole {
    val index = ((slot + rotation) % SLOT_COUNT + SLOT_COUNT) % SLOT_COUNT
    return SlotRole.entries[index]
}

/**
 * Where the art the finger was holding is: the finger's own offset, or, once a
 * commit is running, that offset carried along the commit curve to the edge.
 * Both the cover leaving and the cover arriving are positioned from this, so
 * they never drift apart.
 */
private fun driveOffset(progress: Float, start: Float, edge: Float, offsetX: Float): Float =
    if (progress < 0f) offsetX else start + (edge - start) * progress

/**
 * Where a neighbouring cover is. While the finger is down it trails [drive] by
 * its travel, and during a commit the one being swiped towards converges on
 * centre, reaching it exactly as the outgoing cover reaches the edge. The other
 * side keeps trailing, which carries it off the surface and out of the clip.
 */
private fun incomingTranslation(
    progress: Float,
    start: Float,
    edge: Float,
    drive: Float,
    travel: Float,
    leading: Boolean
): Float {
    val trailing = if (leading) drive - travel else drive + travel
    if (progress < 0f) return trailing
    val arriving = if (leading) edge > 0f else edge < 0f
    if (!arriving) return trailing
    val from = if (leading) start - travel else start + travel
    return from - from * progress
}

/** How far along its travel the art is, eased, from an offset and the surface's width. */
private fun easedProgressOf(offset: Float, width: Float): Float {
    val span = width.coerceAtLeast(1f)
    return FastOutSlowInEasing.transform(abs((offset / span).coerceIn(-1f, 1f)))
}

private fun incomingScaleOf(easedProgress: Float): Float = 0.74f + 0.26f * easedProgress

private fun incomingAlphaOf(easedProgress: Float): Float =
    (0.02f + 0.98f * easedProgress).coerceIn(0f, 1f)

/** Fraction of the art's width a drag must pass before it counts as a track change. */
private const val SWIPE_COMMIT_FRACTION = 0.25f

/** How far the art either side travels while the current one is dragged away. */
private const val INCOMING_TRAVEL_FACTOR = 0.94f

/**
 * One curve for the whole commit, shorter than the 380 ms the old two-part
 * animation added up to and without the drop in speed between its halves.
 */
private const val COMMIT_DURATION_MS = 280

private const val RETURN_DURATION_MS = 200

/** [commitProgress] when no commit is running and the finger owns the art. */
private const val NO_COMMIT = -1f

/**
 * How long the art may stay on the cover a swipe carried it to without the
 * player confirming that track. Long enough to cover a burst of swipes, whose
 * final position is sent once the swiping stops, and short enough that a jump
 * the server never makes does not leave the wrong cover on screen.
 */
private const val CARRIED_ARTWORK_TTL_MS = 5000L

/**
 * The lift under the cover.
 *
 * Elevation sets the blur radius as well as the depth, so this is really a choice of how
 * far the shade reaches. Twenty-eight spread it so thin that nothing was left to see. This
 * is scaled from the Music Assistant web client, whose cards use an eight pixel blur on
 * artwork about a third of this size.
 */
private val ALBUM_ART_SHADOW_ELEVATION = 12.dp

/**
 * The cast shadow, which is what the eye actually reads, and the even one around the rest
 * of the shape. The spot is the stronger of the two on purpose: it falls below the cover
 * and gives it somewhere to sit, which is the same thing the web client's `0 2px` offset
 * does. Both are honoured from API 28; below that the platform uses its own black and the
 * shadow comes out heavier.
 */
private val ALBUM_ART_SHADOW_SPOT = Color.Black.copy(alpha = 0.45f)
private val ALBUM_ART_SHADOW_AMBIENT = Color.Black.copy(alpha = 0.30f)
