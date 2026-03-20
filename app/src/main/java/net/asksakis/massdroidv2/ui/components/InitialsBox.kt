package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InitialsBox(
    name: String,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val initials = name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
    val fontSize = when {
        size <= 32.dp -> 11.sp
        size <= 48.dp -> 14.sp
        else -> 18.sp
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
