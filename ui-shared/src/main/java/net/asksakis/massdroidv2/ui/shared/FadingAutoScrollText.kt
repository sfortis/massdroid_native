package net.asksakis.massdroidv2.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * A window in which [text] rolls slowly upwards, with its top and bottom edges faded. The
 * window holds [visibleLines] clear lines between two fades of [fadeHeight], so the text
 * starts with its first line whole, below the top fade, and only dims as it passes through
 * an edge. Text that fits is shown still. [fadingAutoScrollTextHeight] gives the window's
 * height, for a caller that keeps the room while there is no text.
 *
 * The roll pauses on the first lines, then runs without a stop: a second copy follows the
 * text one line below, and when the first copy has gone the offset snaps back, which lands
 * on the same picture, so the loop has no visible jump.
 *
 * It moves like every continuous animation in the app: a fixed step every 50 ms with
 * `snapTo`, read only in the graphics layer, so the roll costs no recomposition and does not
 * bind the frame clock. It runs only while the screen is started. Not focusable, so the
 * D-pad never stops on it.
 */
@Composable
fun FadingAutoScrollText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    visibleLines: Int = 3,
    fadeHeight: Dp = DEFAULT_FADE_HEIGHT,
    speed: Dp = SCROLL_SPEED_PER_SECOND,
) {
    val density = LocalDensity.current
    val linesPx = rememberLinesHeightPx(style, visibleLines)
    val windowHeight = with(density) { linesPx.toDp() } + fadeHeight * 2
    val gapHeight = with(density) { (linesPx / visibleLines).toDp() }
    var textHeightPx by remember(text) { mutableIntStateOf(0) }
    val rolls = textHeightPx > linesPx
    val offset = remember(text) { Animatable(0f) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Keyed on the measured height too, so a new width (and so a new line count) restarts
    // the roll with the right loop length.
    LaunchedEffect(text, textHeightPx, linesPx, lifecycle) {
        offset.snapTo(0f)
        if (!rolls) return@LaunchedEffect
        val loopPx = textHeightPx + with(density) { gapHeight.toPx() }
        val stepPx = with(density) { speed.toPx() } * FRAME_MS / MILLIS_PER_SECOND
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(START_HOLD_MS)
            while (true) {
                delay(FRAME_MS)
                val next = offset.value + stepPx
                offset.snapTo(if (next >= loopPx) next - loopPx else next)
            }
        }
    }

    val fadePx = with(density) { fadeHeight.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(windowHeight)
            .clipToBounds()
            // Offscreen, so the fade below masks this layer only and not what is behind it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = (fadePx / size.height).coerceIn(0f, 0.5f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        edge to Color.Black,
                        1f - edge to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -offset.value }
        ) {
            // The first line starts below the top fade, not inside it.
            Spacer(Modifier.height(fadeHeight))
            BasicText(
                text = text,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { textHeightPx = it.height }
            )
            if (rolls) {
                Spacer(Modifier.height(gapHeight))
                BasicText(text = text, style = style, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The height [FadingAutoScrollText] takes for [style], [visibleLines] and [fadeHeight]: the
 * lines plus a fade above and below them.
 */
@Composable
fun fadingAutoScrollTextHeight(
    style: TextStyle,
    visibleLines: Int = 3,
    fadeHeight: Dp = DEFAULT_FADE_HEIGHT,
): Dp = with(LocalDensity.current) { rememberLinesHeightPx(style, visibleLines).toDp() } + fadeHeight * 2

/**
 * The height of [lines] lines of [style], measured from the style itself, so it is right
 * whatever line height the theme gives that style.
 */
@Composable
private fun rememberLinesHeightPx(style: TextStyle, lines: Int): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, lines, density) {
        measurer.measure(List(lines) { "A" }.joinToString("\n"), style).size.height
    }
}

private val DEFAULT_FADE_HEIGHT = 8.dp

/**
 * About one line every three seconds at a TV's 20 sp body line: slow enough to read along
 * from the sofa. 14 dp/s was too fast on the Shield (owner, 2026-10-23).
 */
private val SCROLL_SPEED_PER_SECOND = 7.dp

/** The project's fixed animation step (see EqualizerBars). */
private const val FRAME_MS = 50L
private const val MILLIS_PER_SECOND = 1000f

/** Time to read the first lines before the text starts to move. */
private const val START_HOLD_MS = 4_000L
