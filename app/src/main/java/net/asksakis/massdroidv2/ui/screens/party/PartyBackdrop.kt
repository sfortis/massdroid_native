package net.asksakis.massdroidv2.ui.screens.party

import android.animation.ValueAnimator
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.party.PartyLights
import net.asksakis.massdroidv2.domain.party.PartySpecks
import net.asksakis.massdroidv2.ui.shared.AlbumArtBackdrop
import net.asksakis.massdroidv2.ui.shared.BackdropTints
import net.asksakis.massdroidv2.ui.shared.rememberBackdropTints
import net.asksakis.massdroidv2.ui.shared.rememberSettledArtwork
import kotlin.math.PI
import kotlin.math.roundToInt

/** The clock of the Party background, and whether it is moving. */
@Stable
private class PartyMotion {
    /** How long the lights have run, in milliseconds. Read only while drawing. */
    var elapsedMs by mutableLongStateOf(0L)

    /** False while animations are turned off in the system settings, or before the first frame. */
    var moving by mutableStateOf(false)
}

/**
 * The animated background of the Party screen, from back to front: the blurred cover of the
 * playing track, turning light beams, a mirror ball hanging from the top, drifting specks of
 * light, a scrim that keeps the text readable, and a strobe. Two colours taken from the cover
 * tint the ball, the specks and the strobe.
 *
 * Everything moves from one clock that is read only while drawing, in a layer of its own, so
 * a frame redraws that layer and never recomposes. The clock runs only while the screen is
 * visible, from the start of its enter transition. With animations turned off in the system
 * settings it draws one still frame with no strobe.
 *
 * [ballRoom] is the room the content leaves above itself, under the app bar. The ball is made
 * as large as that room holds, up to its full size, and is left out while it is null. It is
 * read only while drawing, so a change in the content's fit redraws and never recomposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartyBackdrop(
    artworkUrl: String?,
    isDark: Boolean,
    ballRoom: State<Dp?>,
    modifier: Modifier = Modifier
) {
    val surface = MaterialTheme.colorScheme.surface
    val artwork = rememberSettledArtwork(artworkUrl)
    val tints = rememberBackdropTints(artwork)
    // Read only while drawing, so a fade redraws and never recomposes.
    val tintA = remember { Animatable(NEUTRAL_TINT_A) }
    val tintB = remember { Animatable(NEUTRAL_TINT_B) }
    val shownTints = remember { mutableStateOf<BackdropTints?>(null) }
    LaunchedEffect(tints) {
        // A track without a cover takes the lights back to neutral, as the cover clears.
        val next = tints ?: BackdropTints(NEUTRAL_TINT_A, NEUTRAL_TINT_B).takeIf { shownTints.value != null }
            ?: return@LaunchedEffect
        if (shownTints.value == null) {
            // The cover playing when the screen opens: in colour from the start, with no
            // fade up from grey.
            tintA.snapTo(next.first)
            tintB.snapTo(next.second)
        } else {
            // A track change: the same 900 ms as the cover's own crossfade, so the tints
            // turn over with the picture.
            coroutineScope {
                launch { tintA.animateTo(next.first, tween(TINT_FADE_MS)) }
                launch { tintB.animateTo(next.second, tween(TINT_FADE_MS)) }
            }
        }
        shownTints.value = next
    }
    val look = if (isDark) DarkLook else LightLook
    val statusBar = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val dot = remember { softDot() }
    val ball = remember(dot) { DiscoBall(dot) }
    val specks = remember { PartySpecks() }
    val speckFilters = remember(look) {
        arrayOf(TintFilter { look.speckWhite }, TintFilter(look.speckTint), TintFilter(look.speckTint))
    }

    val motion = remember { PartyMotion() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, motion) {
        // Started rather than resumed: a destination reaches resumed only once its enter
        // transition ends, about 700 ms after it shows, and the lights stood still until then.
        // They still stop as soon as the screen is no longer visible.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Read on every start, so turning animations off in the settings takes effect here.
            if (!ValueAnimator.areAnimatorsEnabled()) {
                motion.moving = false
                return@repeatOnLifecycle
            }
            try {
                runLights(motion)
            } finally {
                motion.moving = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AlbumArtBackdrop(imageUrl = artwork, isDark = isDark, surfaceColor = surface)
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // Its own layer, so each frame records only these lights and not the cover,
                // the scrims and the content under the same destination. Clipped, because the
                // beams reach far past the screen and the specks drift off its edge, and while
                // Party slides over Now Playing they would light up the player beside it.
                .graphicsLayer(clip = true)
                .drawWithCache {
                    val room = ballRoom.value
                    // The ball hangs from just under the top of the app bar down to a small gap
                    // above the content, and is never larger than its full size.
                    val ballTop = statusBar + BALL_TOP_BELOW_STATUS_BAR
                    val ballSize = room?.let { minOf(BALL_SIZE, statusBar + TopAppBarDefaults.TopAppBarExpandedHeight + it - BALL_GAP - ballTop) }
                    val showBall = ballSize != null && ballSize > 0.dp
                    val center = Offset(size.width / 2f, (ballTop + (ballSize ?: BALL_SIZE) / 2).toPx())
                    val radius = ((ballSize ?: BALL_SIZE) * BALL_RADIUS_SHARE).toPx()
                    val beams = Brush.sweepGradient(*beamStops(look.beamAlphas), center = center)
                    val beamReach = size.maxDimension * BEAM_REACH_SCALE
                    val rim = Brush.radialGradient(
                        0f to Color.Transparent,
                        RIM_CLEAR_SHARE to Color.Transparent,
                        1f to Color.Black.copy(alpha = RIM_ALPHA),
                        center = center,
                        radius = radius
                    )
                    val scrim = Brush.verticalGradient(
                        0f to surface.copy(alpha = look.scrimTop),
                        SCRIM_MIDDLE_STOP to surface.copy(alpha = look.scrimMiddle),
                        1f to surface.copy(alpha = look.scrimBottom)
                    )
                    val drift = Offset(PartySpecks.DRIFT_X_DP.dp.toPx(), PartySpecks.DRIFT_Y_DP.dp.toPx())
                    val glintLength = GLINT_LENGTH.toPx()
                    val glintStroke = GLINT_STROKE.toPx()
                    val lineWidth = LINE_WIDTH.toPx()
                    onDrawBehind {
                        val elapsed = motion.elapsedMs
                        val a = tintA.value
                        val b = tintB.value
                        val beamDegrees = FULL_TURN * (elapsed % PartyLights.BEAM_TURN_MS) / PartyLights.BEAM_TURN_MS
                        rotate(beamDegrees, pivot = center) { drawCircle(beams, beamReach, center) }
                        if (showBall) {
                            drawLine(look.line, Offset(center.x, 0f), Offset(center.x, center.y - radius), lineWidth)
                            val angle = (2 * PI * (elapsed % PartyLights.BALL_TURN_MS) / PartyLights.BALL_TURN_MS).toFloat()
                            with(ball) { draw(angle, center, radius, a, b, look.grout, rim, glintLength, glintStroke) }
                        }
                        for (i in 0 until specks.count) {
                            val progress = specks.progress(i, elapsed)
                            val diameter = specks.sizeDp[i].dp.toPx().roundToInt()
                            val filter = speckFilters[i % speckFilters.size]
                            drawImage(
                                image = dot,
                                dstOffset = IntOffset(
                                    (specks.startX[i] * size.width + drift.x * progress).roundToInt(),
                                    (specks.startY[i] * size.height + drift.y * progress).roundToInt()
                                ),
                                dstSize = IntSize(diameter, diameter),
                                alpha = PartySpecks.opacity(progress),
                                colorFilter = filter.of(if (i % speckFilters.size == 2) b else a)
                            )
                        }
                        drawRect(scrim)
                        if (motion.moving) {
                            val flash = PartyLights.strobeAlpha(elapsed, look.strobeFirstPeak, look.strobeSecondPeak)
                            if (flash > 0f) drawRect(if (isDark) b else Color.White, alpha = flash)
                        }
                    }
                }
        )
    }
}

/**
 * Advances [motion] on every display frame until the screen is no longer visible. The screen
 * closes itself after a minute without a touch, so the lights never run for long unattended.
 *
 * A deliberate exception to the project's fixed-step rule (delay(50) + snapTo), decided by the
 * owner on 2026-11-07: this screen is open for a party at most, and its animation is read in
 * the draw phase alone, so it follows the display's frame rate. Every motion is computed from
 * the elapsed time, so the speed is the same at 60 and 120 Hz and a dropped frame slows nothing.
 */
