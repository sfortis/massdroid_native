package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import net.asksakis.massdroidv2.ui.components.MediaArtwork
import net.asksakis.massdroidv2.ui.components.dropShadow

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

    // Portrait is driven by the width it is given; landscape by the height, because there the
    // column is usually wider than the surface wants and a width-driven surface then keeps the
    // width while the height is capped, which silently widens the real aspect ratio. The travel
    // below is computed from that ratio, so a surface wider than it claims to be uncovers the
    // art either side: at 1.38 instead of 1.23 the next cover sat in plain view beside the
    // current one.
    val aspect = if (fillMaxWidth) SURFACE_ASPECT else SURFACE_ASPECT_COMPACT
    val outerModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth().aspectRatio(aspect)
    } else {
        Modifier
            .fillMaxHeight()
            .heightIn(max = SURFACE_MAX_COMPACT)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
    }
    val travelFactor = travelFactorFor(aspect)
    // Sized off the surface's height, not its width. The surface is wider than it is tall
    // so that a cover has somewhere to come from and somewhere to go, and sizing the cover
    // off that width would make it grow with the room instead of staying put.
    val artworkModifier = Modifier.fillMaxHeight(ARTWORK_FRACTION).aspectRatio(1f)

    suspend fun animateOffsetTo(target: Float, durationMs: Int, easing: androidx.compose.animation.core.Easing) {
        animate(
            initialValue = offsetX,
            targetValue = target,
            animationSpec = tween(durationMillis = durationMs, easing = easing)
        ) { value, _ -> offsetX = value }
    }

    Box(
        modifier = outerModifier
            // The shadow is not here. This surface does not move, so a swipe slid the
            // cover out from under its own shadow. It is on the cover itself now, and
            // the room it needs inside this clip is what ARTWORK_FRACTION leaves over.
            //
            // Nothing rounds this clip. The artwork carries its own rounded corners, and
            // rounding the surface as well cut the corners off the shadow. What the clip
            // is for is keeping the covers either side out of sight until a drag pulls
            // one in, and a rectangle does that.
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

        fun buildImageRequest(url: String?, requestPx: Int) = ImageRequest.Builder(context)
            .data(url)
            .size(requestPx)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        val requestPx = artworkRequestPx(containerWidth)

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
                    val request = remember(url, requestPx) { buildImageRequest(url, requestPx) }
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
                                // The fade is applied to each draw call rather than through
                                // an offscreen layer. An offscreen layer is bounded by the
                                // composable, and the shadow below is drawn outside those
                                // bounds, so the moment a drag made the alpha anything other
                                // than 1 the shadow was clipped away and the cover started to
                                // move without one.
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                val drive = driveOffset(commitProgress, commitStart, commitEdge, offsetX)
                                // Measured against the surface, which is how far a commit
                                // actually travels, rather than against the cover. Against
                                // the cover the fade finished while the cover was still on
                                // screen, and it vanished before it reached the edge.
                                val eased = easedProgressOf(drive, containerWidth.toFloat())
                                if (role == SlotRole.CURRENT) {
                                    translationX = drive
                                    scaleX = 1f - 0.20f * eased
                                    scaleY = scaleX
                                    this.alpha = 1f - 0.88f * eased
                                } else {
                                    val travel = size.width * travelFactor
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
                            }
                            // Under the layer that moves the cover, so it travels, scales
                            // and fades with it, and the artwork itself covers the middle
                            // of it.
                            .dropShadow(
                                shape = shape,
                                color = ALBUM_ART_SHADOW_COLOR,
                                blurRadius = ALBUM_ART_SHADOW_BLUR,
                                offsetY = ALBUM_ART_SHADOW_OFFSET_Y
                            ),
                        shape = shape,
                        iconSize = 64.dp,
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * The width the landscape surface takes when the row gives it [availableHeight].
 *
 * The caller needs this because the surface is driven by its height there, so its width is
 * not known until the height is. A column laid out with a share of the screen instead ends
 * up holding more than the surface uses, and that leftover pushes everything beside it
 * outwards: measured at 137dp of empty space to the left of the cover against 24dp to the
 * right of the controls.
 */
fun landscapeArtSurfaceWidth(availableHeight: Dp): Dp =
    availableHeight.coerceAtMost(SURFACE_MAX_COMPACT) * SURFACE_ASPECT_COMPACT

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

/**
 * The pixel size to decode a cover at, stepped rather than followed pixel by pixel.
 *
 * A new [ImageRequest] puts `SubcomposeAsyncImage` back into its loading state, and the
 * placeholder it draws for that frame reads as a blink. The surface's width does move on
 * its own: navigating back brings the navigation bar and the mini player back while this
 * screen is still animating out, which leaves the screen shorter, and a surface with a
 * fixed aspect ratio that no longer fits the height is sized from the height instead. Three
 * steps cover every screen the app runs on, so that movement no longer reaches the request.
 */
private fun artworkRequestPx(containerWidth: Int): Int = when {
    containerWidth <= ARTWORK_REQUEST_SMALL -> ARTWORK_REQUEST_SMALL
    containerWidth <= ARTWORK_REQUEST_MEDIUM -> ARTWORK_REQUEST_MEDIUM
    else -> ARTWORK_REQUEST_LARGE
}

private const val ARTWORK_REQUEST_SMALL = 512
private const val ARTWORK_REQUEST_MEDIUM = 1024
private const val ARTWORK_REQUEST_LARGE = 2048

/** How far along its travel the art is, eased, from an offset and the surface's width. */
private fun easedProgressOf(offset: Float, width: Float): Float {
    val span = width.coerceAtLeast(1f)
    return FastOutSlowInEasing.transform(abs((offset / span).coerceIn(-1f, 1f)))
}

private fun incomingScaleOf(easedProgress: Float): Float = 0.74f + 0.26f * easedProgress

private fun incomingAlphaOf(easedProgress: Float): Float =
    (0.02f + 0.98f * easedProgress).coerceIn(0f, 1f)

/**
 * Fraction of the surface's width a drag must pass before it counts as a track change.
 *
 * The surface is the full width of the screen, so this is a smaller fraction than it looks:
 * it works out at the same 83dp of finger travel as before the surface grew.
 */
private const val SWIPE_COMMIT_FRACTION = 0.20f

/**
 * The surface's shape: wider than it is tall.
 *
 * The surface runs the full width of the screen, so that the cover a swipe brings in
 * arrives from the screen's own edge and the one it takes out leaves at the other. Were it
 * square it would then be as tall as the screen is wide, which is 77dp more than the
 * artwork needs, and that height would come out of everything below it. This keeps the
 * height where it was and gives all of the extra width to the sides, which is where the
 * covers travel.
 */
private const val SURFACE_ASPECT = 1.23f

/**
 * The surface's ceiling where the screen is short, which is the landscape layout.
 *
 * It is a ceiling, not the size: in landscape the surface is usually narrower than this
 * allows, so the width decides and this only stops the cover eating the row when there is
 * an unusual amount of height. Raised from 220dp together with the column's width. At 220 the two met almost exactly,
 * so the cover could not grow at all; measured after the first raise, the width was still
 * what decided, and the cover came out at 185dp against 167 before.
 */
private val SURFACE_MAX_COMPACT = 300.dp

/**
 * How much of the surface's height the cover takes, leaving the rest for its shadow.
 *
 * The surface clips, so a shadow on the cover is cut at its edge unless the cover stops
 * short of it. What is left over here, eleven and a half percent of the height above and
 * below, is about 38dp on a 1080 wide screen, against the 32dp a `0 8 24` shadow reaches
 * below the cover. Sideways there is far more room than the shadow needs, and that room is
 * the travel the covers either side need.
 */
private const val ARTWORK_FRACTION = 0.77f

/**
 * The surface's shape in landscape, where the height is what there is least of.
 *
 * Narrower than [SURFACE_ASPECT], so the surface asks for less width at the same height and
 * the cover inside it can be as tall as the row allows. Less of the art either side shows
 * during a drag as a result, which is the trade: the room beside the cover is what the
 * arriving and departing covers are seen in.
 */
private const val SURFACE_ASPECT_COMPACT = 1.10f

/**
 * How far the art either side travels while the current one is dragged away, as a multiple
 * of the cover's width.
 *
 * The covers either side rest a whole travel out, scaled to [incomingScaleOf] at zero, and
 * their near edge has to land outside the clip or a sliver of them shows down each side. The
 * clip reaches half the surface's width, which is `aspect / (2 * ARTWORK_FRACTION)` of the
 * cover, so the travel has to clear that plus half the resting cover, plus a margin.
 *
 * It is computed rather than written down because the aspect ratio now differs between
 * portrait and landscape, and a constant that matched one of them uncovered the art in the
 * other. At the portrait ratio this returns 1.25, which is the value it replaces.
 */
private fun travelFactorFor(aspect: Float): Float =
    aspect / (2f * ARTWORK_FRACTION) + 0.5f * incomingScaleOf(0f) + TRAVEL_MARGIN

/** Slack on top of the travel the geometry demands, so a rounding never shows an edge. */
private const val TRAVEL_MARGIN = 0.08f

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
 * The cover's shadow, drawn rather than lifted.
 *
 * Taken from the Music Assistant web client, which gives the full player's artwork
 * `box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25)`. The offset is what gives the cover
 * somewhere to sit and the blur is what makes the edge soft instead of a rim. The shade is
 * darker than the web client's because it falls on a blurred copy of the same artwork
 * rather than on a flat page.
 *
 * The platform's elevation shadow used to do this job and could not: its blur is tied to
 * its depth, so reaching far enough meant spreading the darkness until nothing was left to
 * see, and its offset cannot be set at all.
 */
private val ALBUM_ART_SHADOW_BLUR = 24.dp
private val ALBUM_ART_SHADOW_OFFSET_Y = 8.dp
private val ALBUM_ART_SHADOW_COLOR = Color.Black.copy(alpha = 0.32f)
