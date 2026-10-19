package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/** Thin horizontal divider for grouping TV settings sections. */
@Composable
fun TvDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/**
 * D-pad slider: LEFT and RIGHT change the value by [step], and a held key speeds up to ten
 * steps at a time. A mouse click or drag on the track sets the value directly.
 *
 * Drawn on a Canvas so the thumb is always visible, also at the minimum (a thumb inside a
 * zero-width filled box disappeared at 0). When focused, the slider gets a light backdrop
 * and the thumb grows and gets a ring, so the cursor is easy to find.
 */
@Composable
fun TvSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val colors = MaterialTheme.colorScheme
    // The value under a pointer x, on the same track geometry the Canvas draws.
    fun valueAt(x: Float, width: Float, inset: Float): Int {
        val f = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
        val steps = ((max - min) * f / step).roundToInt()
        return (min + steps * step).coerceIn(min, max)
    }
    Canvas(
        modifier = modifier
            .height(SLIDER_HEIGHT)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val held = e.nativeKeyEvent.repeatCount >= SLIDER_FAST_AFTER_REPEATS
                val delta = if (held) step * SLIDER_FAST_FACTOR else step
                when (e.key) {
                    Key.DirectionLeft -> { latestOnChange((latestValue - delta).coerceIn(min, max)); true }
                    Key.DirectionRight -> { latestOnChange((latestValue + delta).coerceIn(min, max)); true }
                    else -> false
                }
            }
            .pointerInput(min, max, step) {
                val inset = SLIDER_THUMB_FOCUSED_RADIUS.toPx()
                detectTapGestures { latestOnChange(valueAt(it.x, size.width.toFloat(), inset)) }
            }
            .pointerInput(min, max, step) {
                val inset = SLIDER_THUMB_FOCUSED_RADIUS.toPx()
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestOnChange(valueAt(change.position.x, size.width.toFloat(), inset))
                }
            }
    ) {
        val inset = SLIDER_THUMB_FOCUSED_RADIUS.toPx()
        val centerY = size.height / 2
        val trackStart = Offset(inset, centerY)
        val trackEnd = Offset(size.width - inset, centerY)
        val thumb = Offset(inset + (size.width - 2 * inset) * fraction, centerY)
        val track = SLIDER_TRACK_WIDTH.toPx()
        if (focused) {
            drawRoundRect(
                color = colors.primary.copy(alpha = SLIDER_FOCUS_BACKDROP_ALPHA),
                cornerRadius = CornerRadius(size.height / 2)
            )
        }
        drawLine(colors.onSurfaceVariant.copy(alpha = SLIDER_TRACK_ALPHA), trackStart, trackEnd, track, StrokeCap.Round)
        drawLine(colors.primary, trackStart, thumb, track, StrokeCap.Round)
        if (focused) {
            drawCircle(colors.onSurface, SLIDER_THUMB_FOCUSED_RADIUS.toPx(), thumb)
            drawCircle(colors.primary, (SLIDER_THUMB_FOCUSED_RADIUS - SLIDER_FOCUS_RING).toPx(), thumb)
        } else {
            drawCircle(colors.primary, SLIDER_THUMB_RADIUS.toPx(), thumb)
        }
    }
}

private val SLIDER_HEIGHT = 32.dp
private val SLIDER_TRACK_WIDTH = 6.dp
private val SLIDER_THUMB_RADIUS = 8.dp
private val SLIDER_THUMB_FOCUSED_RADIUS = 12.dp
private val SLIDER_FOCUS_RING = 2.dp
private const val SLIDER_TRACK_ALPHA = 0.35f
private const val SLIDER_FOCUS_BACKDROP_ALPHA = 0.12f
// Held-key auto-repeat runs at roughly 20 repeats a second; after half a second of holding,
// each repeat moves ten steps.
private const val SLIDER_FAST_AFTER_REPEATS = 10
private const val SLIDER_FAST_FACTOR = 10

/** The output delay section of the Settings screen, between dividers. */
@Composable
fun OutputDelayControl(
    outputDelay: TvOutputDelay?,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        TvDivider()
        Spacer(Modifier.height(16.dp))
        OutputDelayContent(outputDelay, onChange)
        Spacer(Modifier.height(16.dp))
        TvDivider()
    }
}

/**
 * The output delay itself: the output it belongs to, the value, a 1 ms slider (D-pad,
 * auto-repeat while held) and Reset. Shared by the Settings screen and the Now Playing
 * options dialog. [sliderFocus], when given, is attached to the slider so a dialog can
 * put the cursor on it, and [actions] adds buttons after Reset.
 *
 * Reset sits on its own row below the slider, because the slider keeps LEFT and RIGHT for
 * the value: a Reset beside it could not be reached with the remote.
 */
@Composable
fun OutputDelayContent(
    outputDelay: TvOutputDelay?,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sliderFocus: FocusRequester? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier = modifier) {
        Text("Output delay", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "How late your TV or AV receiver plays the sound. This device plays that much earlier, " +
                "so it lines up with the other speakers in a group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (outputDelay == null) {
            Text(
                "Several Bluetooth devices are connected. Play something to set the delay of the one in use.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                outputDelay.outputName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    "${outputDelay.delayMs} ms",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.width(120.dp)
                )
                TvSlider(
                    value = outputDelay.delayMs,
                    min = 0,
                    max = SettingsRepository.MANUAL_OUTPUT_DELAY_MAX_MS,
                    step = 1,
                    onChange = onChange,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (sliderFocus != null) Modifier.focusRequester(sliderFocus) else Modifier)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (outputDelay != null) {
                Button(onClick = { onChange(0) }, modifier = Modifier.pointerClick { onChange(0) }) { Text("Reset") }
            }
            actions()
        }
    }
}
