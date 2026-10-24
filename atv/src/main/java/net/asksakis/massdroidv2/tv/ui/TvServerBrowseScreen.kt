package net.asksakis.massdroidv2.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.domain.model.BrowseItem as ServerBrowseItem

/** Server provider folder browse (RadioBrowser, ORF, Filesystem…) with drill-down. */
@Composable
fun TvServerBrowseScreen(viewModel: TvServerBrowseViewModel = hiltViewModel()) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    // Each folder keeps its own focused card and scroll position, so Back lands on the
    // folder the user came out of instead of the first entry.
    val folderStates = rememberSaveableStateHolder()

    // Back pops one folder; at the root it is disabled so the system Back exits.
    BackHandler(enabled = canGoBack) { viewModel.back() }

    TvGridScreen(title = title) {
        folderStates.SaveableStateProvider(folder.id) {
            // The provider reuses its layout nodes when the key changes, and a reused node
            // kept the previous folder's focus: it then reported the card now drawn in its
            // place as focused, overwriting the card saved for this folder. A key of its own
            // gives each folder new nodes.
            key(folder.id) {
                FolderContent(folder.state, onOpen = viewModel::open, onRetry = viewModel::retry)
            }
        }
    }
}

@Composable
private fun FolderContent(
    state: TvServerBrowseViewModel.FolderState,
    onOpen: (ServerBrowseItem) -> Unit,
    onRetry: () -> Unit,
) {
    val entries = (state as? TvServerBrowseViewModel.FolderState.Loaded)?.entries.orEmpty()
    // Drilling into a folder replaces the whole list; the shared grid-focus helper keeps
    // the cursor on a content item so it never escapes to the mini player bar.
    val gridState = rememberLazyGridState()
    val entryKeys = remember(entries) { entries.map { it.browseKey } }
    val gridFocus = rememberGridItemFocus(entryKeys, gridState)

    when (state) {
        TvServerBrowseViewModel.FolderState.Loading -> TvContentMessage("Loading…", TV_GRID_EDGE)
        TvServerBrowseViewModel.FolderState.Failed ->
            TvLoadFailed("Couldn't load this folder.", TV_GRID_EDGE, onRetry = onRetry)
        is TvServerBrowseViewModel.FolderState.Loaded -> if (entries.isEmpty()) {
            TvContentMessage("Nothing here", TV_GRID_EDGE)
        } else {
            TvMediaGrid(gridState) {
                items(entries, key = { it.browseKey }) { entry ->
                    TvMediaCard(
                        title = entry.name,
                        imageUrl = entry.imageUrl,
                        placeholder = if (entry.isFolder) Icons.Filled.Folder else Icons.Filled.MusicNote,
                        // Folder names run long and have no subtitle line to share.
                        titleMaxLines = 2,
                        modifier = gridFocus.modifierFor(entry.browseKey)
                    ) { onOpen(entry) }
                }
            }
        }
    }
}
