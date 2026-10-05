package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The labelled slider of the settings screens (Smart Mix tuning, room sensitivity and the
 * like): a title, an optional one-line description, the app slider ([MdSlider]) and an
 * optional live value. It uses the type scale of a settings row, so a slider in a section
 * reads like the rows around it; the caller places it at the row inset.
 *
 * Dragging updates local state for smoothness; the committed value is forwarded
 * via [onValueChangeFinished] only when the drag ends, matching the rest of the
 * app's sliders.
 */
@Composable
fun LabeledSlider(
    title: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    valueLabel: ((Float) -> String)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Dimmed the way a disabled settings row is, rather than recoloured.
        val alpha = if (enabled) 1f else DISABLED_SLIDER_ALPHA
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        MdSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled
        )
        if (valueLabel != null) {
            Text(
                valueLabel(sliderValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The same dimming as a disabled settings row. */
private const val DISABLED_SLIDER_ALPHA = 0.6f
