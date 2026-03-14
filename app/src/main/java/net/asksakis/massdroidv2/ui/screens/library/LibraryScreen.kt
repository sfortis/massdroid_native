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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear
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
    val tabs = listOf("Artists", "Albums", "Tracks", "Playlists", "Radios", "Browse")

    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val radios by viewModel.radios.collectAsStateWithLifecycle()
    val browseItems by viewModel.browseItems.collectAsStateWithLifecycle()
    val browsePath by viewModel.browsePath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()

    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Reload pending changes when screen becomes visible again
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Action sheet state
    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }

    LaunchedEffect(selectedTab, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        when (selectedTab) {
            0 -> if (artists.isEmpty()) viewModel.loadArtists()
            1 -> if (albums.isEmpty()) viewModel.loadAlbums()
            2 -> if (tracks.isEmpty()) viewModel.loadTracks()
            3 -> if (playlists.isEmpty()) viewModel.loadPlaylists()
            4 -> if (radios.isEmpty()) viewModel.loadRadios()
            5 -> if (browseItems.isEmpty()) viewModel.loadBrowse()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (isLandscape) {
            val isBrowseTab = selectedTab == 5
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
                SortDropdown(
                    selected = sortOption,
                    onSelect = { viewModel.updateSort(it) },
                    options = if (isBrowseTab) listOf(SortOption.NAME) else SortOption.entries
                )
                IconButton(onClick = { viewModel.toggleSortDirection() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = "Toggle sort direction",
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (!isBrowseTab) {
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
            val isBrowseTab = selectedTab == 5

            // Search bar
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
                        4 -> "Search radios..."
                        else -> "Search..."
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
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
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
                        onSelect = { viewModel.updateSort(it) },
                        options = if (isBrowseTab) listOf(SortOption.NAME) else SortOption.entries
                    )
                    IconButton(onClick = { viewModel.toggleSortDirection() }) {
                        Icon(
                            if (sortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = "Toggle sort direction",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!isBrowseTab) {
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
                }

                if (!isBrowseTab) {
                    IconButton(onClick = { viewModel.toggleLibraryDisplayMode() }) {
                        Icon(
                            if (displayMode == LibraryDisplayMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle view"
                        )
                    }
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
                            actionSheetItem = ActionSheetItem(
                                title = artist.name,
                                subtitle = "",
                                uri = artist.uri,
                                imageUrl = artist.imageUrl,
                                favorite = artist.favorite,
                                mediaType = MediaType.ARTIST,
                                itemId = artist.itemId,
                                primaryArtistUri = artist.uri,
                                primaryArtistName = artist.name
                            )
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
                        subtitle = {
                            formatAlbumTypeYear(it.albumType, it.year).ifBlank { it.artistNames }
                        },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { onAlbumClick(it) },
                        onLongClick = { album ->
                            actionSheetItem = ActionSheetItem(
                                title = album.name,
                                subtitle = album.artistNames,
                                uri = album.uri,
                                imageUrl = album.imageUrl,
                                favorite = album.favorite,
                                mediaType = MediaType.ALBUM,
                                itemId = album.itemId,
                                primaryArtistUri = album.artists.firstOrNull()?.uri,
                                primaryArtistName = album.artists.firstOrNull()?.name
                            )
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
                            actionSheetItem = ActionSheetItem(
                                title = track.name,
                                subtitle = track.artistNames,
                                uri = track.uri,
                                imageUrl = track.imageUrl,
                                favorite = track.favorite,
                                mediaType = MediaType.TRACK,
                                itemId = track.itemId,
                                primaryArtistUri = track.artistUri,
                                primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim()
                            )
                        },
                        onMoreClick = { track ->
                            actionSheetItem = ActionSheetItem(
                                title = track.name,
                                subtitle = track.artistNames,
                                uri = track.uri,
                                imageUrl = track.imageUrl,
                                favorite = track.favorite,
                                mediaType = MediaType.TRACK,
                                itemId = track.itemId,
                                primaryArtistUri = track.artistUri,
                                primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim()
                            )
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
                            actionSheetItem = ActionSheetItem(
                                title = playlist.name,
                                subtitle = "",
                                uri = playlist.uri,
                                imageUrl = playlist.imageUrl,
                                favorite = playlist.favorite,
                                mediaType = MediaType.PLAYLIST,
                                itemId = playlist.itemId
                            )
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                    4 -> MediaList(
                        items = radios,
                        displayMode = displayMode,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { viewModel.loadMoreRadios() },
                        key = { it.uri },
                        title = { it.name },
                        subtitle = { "" },
                        imageUrl = { it.imageUrl },
                        favorite = { it.favorite },
                        onClick = { viewModel.quickPlay(it.uri) },
                        onLongClick = { radio ->
                            actionSheetItem = ActionSheetItem(
                                title = radio.name,
                                subtitle = "",
                                uri = radio.uri,
                                imageUrl = radio.imageUrl,
                                favorite = radio.favorite,
                                mediaType = MediaType.RADIO,
                                itemId = radio.itemId,
                                inLibrary = radio.inLibrary
                            )
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) }
                    )
                    5 -> BrowseList(
                        items = browseItems,
                        isLoading = isLoading,
                        browsePath = browsePath,
                        onFolderClick = { viewModel.browseTo(it.path ?: it.uri) },
                        onItemClick = { item ->
                            when (item.mediaType) {
                                "artist" -> onArtistClick(Artist(
                                    itemId = item.itemId, provider = item.provider,
                                    name = item.name, uri = item.uri, imageUrl = item.imageUrl
                                ))
                                "album" -> onAlbumClick(Album(
                                    itemId = item.itemId, provider = item.provider,
                                    name = item.name, uri = item.uri, imageUrl = item.imageUrl
                                ))
                                "playlist" -> onPlaylistClick(Playlist(
                                    itemId = item.itemId, provider = item.provider,
                                    name = item.name, uri = item.uri, imageUrl = item.imageUrl
                                ))
                                else -> viewModel.quickPlay(item.uri)
                            }
                        },
                        onPlayClick = { viewModel.quickPlay(it.uri) },
                        onLongClick = { item ->
                            MediaType.fromApi(item.mediaType)?.let { type ->
                                actionSheetItem = ActionSheetItem(
                                    title = item.name,
                                    uri = item.uri,
                                    imageUrl = item.imageUrl,
                                    favorite = false,
                                    mediaType = type,
                                    itemId = item.itemId
                                )
                            }
                        },
                        onBack = { viewModel.browseBack() }
                    )
                }
            }
        }
    }

    // Action sheet
    actionSheetItem?.let { target ->
        val isRadio = target.mediaType == MediaType.RADIO
            val isPlaylist = target.mediaType == MediaType.PLAYLIST
        val players by viewModel.players.collectAsStateWithLifecycle()
        MediaActionSheet(
            title = target.title,
            subtitle = target.subtitle,
            imageUrl = target.imageUrl,
            players = players,
            selectedPlayerId = players.firstOrNull()?.playerId,
            favorite = target.favorite,
            artistBlocked = target.primaryArtistUri?.let { uri ->
                val key = MediaIdentity.canonicalArtistKey(uri = uri)
                key != null && key in blockedArtistUris
            } ?: false,
            onToggleFavorite = {
                viewModel.toggleFavorite(target.uri, target.mediaType, target.itemId, target.favorite)
            },
            onToggleArtistBlocked = target.primaryArtistUri?.let { uri ->
                { viewModel.toggleArtistBlocked(uri, target.primaryArtistName) }
            },
            onViewInfo = when (target.mediaType) {
                MediaType.ARTIST -> {
                    { onArtistClick(Artist(
                        itemId = target.itemId,
                        provider = target.uri.substringBefore("://"),
                        name = target.title,
                        uri = target.uri,
                        imageUrl = target.imageUrl
                    )) }
                }
                MediaType.ALBUM -> {
                    { onAlbumClick(Album(
                        itemId = target.itemId,
                        provider = target.uri.substringBefore("://"),
                        name = target.title,
                        uri = target.uri,
                        imageUrl = target.imageUrl,
                        artistNames = target.subtitle
                    )) }
                }
                MediaType.TRACK -> target.primaryArtistUri?.let { artistUri ->
                    {
                        onArtistClick(Artist(
                            itemId = artistUri.substringAfterLast("/"),
                            provider = artistUri.substringBefore("://"),
                            name = target.primaryArtistName.orEmpty(),
                            uri = artistUri
                        ))
                    }
                }
                MediaType.PLAYLIST -> {
                    { onPlaylistClick(Playlist(
                        itemId = target.itemId,
                        provider = target.uri.substringBefore("://"),
                        name = target.title,
                        uri = target.uri,
                        imageUrl = target.imageUrl
                    )) }
                }
                else -> null
            },
            inLibrary = target.inLibrary,
            onToggleLibrary = if (isPlaylist) null else {
                {
                    if (isRadio && !target.inLibrary) {
                        viewModel.addRadioToLibrary(
                            Radio(
                                itemId = target.itemId,
                                provider = target.uri.substringBefore("://"),
                                name = target.title,
                                uri = target.uri,
                                imageUrl = target.imageUrl,
                                inLibrary = false
                            )
                        )
                    } else {
                        viewModel.removeFromLibrary(target.mediaType, target.itemId, target.uri)
                    }
                }
            },
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = if (isRadio) null else {
                { viewModel.startRadio(target.uri) }
            },
            onDismiss = { actionSheetItem = null }
        )
    }
}

@Composable
private fun SortDropdown(
    selected: SortOption,
    onSelect: (SortOption) -> Unit,
    options: List<SortOption> = SortOption.entries
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
            options.forEach { option ->
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
    onMoreClick: ((T) -> Unit)? = null,
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
                        onMoreClick = onMoreClick?.let { { it(item) } },
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
                columns = GridCells.Adaptive(minSize = 120.dp),
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseList(
    items: List<BrowseItem>,
    isLoading: Boolean,
    browsePath: String?,
    onFolderClick: (BrowseItem) -> Unit,
    onItemClick: (BrowseItem) -> Unit,
    onPlayClick: (BrowseItem) -> Unit,
    onLongClick: (BrowseItem) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = browsePath != null) { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (browsePath != null) {
            ListItem(
                headlineContent = { Text("..") },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                },
                modifier = Modifier.clickable { onBack() }
            )
        }
        if (isLoading && items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn {
                items(items, key = { it.uri.ifBlank { it.name } }) { item ->
                    ListItem(
                        headlineContent = {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            if (item.isFolder) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            } else if (item.imageUrl != null) {
                                coil.compose.AsyncImage(
                                    model = item.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    when (item.mediaType) {
                                        "artist" -> Icons.Default.Person
                                        "album" -> Icons.Default.Album
                                        "track" -> Icons.Default.MusicNote
                                        "playlist" -> Icons.Default.QueueMusic
                                        "radio" -> Icons.Default.Radio
                                        else -> Icons.Default.MusicNote
                                    },
                                    contentDescription = null
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.isPlayable) {
                                    IconButton(onClick = { onPlayClick(item) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                    }
                                }
                                if (item.isFolder) {
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (item.isFolder) onFolderClick(item) else onItemClick(item)
                            },
                            onLongClick = if (!item.isFolder && item.isPlayable) {
                                { onLongClick(item) }
                            } else null
                        )
                    )
                }
            }
        }
    }
}