private suspend fun runLights(motion: PartyMotion) {
    motion.moving = true
    val startedAt = motion.elapsedMs
    val start = withFrameNanos { it }
    while (true) {
        withFrameNanos { now -> motion.elapsedMs = startedAt + (now - start) / NANOS_PER_MS }
    }
}

/**
 * A tint filter for one speck colour, made again only when its colour changes: once per cover,
 * and on each frame of the short crossfade between two covers.
 */
private class TintFilter(private val shade: (Color) -> Color) {
    private var source = Color.Unspecified
    private var filter: ColorFilter = ColorFilter.tint(Color.White)

    fun of(color: Color): ColorFilter {
        if (color != source) {
            source = color
            filter = ColorFilter.tint(shade(color))
        }
        return filter
    }
}

/** A white dot that fades from its center to its edge, scaled into place for specks and glints. */
private fun softDot(): ImageBitmap {
    val image = ImageBitmap(SOFT_DOT_PX, SOFT_DOT_PX)
    val half = SOFT_DOT_PX / 2f
    val paint = Paint().apply {
        shader = RadialGradientShader(
            center = Offset(half, half),
            radius = half,
            colors = listOf(Color.White, Color.White.copy(alpha = 0f))
        )
    }
    Canvas(image).drawCircle(Offset(half, half), half, paint)
    return image
}

