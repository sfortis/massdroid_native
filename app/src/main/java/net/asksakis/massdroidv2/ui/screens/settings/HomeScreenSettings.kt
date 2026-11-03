package net.asksakis.massdroidv2.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.ui.components.SETTINGS_SCREEN_BOTTOM_PADDING
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsSwitchRow
import net.asksakis.massdroidv2.ui.components.SettingsTone
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The home screen's settings page: the Music Assistant rows under the app's own sections, each
 * with a switch, in the order they show. A row is moved by its handle. The choice and the order
 * are the Music Assistant user's own, so the web UI and the official apps follow them too.
 *
 * A list of its own rather than a [SettingsScreenColumn], because dragging needs a lazy list
 * that scrolls when a row is pulled to its edge; there can be thirty rows.
 */
@Composable
internal fun HomeScreenSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeRowsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read each time the page opens: the view model outlives it while Settings stays open, and
    // a provider added or removed meanwhile changes the rows.
    LaunchedEffect(Unit) { viewModel.load() }
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String
        val toKey = to.key as? String
        if (fromKey != null && toKey != null) viewModel.moveLocally(fromKey, toKey)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = SETTINGS_SCREEN_BOTTOM_PADDING)
    ) {
        item(key = HEADER_KEY) {
            SettingsSectionHeader(
                "Music Assistant rows",
                caption = "Shown under your own sections, in this order. Drag a row by its handle to " +
                    "move it. The choice is saved to your Music Assistant account, so its web app follows it too."
            )
        }
        state.error?.let { message ->
            item(key = ERROR_KEY) {
                if (state.retryable) {
                    SettingsRow(
                        title = message,
                        icon = Icons.Default.Refresh,
                        supporting = "Tap to try again",
                        onClick = viewModel::load,
                        tone = SettingsTone.ERROR
                    )
                } else {
                    SettingsRow(title = message, icon = Icons.Default.Info)
                }
            }
        }
        if (state.loading) {
            item(key = LOADING_KEY) { SettingsRow(title = "Loading rows…", icon = Icons.Default.Explore) }
        }
        itemsIndexed(state.rows, key = { _, choice -> choice.key }) { index, choice ->
            val above = state.rows.getOrNull(index - 1)?.key
            val below = state.rows.getOrNull(index + 1)?.key
            ReorderableItem(reorderState, key = choice.key) { isDragging ->
                Surface(
                    color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    shadowElevation = if (isDragging) 8.dp else 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics {
                            // The handle cannot be dragged with a screen reader, so moving one
                            // place up or down is offered as an action on the row.
                            customActions = listOfNotNull(
                                above?.let {
                                    CustomAccessibilityAction("Move up") {
                                        viewModel.moveLocally(choice.key, it)
                                        viewModel.commitOrder()
                                        true
                                    }
                                },
                                below?.let {
                                    CustomAccessibilityAction("Move down") {
                                        viewModel.moveLocally(choice.key, it)
                                        viewModel.commitOrder()
                                        true
                                    }
                                }
                            )
                        }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(40.dp)
                                .draggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { viewModel.commitOrder() }
                                )
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = null,
                                tint = if (isDragging) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        SettingsSwitchRow(
                            title = choice.row.name,
                            icon = null,
                            supporting = choice.source,
                            checked = choice.visible,
                            enabled = choice.changeable,
                            onCheckedChange = { viewModel.setVisible(choice, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private const val HEADER_KEY = "header"
private const val ERROR_KEY = "error"
private const val LOADING_KEY = "loading"
