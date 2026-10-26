package net.asksakis.massdroidv2.tv.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.SortOption
import net.asksakis.massdroidv2.domain.model.sortOptionsFor

/** Full server library browse: category chips + a paginated grid of everything. */
@Composable
fun TvBrowseScreen(
    onOpenArtist: (itemId: String, provider: String) -> Unit,
    onOpenFolders: () -> Unit,
    viewModel: TvBrowseViewModel = hiltViewModel()
) {
    val category by viewModel.category.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    var showSortDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // Shared D-pad focus: remembers the focused card, serves the mini player's exit
    // restore, and validates the remembered key against the current list so a stale key
    // (after a category switch) never escapes to the mini player bar or the category chips.
    // autoFocusOnLoad = false: the category chips sit above the grid, so entering the
    // screen must not yank focus into the grid; the grid never loses focus on its own.
    val itemKeys = remember(items) { items.map { it.uri } }
    // The selected category takes the cursor on the first entry. A return from an artist has
    // a saved card, which the grid focus helper restores instead.
    var entered by rememberSaveable { mutableStateOf(false) }
    val chipFocus = rememberInitialFocus(enabled = !entered) { entered = true }
    val gridFocus = rememberGridItemFocus(itemKeys, gridState, autoFocusOnLoad = false)
    // Folders is a chip, not a card, so neither of the above brings the cursor back to it, and
    // a return from Folders left it on the first chip.
    var returnToFolders by rememberSaveable { mutableStateOf(false) }
    val foldersFocus = remember { FocusRequester() }
    // Runs when the screen composes again on the way back, not when the flag is set.
    LaunchedEffect(Unit) {
        if (returnToFolders) {
            foldersFocus.requestFocusWhenAttached()
            returnToFolders = false
        }
    }
    val openFolders = {
        returnToFolders = true
        gridFocus.forget()
        onOpenFolders()
    }

    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= items.size - BROWSE_LOAD_MORE_THRESHOLD) viewModel.loadMore() }
    }

    TvGridScreen(
        title = "Browse",
        onSort = { showSortDialog = true },
        header = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TV_GRID_EDGE),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BrowseCategory.entries.forEach { cat ->
                    CategoryChip(
                        cat.label,
                        selected = cat == category,
                        modifier = if (cat == category) Modifier.focusRequester(chipFocus) else Modifier,
                    ) { viewModel.selectCategory(cat) }
                }
                // Provider folder tree (RadioBrowser, ORF, Filesystem...), the MA UI "Browse".
                // Not a flat category, so it opens its own drill-down screen.
                CategoryChip(
                    "Folders",
                    selected = false,
                    modifier = Modifier.focusRequester(foldersFocus),
                    onClick = openFolders
                )
            }
        }
    ) {
        if (items.isEmpty()) {
            when (load) {
                TvLoad.LOADING -> TvContentMessage("Loading\u2026", TV_GRID_EDGE)
                TvLoad.FAILED ->
                    TvLoadFailed("Couldn't load ${category.label.lowercase()}.", TV_GRID_EDGE, onRetry = viewModel::retry)
                TvLoad.READY -> TvContentMessage("Nothing here", TV_GRID_EDGE)
            }
        } else {
            TvMediaGrid(gridState) {
                items(items, key = { it.uri }) { item ->
                    TvMediaCard(
                        title = item.title,
                        imageUrl = item.imageUrl,
                        subtitle = item.subtitle,
                        circular = item.circular,
                        modifier = gridFocus.modifierFor(item.uri)
                    ) {
                        if (item.isArtist) onOpenArtist(item.itemId, item.provider)
                        else viewModel.play(item.uri)
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        TvSortDialog(
            current = sortOption,
            sortOptions = availableSortOptions(category),
            descending = sortDescending,
            onSelect = { option ->
                viewModel.setSort(option)
                showSortDialog = false
            },
            onSetDescending = { viewModel.setSortDescending(it) },
            onDismiss = { showSortDialog = false }
        )
    }
}

/**
 * Sort options offered per category. Which ones a category can ask the server for is a property
 * of the media type rather than of this screen, so the list comes from the domain model.
 */
private fun availableSortOptions(category: BrowseCategory): List<SortOption> =
    sortOptionsFor(category.libraryTab)

private const val BROWSE_LOAD_MORE_THRESHOLD = 12

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        ClickableSurfaceDefaults.colors()
    }
    Surface(
        onClick = onClick,
        colors = colors,
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        modifier = modifier.pointerClick(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
