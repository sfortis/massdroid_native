package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * Two-column key/value row used in the Streaming Status sheet for the
 * primary fields (Transport, Playback, Input, Output, Network). Uses the
 * standard bodyMedium typography in the surface-variant / surface color
 * pair.
 */
@Composable
internal fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Smaller variant of [StatusLine]. Caller controls typography and colors
 * so the sync details block (sync mode, sync lock, etc.) can pack tight.
 */
@Composable
internal fun SmallStatusLine(
    label: String,
    value: String,
    style: TextStyle,
    labelColor: Color,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = style, color = labelColor)
        Text(value, style = style, color = valueColor)
    }
}

/**
 * Two-line status row: the main value on the same line as the label, with
 * a secondary detail below (right-aligned). Used for the Latency row,
 * which shows the headline measurement and the breakdown below it.
 */
@Composable
internal fun DetailStatusLine(
    label: String,
    value: String,
    detail: String,
    style: TextStyle,
    labelColor: Color,
    valueColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = style, color = labelColor)
            Text(value, style = style, color = valueColor)
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
