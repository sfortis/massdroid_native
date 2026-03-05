package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaItemGrid
import net.asksakis.massdroidv2.ui.components.MediaItemRow

@Composable
fun LibraryScreen(
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val selectedTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val tabs = listOf("Artists", "Albums", "Tracks", "Playlists")

    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()

    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Action sheet state
    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }

    LaunchedEffect(selectedTab, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        when (selectedTab) {
            0 -> if (artists.isEmpty()) viewModel.loadArtists()
            1 -> if (albums.isEmpty()) viewModel.loadAlbums()
            2 -> if (tracks.isEmpty()) viewModel.loadTracks()
            3 -> if (playlists.isEmpty()) viewModel.loadPlaylists()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            // Compact landscape header: search + tabs + sort in minimal space
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compact search field
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearch(it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    placeholder = { Text("Search...", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearch("") }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Sort + display mode controls
                SortDropdown(selected = sortOption, onSelect = { viewModel.updateSort(it) })
                IconButton(onClick = { viewModel.toggleSortDirection() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = "Toggle sort direction",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = { viewModel.toggleFavoritesFilter() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Filter favorites",
                        modifier = Modifier.size(18.dp),
                        tint = if (favoritesOnly) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.toggleLibraryDisplayMode() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (displayMode == LibraryDisplayMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = "Toggle view",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // Compact tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                modifier = Modifier.height(36.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { viewModel.setCurrentTab(index) },
                        text = { Text(title, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(36.dp)
                    )
                }
            }
        } else {
            // Portrait: full-size header
            // Search bar at top
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = {
                    Text(when (selectedTab) {
                        0 -> "Search artists..."
                        1 -> "Search albums..."
                        2 -> "Search tracks..."
                        3 -> "Search playlists..."
                        else -> "Search library..."
                    })
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearch("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { viewModel.setCurrentTab(index) },
                        text = { Text(title) }
                    )
                }
            }

            // Sort + Display mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SortDropdown(
                        selected = sortOption,
                        onSelect = { viewModel.updateSort(it) }
                    )
                    IconButton(onClick = { viewModel.toggleSortDirection() }) {
                        Icon(
                            if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = "Toggle sort direction",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFavoritesFilter() }) {
                        Icon(
                            if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Filter favorites",
                            modifier = Modifier.size(20.dp),
                            tint = if (favoritesOnly) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { viewModel.toggleLibraryDisplayMode() }) {
                    Icon(
                        if (displayMode == LibraryDisplayMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = "Toggle view"
                    )
                }
            }
        }

        if (isLoading && !isRefreshing) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            @OptIn(ExperimentalMaterial3Api::class)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f)
            ) {
                when (selectedTab) {
                    0 -> MediaList(
                        items = artists,
                        displayMode = displayMode,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { viewModel.loadMoreArtists() },
                        key = { it.uri },
                        title = { it.name },
                        subtitle = { "" },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { onArtistClick(it) },
                        onLongClick = { artist ->
                            actionSheetItem = ActionSheetItem(artist.name, "", artist.uri, artist.imageUrl, artist.favorite, MediaType.ARTIST, artist.itemId)
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                    1 -> MediaList(
                        items = albums,
                        displayMode = displayMode,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { viewModel.loadMoreAlbums() },
                        key = { it.uri },
                        title = { it.name },
                        subtitle = { listOfNotNull(it.artistNames.ifEmpty { null }, it.year?.toString()).joinToString(" · ") },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { onAlbumClick(it) },
                        onLongClick = { album ->
                            actionSheetItem = ActionSheetItem(album.name, album.artistNames, album.uri, album.imageUrl, album.favorite, MediaType.ALBUM, album.itemId)
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                    2 -> MediaList(
                        items = tracks,
                        displayMode = displayMode,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { viewModel.loadMoreTracks() },
                        key = { it.uri },
                        title = { it.name },
                        subtitle = { "${it.artistNames} - ${it.albumName}".trimEnd(' ', '-') },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { viewModel.playTrack(it) },
                        onLongClick = { track ->
                            actionSheetItem = ActionSheetItem(track.name, track.artistNames, track.uri, track.imageUrl, track.favorite, MediaType.TRACK, track.itemId)
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                    3 -> MediaList(
                        items = playlists,
                        displayMode = displayMode,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { viewModel.loadMorePlaylists() },
                        key = { it.uri },
                        title = { it.name },
                        subtitle = { "" },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { onPlaylistClick(it) },
                        onLongClick = { playlist ->
                            actionSheetItem = ActionSheetItem(playlist.name, "", playlist.uri, playlist.imageUrl, playlist.favorite, MediaType.PLAYLIST, playlist.itemId)
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                }
            }
        }
    }

    // Action sheet
    actionSheetItem?.let { target ->
        val players by viewModel.players.collectAsStateWithLifecycle()
        MediaActionSheet(
            title = target.title,
            subtitle = target.subtitle,
            imageUrl = target.imageUrl,
            players = players,
            selectedPlayerId = players.firstOrNull()?.playerId,
            favorite = target.favorite,
            onToggleFavorite = {
                viewModel.toggleFavorite(target.uri, target.mediaType, target.itemId, target.favorite)
            },
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = { viewModel.startRadio(target.uri) },
            onDismiss = { actionSheetItem = null }
        )
    }
}

@Composable
private fun SortDropdown(
    selected: SortOption,
    onSelect: (SortOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(selected.label) },
            leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    trailingIcon = {
                        if (option == selected) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> MediaList(
    items: List<T>,
    displayMode: LibraryDisplayMode,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    key: (T) -> Any,
    title: (T) -> String,
    subtitle: (T) -> String,
    imageUrl: (T) -> String?,
    favorite: (T) -> Boolean = { false },
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    onPlayClick: ((T) -> Unit)? = null
) {
    when (displayMode) {
        LibraryDisplayMode.LIST -> {
            val listState = rememberLazyListState()
            InfiniteListHandler(listState, items.size, threshold = 5, onLoadMore = onLoadMore)
            LazyColumn(state = listState) {
                items(
                    items = items,
                    key = { key(it) },
                    contentType = { "library_list_item" }
                ) { item ->
                    MediaItemRow(
                        title = title(item),
                        subtitle = subtitle(item),
                        imageUrl = imageUrl(item),
                        onClick = { onClick(item) },
                        favorite = favorite(item),
                        onLongClick = { onLongClick(item) },
                        onPlayClick = onPlayClick?.let { { it(item) } }
                    )
                }
                if (isLoadingMore) {
                    item { LoadingIndicator() }
                }
            }
        }
        LibraryDisplayMode.GRID -> {
            val gridState = rememberLazyGridState()
            InfiniteGridHandler(gridState, items.size, threshold = 6, onLoadMore = onLoadMore)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = items,
                    key = { key(it) },
                    contentType = { "library_grid_item" }
                ) { item ->
                    MediaItemGrid(
                        title = title(item),
                        subtitle = subtitle(item),
                        imageUrl = imageUrl(item),
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) }
                    )
                }
                if (isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun InfiniteListHandler(
    listState: LazyListState,
    itemCount: Int,
    threshold: Int,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, itemCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (itemCount > 0 && lastVisible >= itemCount - threshold) {
                    onLoadMore()
                }
            }
    }
}

@Composable
private fun InfiniteGridHandler(
    gridState: LazyGridState,
    itemCount: Int,
    threshold: Int,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (itemCount > 0 && lastVisible >= itemCount - threshold) {
                    onLoadMore()
                }
            }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
