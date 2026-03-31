package net.asksakis.massdroidv2.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom padding needed for content to scroll past the floating mini player.
 * Provided by MassDroidApp, consumed by scrollable screens.
 */
val LocalMiniPlayerPadding = compositionLocalOf { 0.dp }
