package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
 * D-pad slider: Left/Right adjust by [step] (auto-repeat when held), snapping to
 * 1 ms resolution. Focusable with a visible thumb + highlight.
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
    val thumb = if (focused) 22.dp else 16.dp
    Box(
        modifier = modifier
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange((value - step).coerceIn(min, max)); true }
                    Key.DirectionRight -> { onChange((value + step).coerceIn(min, max)); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        // filled portion + thumb at its leading edge
        Box(modifier = Modifier.fillMaxWidth(fraction).height(thumb), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .size(thumb)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .then(
                        if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
            )
        }
    }
}

/**
 * The output delay control: the output it belongs to, the value, a 1 ms
 * slider (D-pad, auto-repeat while held) and Reset. Shared by the Settings
 * screen and the Now Playing options panel.
 */
@Composable
fun OutputDelayControl(
    outputDelay: TvOutputDelay?,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        TvDivider()
        Spacer(Modifier.height(16.dp))
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
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onChange(0) }) { Text("Reset") }
            }
        }
        Spacer(Modifier.height(16.dp))
        TvDivider()
    }
}
