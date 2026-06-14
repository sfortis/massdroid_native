package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GRID_FOCUS_TRIES = 25
private const val GRID_FOCUS_RETRY_MS = 60L

/**
 * Shared D-pad focus management for a single-grid TV screen (Browse, Folders, Artist…).
 *
 * Solves the app-wide bug where content focus is lost and escapes to the only
 * always-present focusable, the floating mini player pill — and the related bug where
 * returning from the mini player jumped to the FIRST item instead of the scrolled
 * position. The fix never leaves the content unfocused and always restores the exact
 * remembered card, scrolling it back into view first (a card that scrolled out of the
 * lazy window can't be focused directly, which used to fall back to the top).
 *
 * Apply [GridItemFocus.modifierFor] to every grid item (keyed by a stable id such as the
 * uri) and pass the SAME [gridState] to both the grid and this helper. It:
 *  - remembers the last-focused key (survives a detail-screen round trip via saveable),
 *  - on the mini player's exit-to-content restore ([LocalTvFocusMemory]), scrolls the
 *    remembered card into view and focuses it (else the first item),
 *  - with [autoFocusOnLoad], does the same whenever the visible item set changes
 *    (drill-down / async load) so a reloaded list keeps the cursor in the grid.
 *
 * Intra-list navigation does NOT re-assert focus (the load effect keys on the first item,
 * not the moving cursor), so it never fights manual D-pad moves or pagination.
 *
 * Set [autoFocusOnLoad] false on screens with focusable chrome above the grid (e.g.
 * Browse's category chips) so entering the screen does not yank focus into the grid; the
 * restore hook + tracking still keep the mini player exit landing on the right card.
 */
class GridItemFocus internal constructor(
    private val lastKey: MutableState<String?>,
    private val target: String?,
    private val requester: FocusRequester,
) {
    fun modifierFor(key: String): Modifier =
        Modifier
            .onFocusChanged { if (it.isFocused) lastKey.value = key }
            .then(if (key == target) Modifier.focusRequester(requester) else Modifier)
}

@Composable
fun rememberGridItemFocus(
    itemKeys: List<String>,
    gridState: LazyGridState,
    autoFocusOnLoad: Boolean = true,
): GridItemFocus {
    val lastKey = rememberSaveable { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    // Restore target: the remembered key if still present, else the first item. Never
    // null while the list is non-empty, so focus always has a content target.
    val target = lastKey.value?.takeIf { it in itemKeys } ?: itemKeys.firstOrNull()

    val scope = rememberCoroutineScope()
    val currentKeys = rememberUpdatedState(itemKeys)

    // Scroll the remembered target back into the lazy window, THEN focus it. Scrolling
    // first is what fixes "returns to the first item after 2-3 pages": a card that fell
    // out of the composed window can't be focused directly.
    suspend fun focusTarget() {
        val keys = currentKeys.value
        val t = lastKey.value?.takeIf { it in keys } ?: keys.firstOrNull() ?: return
        val idx = keys.indexOf(t)
        // Scroll the target into view ONLY if it isn't already on screen. When it's already
        // visible (the usual mini-player-exit case — the grid didn't move while we were on
        // the pill), scrollToItem would yank it to the top and lose the exact scroll
        // position the user expects. Only a target that scrolled out needs a scroll.
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == idx }
        if (idx >= 0 && !visible) runCatching { gridState.scrollToItem(idx) }
        repeat(GRID_FOCUS_TRIES) {
            if (runCatching { requester.requestFocus() }.isSuccess) return
            delay(GRID_FOCUS_RETRY_MS)
        }
    }

    val focusMemory = LocalTvFocusMemory.current
    DisposableEffect(focusMemory) {
        val hook: () -> Boolean = {
            val keys = currentKeys.value
            val hasTarget = (lastKey.value?.takeIf { it in keys } ?: keys.firstOrNull()) != null
            // Claim the restore (so the host skips its scroll-to-top spatial fallback) and
            // do the scroll+focus asynchronously, since the hook itself is synchronous.
            if (hasTarget) scope.launch { focusTarget() }
            hasTarget
        }
        focusMemory.restoreToLastFocused = hook
        // Only clear our own hook: on navigation the NEW screen registers before the old
        // one disposes, so an unconditional null here would wipe the new screen's hook.
        onDispose {
            if (focusMemory.restoreToLastFocused === hook) focusMemory.restoreToLastFocused = null
        }
    }

    // Re-focus when the visible content CHANGES (load / drill-down / back-stack return) —
    // keyed on the first item, so intra-list navigation and pagination never re-focus.
    if (autoFocusOnLoad) {
        LaunchedEffect(itemKeys.firstOrNull()) {
            if (target != null) focusTarget()
        }
    }

    // Fresh instance each recomposition so modifierFor closes over the CURRENT target. A
    // remembered instance with a plain `target` field is not a Compose snapshot read, so the
    // grid items would not recompose when target changes and the focusRequester would stay
    // stuck on a stale item (requestFocus then no-ops -> the cursor can't return).
    return GridItemFocus(lastKey, target, requester)
}
