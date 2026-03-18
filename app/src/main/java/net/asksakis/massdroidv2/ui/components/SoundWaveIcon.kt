package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SoundWaveIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    val phase = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val cycleMs = 500           // 120 BPM
            val stepMs = 50L            // ~20fps (was 20ms/50fps)
            val steps = (cycleMs / stepMs).toInt()
            val maxPhase = 5f
            while (true) {
                repeat(steps) { i ->
                    delay(stepMs)
                    phase.floatValue = maxPhase * (i + 1) / steps
                }
                phase.floatValue = 0f
            }
        } else {
            phase.floatValue = 0f
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        content()
        if (isPlaying) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokeW = 1.5.dp.toPx()
                val gap = 2.dp.toPx()
                val baseR = size.minDimension / 2f + gap
                val currentPhase = phase.floatValue

                repeat(2) { i ->
                    val peak = i + 1f
                    val raw = if (currentPhase < peak) {
                        ((currentPhase - (peak - 0.5f)) / 0.5f).coerceIn(0f, 1f)
                    } else {
                        ((peak + 2.5f - currentPhase) / 2.5f).coerceIn(0f, 1f)
                    }
                    val alpha = raw * raw * (3f - 2f * raw)
                    if (alpha > 0f) {
                        val r = baseR + i * (strokeW + gap)
                        val arcColor = waveColor.copy(alpha = alpha)
                        drawArc(
                            color = arcColor,
                            startAngle = 155f,
                            sweepAngle = 50f,
                            useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = strokeW)
                        )
                        drawArc(
                            color = arcColor,
                            startAngle = -25f,
                            sweepAngle = 50f,
                            useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = strokeW)
                        )
                    }
                }
            }
        }
    }
}
