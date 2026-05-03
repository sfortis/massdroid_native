package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val iconSize = if (compact) 20.dp else 24.dp
    Row(
        modifier = modifier.fillMaxWidth().let { if (compact) it.height(36.dp) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        MdIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onMuteToggle()
            },
            modifier = if (compact) Modifier.size(32.dp) else Modifier,
            enabled = enabled
        ) {
            Icon(
                imageVector = when {
                    isMuted || volume == 0 -> Icons.Default.VolumeMute
                    volume < 50 -> Icons.Default.VolumeDown
                    else -> Icons.Default.VolumeUp
                },
                contentDescription = "Volume",
                modifier = Modifier.size(iconSize)
            )
        }

        var sliderValue by remember(volume) { mutableFloatStateOf(volume.toFloat()) }

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onVolumeChange(sliderValue.toInt())
            },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier.weight(1f).let { if (compact) it.height(28.dp) else it }
        )

        Text(
            text = "${sliderValue.toInt()}%",
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(if (compact) 32.dp else 40.dp)
        )
    }
}
