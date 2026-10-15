package net.asksakis.massdroidv2.tv.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Bridge between the mini player bar and the active screen's own focus tracking:
 * screens that remember their last-focused item (home/browse already do, for back-stack
 * restoration) register a restore hook here, and the mini player invokes it on exit so
 * the cursor returns to the exact card it came from. Returns false when there is nothing
 * to restore (the caller then falls back to a spatial move).
 */
class TvFocusMemory {
    var restoreToLastFocused: (() -> Boolean)? = null
}

val LocalTvFocusMemory = staticCompositionLocalOf { TvFocusMemory() }
