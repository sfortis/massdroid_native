package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.ui.util.formatPlaybackTime
import net.asksakis.massdroidv2.ui.util.isLongFormDuration

/**
 * Slider with a synchronized text label pair (current / total). While the
 * user is dragging we show their target; on release we forward [onSeek]
 * and hold the target value until the live `elapsed` catches up to it
 * (avoids the slider snapping back during the seek round-trip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeekBar(
    /** Read here, so the position ticker recomposes only the bar and not the screen. */
    elapsedProvider: () -> Double,
    duration: Double,
    onSeek: (Double) -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val elapsed = elapsedProvider()
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableFloatStateOf(-1f) }

    // Release hold once server position catches up to the seek target
    if (seekTarget >= 0f && !seeking) {
        if (kotlin.math.abs(elapsed.toFloat() - seekTarget) < 2f) {
            seekTarget = -1f
        }
    }

    val displayValue = when {
        seeking -> seekValue
        seekTarget >= 0f -> seekTarget
        else -> elapsed.toFloat()
    }
    val thumbWidth = if (compact) 5.dp else 5.dp
    val thumbHeight = if (compact) 14.dp else 16.dp
    val trackHeight = if (compact) 6.dp else 8.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSeek(seekValue.toDouble())
                seekTarget = seekValue
                seeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = enabled,
            modifier = if (compact) Modifier.height(30.dp) else Modifier.height(34.dp),
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(thumbWidth, thumbHeight)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(trackHeight),
                    thumbTrackGapSize = 0.dp
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val timeStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
            val longForm = isLongFormDuration(duration)
            Text(formatPlaybackTime(displayValue.toDouble(), padToHours = longForm), style = timeStyle)
            Text(formatPlaybackTime(duration, padToHours = longForm), style = timeStyle)
        }
    }
}
