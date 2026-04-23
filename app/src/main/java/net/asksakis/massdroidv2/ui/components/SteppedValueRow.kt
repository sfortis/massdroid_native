package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Label + current value on the left, tap-to-step and hold-to-repeat -/+ buttons
 * on the right. Keeps the value visible while the user is interacting because
 * it lives outside the button column, so fingers on the buttons don't obscure it.
 */
@Composable
fun SteppedValueRow(
    label: String,
    valueLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    valueStyle: TextStyle = MaterialTheme.typography.labelLarge,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    buttonSpacing: Dp = 16.dp
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = labelStyle)
            Text(
                valueLabel,
                style = valueStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            RepeatingIconButton(
                onClick = onDecrement,
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    modifier = Modifier.size(iconSize)
                )
            }
            RepeatingIconButton(
                onClick = onIncrement,
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase $label",
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}
