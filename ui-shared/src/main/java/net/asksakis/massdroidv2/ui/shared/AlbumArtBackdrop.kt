package net.asksakis.massdroidv2.ui.shared

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The cover the background should show: [imageUrl] once it has stayed current for
 * [BACKGROUND_SETTLE_DELAY_MS]. Skipping through tracks would otherwise repaint the whole
 * screen for each one on the way, and a colour that is about to be replaced is not worth
 * showing.
 */
@Composable
fun rememberSettledArtwork(imageUrl: String?): String? {
    var settled by remember { mutableStateOf(imageUrl) }
    LaunchedEffect(imageUrl) {
        delay(BACKGROUND_SETTLE_DELAY_MS)
        settled = imageUrl
    }
    return settled
}

/**
 * How long a confirmed track has to stay current before its colour is taken up.
 * Tracks passed through on the way to the one the user wants are confirmed by
 * the server too, and repainting the screen for each of them is noise. Measured
 * gaps between confirmations while skipping were 763 to 3591 ms, so this folds
 * the closest of them without holding back an ordinary track change.
 */
private const val BACKGROUND_SETTLE_DELAY_MS = 800L

/**
 * The album art, blurred, filling the screen behind the player.
 *
 * Two things blur it, and the second one is why it looks smooth.
 *
 * The artwork is decoded at [BACKDROP_SOURCE_PX] and stretched to the screen, which
 * softens it but leaves the faceted diamonds that bilinear upscaling always produces.
 * [BlurTransformation] removes those, and because it works on an image that carries no
 * detail to begin with, a small radius reads as a long, even falloff rather than a
 * smeared photograph.
 *
 * The blur happens once, at decode. `Modifier.blur` was tried first and measured a
 * 49% jank rate on a 120 Hz phone, almost all of it in slow draw commands, because it
 * re-blurs the whole screen every frame for an image that only changes with the track.
 *
 * The scrim above it is what keeps the text readable. It is deliberately heavier at
 * the bottom, where the controls and the track title sit, than at the top.
 *
 * Shared by the phone and the TV player. [surfaceColor] is the screen's surface, the colour
 * the scrim fades towards and the text is drawn against; each app passes its own theme's.
 */
@Composable
fun AlbumArtBackdrop(imageUrl: String?, isDark: Boolean, surfaceColor: Color) {
    val context = LocalContext.current
    // Lighter in the light theme: the same ring that frames a dark screen reads as
    // dirt on a bright one.
    val vignetteStrength = if (isDark) 0.55f else 0.28f
    val scrim = Brush.verticalGradient(
        colors = listOf(
            surfaceColor.copy(alpha = if (isDark) 0.30f else 0.45f),
            surfaceColor.copy(alpha = if (isDark) 0.62f else 0.72f),
            surfaceColor.copy(alpha = if (isDark) 0.88f else 0.92f)
        )
    )
    // Two layers that both stay laid out, with only the hidden one ever given a new url.
    // One AsyncImage handed a new url drops the picture it is showing and loads the new
    // one from nothing, so its own crossfade fades in over the bare surface: the
    // background went out and came back instead of turning over. Here the picture
    // leaving is still on screen underneath while the one arriving fades in above it.
    val slotUrls = remember { mutableStateListOf(imageUrl, null) }
    // The upper layer's opacity, and the whole of the crossfade: the lower layer is
    // always opaque, so fading the upper one to 1 reveals it and fading it to 0 gives
    // the lower one back. One value drives both directions.
    val upperAlpha = remember { Animatable(0f) }

    val lowerPainter = rememberAsyncImagePainter(
        model = remember(slotUrls[0]) { backdropRequest(context, slotUrls[0]) }
    )
    val upperPainter = rememberAsyncImagePainter(
        model = remember(slotUrls[1]) { backdropRequest(context, slotUrls[1]) }
    )

    LaunchedEffect(imageUrl) {
        // Which layer is showing is read from the opacity rather than tracked beside it. A
        // fade cancelled halfway used to leave a separate "front slot" claiming the layer
        // it never reached, and the next change then aimed at the layer already on screen
        // and never moved the opacity again, leaving the two albums blended for good.
        val front = if (upperAlpha.value >= 0.5f) 1 else 0
        if (imageUrl == slotUrls[front]) return@LaunchedEffect
        val target = 1 - front
        slotUrls[target] = imageUrl
        // Wait for the arriving picture before fading to it, because fading to a layer
        // that has not decoded yet dissolves the old one into the bare surface and then
        // pops. Bounded, so a picture that never arrives still hands the background over
        // rather than leaving it on the album before last.
        //
        // The wait has to name the picture. A slot that already carries one still reports
        // success for it until the new request replaces it, so a bare check for success
        // returned at once from the second album onward and the fade ran a full second
        // back towards the album before last.
        val arriving = if (target == 1) upperPainter else lowerPainter
        withTimeoutOrNull(BACKDROP_LOAD_WAIT_MS) {
            snapshotFlow { arriving.state }.first { state ->
                state is AsyncImagePainter.State.Success &&
                    state.result.request.data == imageUrl
            }
        }
        upperAlpha.animateTo(
            targetValue = if (target == 1) 1f else 0f,
            animationSpec = tween(BACKDROP_FADE_MS, easing = FastOutSlowInEasing)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = lowerPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = upperPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Read in the graphics phase, so the fade costs no recomposition.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = upperAlpha.value }
        )
        Box(modifier = Modifier.fillMaxSize().background(scrim))
        // A second scrim over the top strip alone. The one above is lightest at the top,
        // because that is where the artwork should show through, and the player name and
        // the options button sit in exactly that strip: over a pale cover they were left
        // reading dark text on a bright background. This pulls that strip back towards the
        // surface colour, which is the colour the text is drawn against everywhere else, so
        // it works in both themes rather than only darkening.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            surfaceColor.copy(alpha = if (isDark) 0.50f else 0.60f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(modifier = Modifier.fillMaxSize().drawWithCache {
            // Deliberate vignette. The blur already darkens the rim on its own, because
            // it samples past the edge of the artwork and finds nothing there, but that
            // is a property of one GPU on one Android version rather than something the
            // platform promises, and below API 31 there is no blur and so no rim at all.
            // Drawing it explicitly makes every device show the same picture.
            val radial = Brush.radialGradient(
                colorStops = arrayOf(
                    VIGNETTE_CLEAR_STOP to Color.Transparent,
                    1f to Color.Black.copy(alpha = vignetteStrength)
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                // Reach past the corners, otherwise the gradient ends mid-edge and the
                // corners read as four dark spots instead of one ring.
                radius = size.maxDimension * VIGNETTE_RADIUS_SCALE
            )
            onDrawBehind { drawRect(radial) }
        })
        // Last, so the grain sits over the blur, the scrim and the vignette alike: it is
        // the finish on the whole background rather than a layer inside it.
        Box(modifier = Modifier.fillMaxSize().grain(backdropGrainAlpha(isDark)))
    }
}

