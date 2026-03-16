package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    spacing: Dp = 2.dp,
    barCount: Int = 4,
    bpm: Int = 130,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val elapsedMs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            elapsedMs.longValue += 50L
        }
    }

    val beatMs = 60_000 / bpm
    val cycleMs = beatMs * 2
    val totalWidth = barWidth * barCount + spacing * (barCount - 1)

    Canvas(modifier = modifier.defaultMinSize(minWidth = totalWidth)) {
        val elapsed = elapsedMs.longValue
        val barWidthPx = barWidth.toPx()
        val spacingPx = spacing.toPx()
        val cornerRadius = CornerRadius(barWidthPx / 2f)
        val barsWidth = barCount * barWidthPx + (barCount - 1) * spacingPx
        val startX = (size.width - barsWidth) / 2f

        repeat(barCount) { index ->
            val peak = PEAKS[index % PEAKS.size]
            val low = LOWS[index % LOWS.size]
            val dur = (cycleMs * CYCLE_MULTS[index % CYCLE_MULTS.size]).toInt()
            val offset = OFFSET_FRACS[index % OFFSET_FRACS.size]
            val offsetMs = (beatMs * offset).toInt()

            val height = barHeight(elapsed, dur, offsetMs, low, peak)
            val barH = size.height * height
            val x = startX + index * (barWidthPx + spacingPx)
            val y = size.height - barH

            drawRoundRect(
                color = color.copy(alpha = 0.4f + height * 0.6f),
                topLeft = Offset(x, y),
                size = Size(barWidthPx, barH.coerceAtLeast(barWidthPx)),
                cornerRadius = cornerRadius
            )
        }
    }
}

private val CYCLE_MULTS = floatArrayOf(1.0f, 1.3f, 0.9f, 1.15f, 1.05f)
private val OFFSET_FRACS = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f / 6f, 1f / 2f)
private val PEAKS = floatArrayOf(0.95f, 0.75f, 0.85f, 0.65f, 0.8f)
private val LOWS = floatArrayOf(0.2f, 0.25f, 0.18f, 0.3f, 0.22f)

private fun barHeight(
    elapsedMs: Long,
    durationMs: Int,
    offsetMs: Int,
    low: Float,
    peak: Float
): Float {
    val t = ((elapsedMs + offsetMs) % durationMs).toFloat()
    val dur = durationMs.toFloat()
    val t1 = dur * 0.25f
    val t2 = dur * 0.45f
    val t3 = dur * 0.70f
    val v1 = peak * 0.7f
    val v3 = low + (peak - low) * 0.3f
    return when {
        t < t1 -> lerp(low, v1, ease(t / t1))
        t < t2 -> lerp(v1, peak, ease((t - t1) / (t2 - t1)))
        t < t3 -> lerp(peak, v3, ease((t - t2) / (t3 - t2)))
        else -> lerp(v3, low, ease((t - t3) / (dur - t3)))
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun ease(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}
