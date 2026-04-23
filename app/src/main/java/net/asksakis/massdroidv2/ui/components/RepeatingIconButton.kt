package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
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
 * IconButton that fires onClick on every tap AND keeps firing while held, after
 * a short initial delay, ramping up the repeat rate for fast adjustments.
 *
 * onClick fires through the IconButton's own callback (reliable for single taps)
 * and additionally through the LaunchedEffect for hold-to-repeat. Because the
 * initial press also triggers IconButton.onClick, the LaunchedEffect skips the
 * first call and only starts repeating after initialDelayMs to avoid a
 * double-tap on every press.
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
        // IconButton.onClick already fires for the initial press. Wait for the
        // initial hold threshold, then start repeat-firing while still pressed.
        delay(initialDelayMs)
        var interval = initialDelayMs / 2
        while (isPressed) {
            currentOnClick()
            delay(interval)
            interval = (interval * 2 / 3).coerceAtLeast(minIntervalMs)
        }
    }

    IconButton(
        onClick = { currentOnClick() },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = { content() }
    )
}
