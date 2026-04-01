package net.asksakis.massdroidv2.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the app is connected to the MA server.
 * Provided by MassDroidApp, consumed by screens to disable actions when disconnected.
 */
val LocalIsConnected = compositionLocalOf { false }