/**
 * How strongly the background carries its grain.
 *
 * Lighter in the light theme for the same reason the vignette is: the texture that gives a
 * dark screen its matte finish reads as dust on a bright one.
 */
fun backdropGrainAlpha(isDark: Boolean): Float = if (isDark) 0.065f else 0.045f

/**
 * Decode size of the backdrop artwork. Small enough that stretching it to a phone
 * screen leaves no detail to distract from the text, large enough to keep the
 * colours and their rough arrangement.
 */
private const val BACKDROP_SOURCE_PX = 48

/**
 * Blur radius in SOURCE pixels, so it scales with [BACKDROP_SOURCE_PX] rather than with
 * the screen. Six of forty-eight pixels is a wide blur once stretched.
 */
private const val BACKDROP_BLUR_PASSES = 6

/**
 * How far down the extra scrim behind the player name and the options button reaches.
 *
 * It covers the status bar and the 48dp top bar under it with room to fade out, so the text
 * sits on the strongest part and nothing below the bar shows an edge where it ends.
 */
private val TOP_SCRIM_HEIGHT = 140.dp

/** Where the vignette starts to darken. Below this the backdrop is untouched. */
private const val VIGNETTE_CLEAR_STOP = 0.55f

/** Multiplier on the longest edge, so the gradient reaches beyond the corners. */
private const val VIGNETTE_RADIUS_SCALE = 0.78f

/**
 * How long the background takes to turn from one album to the next. It matches the colour
 * fade above it, so the picture and the tint it carries move together.
 */
private const val BACKDROP_FADE_MS = 900

/**
 * How long to wait for the arriving picture before fading to it anyway. The artwork itself
 * has already been fetched for the cover by the time this runs, so this only covers the
 * decode of the small blurred copy.
 */
private const val BACKDROP_LOAD_WAIT_MS = 1500L

/**
 * The backdrop's picture request. Built here rather than inline so both layers ask for
 * exactly the same thing and share one cache entry per album.
 */
private fun backdropRequest(context: Context, url: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(BACKDROP_SOURCE_PX)
        .memoryCacheKey("backdrop_$url")
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .transformations(BlurTransformation(BACKDROP_BLUR_PASSES))
        .build()