/** Five thin beams around the circle, each fading in and out over a few degrees. */
private fun beamStops(alphas: FloatArray): Array<Pair<Float, Color>> {
    val stops = mutableListOf(0f to Color.Transparent)
    BEAM_DEGREES.forEachIndexed { beam, (from, to) ->
        val color = Color.White.copy(alpha = alphas[beam])
        stops += (from - BEAM_EDGE_DEGREES) / FULL_TURN to Color.Transparent
        stops += from / FULL_TURN to color
        stops += to / FULL_TURN to color
        stops += (to + BEAM_EDGE_DEGREES) / FULL_TURN to Color.Transparent
    }
    stops += 1f to Color.Transparent
    return stops.toTypedArray()
}

/** The values that differ between the dark and the light theme, from the approved mockups. */
private class PartyLook(
    val beamAlphas: FloatArray,
    val line: Color,
    val grout: Color,
    val speckWhite: Color,
    val speckTint: (Color) -> Color,
    val scrimTop: Float,
    val scrimMiddle: Float,
    val scrimBottom: Float,
    val strobeFirstPeak: Float,
    val strobeSecondPeak: Float
)

private val DarkLook = PartyLook(
    beamAlphas = floatArrayOf(0.10f, 0.08f, 0.10f, 0.07f, 0.09f),
    line = Color.White.copy(alpha = 0.2f),
    grout = Color(0xFF161616),
    speckWhite = Color.White.copy(alpha = 0.95f),
    // Lighter on a dark screen, so a speck reads as light.
    speckTint = { lerp(it, Color.White, 0.55f).copy(alpha = 0.9f) },
    scrimTop = 0.2f,
    scrimMiddle = 0.55f,
    scrimBottom = 0.9f,
    strobeFirstPeak = 0.18f,
    strobeSecondPeak = 0.11f
)

private val LightLook = PartyLook(
    beamAlphas = floatArrayOf(0.45f, 0.38f, 0.45f, 0.35f, 0.42f),
    line = Color.Black.copy(alpha = 0.25f),
    grout = Color(0xFF3A3A3A),
    speckWhite = Color.White,
    // More saturated on a light screen, so a speck still shows against it.
    speckTint = { saturated(it).copy(alpha = 0.75f) },
    scrimTop = 0.15f,
    scrimMiddle = 0.55f,
    scrimBottom = 0.92f,
    strobeFirstPeak = 0.45f,
    strobeSecondPeak = 0.28f
)

/** [color] with its saturation raised; a grey stays grey, because it has no hue to raise. */
private fun saturated(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    if (hsl[1] > GREY_SATURATION) hsl[1] = hsl[1].coerceAtLeast(MIN_SPECK_SATURATION)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** The beams' spans in degrees, from the mockup's conic gradient. */
private val BEAM_DEGREES = listOf(17f to 23f, 77f to 82f, 143f to 149f, 208f to 213f, 273f to 279f)
private const val BEAM_EDGE_DEGREES = 3f
private const val FULL_TURN = 360f
private const val BEAM_REACH_SCALE = 1.5f

/** Colours for the ball and the specks while there is no cover to take them from. */
private val NEUTRAL_TINT_A = Color(0xFF8C8C8C)
private val NEUTRAL_TINT_B = Color(0xFF5E5E5E)
private const val TINT_FADE_MS = 900

private val BALL_SIZE: Dp = 160.dp

/** The ball's radius as a share of [BALL_SIZE], leaving room for the glints at its edge. */
private const val BALL_RADIUS_SHARE = 0.47f

/** The 64 dp app bar and a small gap under it. */
private val BALL_TOP_BELOW_STATUS_BAR: Dp = 74.dp


/** The space kept between the ball and the content under it. */
private val BALL_GAP: Dp = 12.dp
private val LINE_WIDTH: Dp = 2.dp
private val GLINT_LENGTH: Dp = 11.dp
private val GLINT_STROKE: Dp = 1.dp
private const val RIM_CLEAR_SHARE = 0.72f
private const val RIM_ALPHA = 0.5f
private const val SCRIM_MIDDLE_STOP = 0.55f
private const val SOFT_DOT_PX = 64
private const val GREY_SATURATION = 0.08f
private const val MIN_SPECK_SATURATION = 0.7f
private const val NANOS_PER_MS = 1_000_000L
