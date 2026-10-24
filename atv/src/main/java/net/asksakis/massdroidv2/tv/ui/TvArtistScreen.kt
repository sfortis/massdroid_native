package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** An artist's albums in a 10-foot grid; click an album to play it on the selected player. */
@Composable
fun TvArtistScreen(viewModel: TvArtistViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    var showSortDialog by remember { mutableStateOf(false) }

    // The albums load async after navigation; the shared grid-focus helper focuses the
    // first card on load so focus never escapes to the mini player bar, and
    // restores the scrolled card (not the top) when returning from the mini player.
    val gridState = rememberLazyGridState()
    val albumKeys = remember(albums) { albums.map { it.uri } }
    val gridFocus = rememberGridItemFocus(albumKeys, gridState)

    TvGridScreen(title = name.ifBlank { "Artist" }, onSort = { showSortDialog = true }) {
        if (albums.isEmpty()) {
            when (load) {
                TvLoad.LOADING -> TvContentMessage("Loading…", TV_GRID_EDGE)
                TvLoad.FAILED ->
                    TvLoadFailed("Couldn't load the albums.", TV_GRID_EDGE, onRetry = viewModel::retry)
                TvLoad.READY -> TvContentMessage("No albums", TV_GRID_EDGE)
            }
        } else {
            TvMediaGrid(gridState) {
                items(albums, key = { it.uri }) { album ->
                    TvMediaCard(
                        title = album.name,
                        imageUrl = album.imageUrl,
                        subtitle = album.year?.toString() ?: album.albumType,
                        modifier = gridFocus.modifierFor(album.uri)
                    ) { viewModel.playMedia(album.uri) }
                }
            }
        }
    }

    if (showSortDialog) {
        TvSortDialog(
            current = sortOption,
            sortOptions = TvArtistViewModel.SORT_OPTIONS,
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
