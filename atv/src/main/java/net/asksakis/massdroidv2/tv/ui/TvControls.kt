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
 * Reusable "audio sync delay" control: slider (1 ms resolution), live value, and
 * a Reset button, framed by dividers so more settings can slot in around it.
 * Shared by the Settings screen and the Now-Playing options panel.
 */
@Composable
fun SyncDelayControl(
    valueMs: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        TvDivider()
        Spacer(Modifier.height(16.dp))
        Text("Audio sync delay", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Negative plays earlier (compensates HDMI / AV receiver latency) so this TV aligns with other speakers in a group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "${if (valueMs > 0) "+" else ""}$valueMs ms",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.width(120.dp)
            )
            TvSlider(
                value = valueMs,
                min = -1000,
                max = 1000,
                step = 5,
                onChange = onChange,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { onChange(0) }) { Text("Reset") }
        }
        Spacer(Modifier.height(16.dp))
        TvDivider()
    }
}
