package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@Composable
fun VolumeSlider(
    volume: Int,
    isMuted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onMuteToggle()
        }) {
            Icon(
                imageVector = when {
                    isMuted || volume == 0 -> Icons.Default.VolumeMute
                    volume < 50 -> Icons.Default.VolumeDown
                    else -> Icons.Default.VolumeUp
                },
                contentDescription = "Volume"
            )
        }

        var sliderValue by remember(volume) { mutableFloatStateOf(volume.toFloat()) }

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onVolumeChange(sliderValue.toInt())
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "${sliderValue.toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
    }
}
