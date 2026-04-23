package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * IconButton that fires onClick once on tap and continues firing while held,
 * after a short initial delay, ramping up the repeat rate for faster adjustments.
 */
@Composable
fun RepeatingIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    initialDelayMs: Long = 400L,
    minIntervalMs: Long = 40L,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentOnClick by rememberUpdatedState(onClick)

    LaunchedEffect(isPressed, enabled) {
        if (!enabled || !isPressed) return@LaunchedEffect
        // Initial tap fires immediately, then we hold-repeat while pressed.
        currentOnClick()
        delay(initialDelayMs)
        var interval = initialDelayMs / 2
        while (isPressed) {
            currentOnClick()
            delay(interval)
            interval = (interval * 2 / 3).coerceAtLeast(minIntervalMs)
        }
    }

    IconButton(
        onClick = {},  // fires via interactionSource (initial press) + LaunchedEffect (repeat)
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = { content() }
    )
}
