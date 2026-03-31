package net.asksakis.massdroidv2.ui.screens.library

import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear
import net.asksakis.massdroidv2.ui.components.AddToPlaylistDialog
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaActionSheetExtraAction
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.ui.components.LocalProviderManifestCache
import net.asksakis.massdroidv2.ui.components.ProviderBadges
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
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

    val selectedProviders by viewModel.selectedProviders.collectAsStateWithLifecycle()
    val providerCache = LocalProviderManifestCache.current
    val allMusicProviders by providerCache.musicProvidersFlow.collectAsStateWithLifecycle()
    val musicProviders = remember(allMusicProviders, selectedTab) {
        providerCache.musicProvidersForTab(selectedTab)
    }

    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val isBrowseTab = selectedTab == 5
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showControlsSheet by remember { mutableStateOf(false) }
    var landscapeSearchExpanded by remember { mutableStateOf(false) }

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
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var deletePlaylistTarget by remember { mutableStateOf<ActionSheetItem?>(null) }
    var addToPlaylistTrackUri by remember { mutableStateOf<String?>(null) }

    val initConnectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    LaunchedEffect(selectedTab, settingsLoaded, initConnectionState) {
        if (!settingsLoaded) return@LaunchedEffect
        if (initConnectionState !is ConnectionState.Connected) return@LaunchedEffect
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
            LibraryCompactHeader(
                searchExpanded = landscapeSearchExpanded,
                searchQuery = searchQuery,
                searchPlaceholder = when (selectedTab) {
                    0 -> "Search artists..."
                    1 -> "Search albums..."
                    2 -> "Search tracks..."
                    3 -> "Search playlists..."
                    4 -> "Search radios..."
                    else -> "Search..."
                },
                onSearchChange = { viewModel.updateSearch(it) },
                onClearSearch = {
                    viewModel.updateSearch("")
                    landscapeSearchExpanded = false
                },
                onSearchIme = { focusManager.clearFocus() },
                onToggleSearch = { landscapeSearchExpanded = !landscapeSearchExpanded },
                onOpenControls = { showControlsSheet = true }
            )
        } else {
            LibraryHeader(
                onOpenControls = { showControlsSheet = true }
            )

            TextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = {
                    Text(
                        when (selectedTab) {
                            0 -> "Search artists..."
                            1 -> "Search albums..."
                            2 -> "Search tracks..."
                            3 -> "Search playlists..."
                            4 -> "Search radios..."
                            else -> "Search..."
                        }
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearch("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tabs) { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { viewModel.setCurrentTab(index) },
                    label = { Text(title) }
                )
            }
        }

        val isDisconnected = initConnectionState is ConnectionState.Disconnected ||
            initConnectionState is ConnectionState.Error
        if (isDisconnected && !isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Not connected to Music Assistant", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (isLoading && !isRefreshing) {
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
                        onPlayClick = { viewModel.quickPlay(it.uri) },
                        providerDomains = { it.providerDomains }
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
                        onPlayClick = { viewModel.quickPlay(it.uri) },
                        providerDomains = { it.providerDomains }
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
                        onClick = { track ->
                            if (track.albumItemId != null && track.albumProvider != null) {
                                onAlbumClick(Album(itemId = track.albumItemId, provider = track.albumProvider, name = track.albumName, uri = ""))
                            } else {
                                viewModel.playTrack(track)
                            }
                        },
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
                        onPlayClick = { viewModel.quickPlay(it.uri) },
                        providerDomains = { it.providerDomains }
                    )
                    3 -> Box(modifier = Modifier.fillMaxSize()) {
                        MediaList(
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
                            onPlayClick = { viewModel.quickPlay(it.uri) },
                            providerDomains = { it.providerDomains }
                        )
                        FloatingActionButton(
                            onClick = { showCreatePlaylistDialog = true },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Playlist")
                        }
                    }
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
                        onPlayClick = { viewModel.quickPlay(it.uri) },
                        providerDomains = { it.providerDomains }
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
            extraActions = if (target.mediaType == MediaType.TRACK) listOf(
                MediaActionSheetExtraAction(
                    title = "Add to Playlist",
                    icon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        addToPlaylistTrackUri = target.uri
                        viewModel.loadEditablePlaylists(target.uri)
                        actionSheetItem = null
                    }
                )
            ) else emptyList(),
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = if (isRadio) null else {
                { viewModel.startRadio(target.uri) }
            },
            onDelete = if (isPlaylist) {
                {
                    deletePlaylistTarget = target
                    actionSheetItem = null
                }
            } else null,
            onDismiss = { actionSheetItem = null }
        )
    }

    addToPlaylistTrackUri?.let { trackUri ->
        val editablePlaylists by viewModel.editablePlaylists.collectAsStateWithLifecycle()
        val isLoadingEditablePlaylists by viewModel.isLoadingEditablePlaylists.collectAsStateWithLifecycle()
        val addingToPlaylistId by viewModel.addingToPlaylistId.collectAsStateWithLifecycle()
        val playlistContainsTrack by viewModel.playlistContainsTrack.collectAsStateWithLifecycle()
        AddToPlaylistDialog(
            playlists = editablePlaylists,
            isLoading = isLoadingEditablePlaylists,
            addingToPlaylistId = addingToPlaylistId,
            onDismiss = { addToPlaylistTrackUri = null },
            onRetry = { viewModel.loadEditablePlaylists(trackUri) },
            onPlaylistClick = { playlist -> viewModel.addTrackToPlaylist(playlist, trackUri) },
            onCreatePlaylist = { name -> viewModel.createPlaylistAndAddTrack(name, trackUri) },
            onRemoveFromPlaylist = { playlist -> viewModel.removeTrackFromPlaylist(playlist, trackUri) },
            containsTrack = playlistContainsTrack
        )
    }

    deletePlaylistTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletePlaylistTarget = null },
            title = { Text("Delete Playlist") },
            text = { Text("Delete \"${target.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFromLibrary(MediaType.PLAYLIST, target.itemId, target.uri)
                    deletePlaylistTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePlaylistTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            viewModel.createPlaylist(playlistName.trim())
                            showCreatePlaylistDialog = false
                        }
                    },
                    enabled = playlistName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showControlsSheet) {
        LibraryControlsSheet(
            isBrowseTab = isBrowseTab,
            musicProviders = musicProviders,
            selectedProviders = selectedProviders,
            providerCache = providerCache,
            sortOption = sortOption,
            sortDescending = sortDescending,
            favoritesOnly = favoritesOnly,
            displayMode = displayMode,
            onToggleProvider = { viewModel.toggleProviderFilter(it) },
            onClearProviders = { viewModel.clearProviderFilter() },
            onSortSelect = { viewModel.updateSort(it) },
            onToggleSortDirection = { viewModel.toggleSortDirection() },
            onToggleFavorites = { viewModel.toggleFavoritesFilter() },
            onToggleDisplayMode = { viewModel.toggleLibraryDisplayMode() },
            onDismiss = { showControlsSheet = false }
        )
    }
}

@Composable
private fun LibraryHeader(
    onOpenControls: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineSmall
        )
        FilledTonalIconButton(
            onClick = onOpenControls,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Library options")
        }
    }
}

@Composable
private fun LibraryCompactHeader(
    searchExpanded: Boolean,
    searchQuery: String,
    searchPlaceholder: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchIme: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenControls: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        // Persistent search in landscape
        TextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            placeholder = { Text(searchPlaceholder, style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearchIme() }
            ),
            textStyle = MaterialTheme.typography.bodySmall,
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
        FilledTonalIconButton(
            onClick = onOpenControls,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Library options")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryControlsSheet(
    isBrowseTab: Boolean,
    musicProviders: List<net.asksakis.massdroidv2.data.provider.MusicProvider>,
    selectedProviders: Set<String>,
    providerCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    sortOption: SortOption,
    sortDescending: Boolean,
    favoritesOnly: Boolean,
    displayMode: LibraryDisplayMode,
    onToggleProvider: (String) -> Unit,
    onClearProviders: () -> Unit,
    onSortSelect: (SortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = isLandscape),
        sheetMaxWidth = if (isLandscape) 480.dp else Dp.Unspecified
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Library options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Sort",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SortDropdown(
                        selected = sortOption,
                        onSelect = onSortSelect,
                        options = if (isBrowseTab) listOf(SortOption.NAME) else SortOption.entries
                    )
                    FilterChip(
                        selected = sortDescending,
                        onClick = onToggleSortDirection,
                        label = { Text("Descending") }
                    )
                }
            }

            if (!isBrowseTab) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Display",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = displayMode == LibraryDisplayMode.LIST,
                            onClick = { if (displayMode != LibraryDisplayMode.LIST) onToggleDisplayMode() },
                            label = { Text("List") }
                        )
                        FilterChip(
                            selected = displayMode == LibraryDisplayMode.GRID,
                            onClick = { if (displayMode != LibraryDisplayMode.GRID) onToggleDisplayMode() },
                            label = { Text("Grid") }
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = onToggleFavorites,
                            label = { Text("Favorites only") }
                        )
                    }
                }
            }

            if (!isBrowseTab && musicProviders.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Providers",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ProviderFilterDropdown(
                        providers = musicProviders,
                        selectedIds = selectedProviders,
                        cache = providerCache,
                        onToggle = onToggleProvider,
                        onClear = onClearProviders
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
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
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) }
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
private fun ProviderFilterDropdown(
    providers: List<net.asksakis.massdroidv2.data.provider.MusicProvider>,
    selectedIds: Set<String>,
    cache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasFilter = selectedIds.isNotEmpty()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    Box {
        FilterChip(
            selected = hasFilter,
            onClick = { expanded = true },
            label = {
                Text(
                    if (hasFilter) "${selectedIds.size} selected" else "All providers"
                )
            },
            leadingIcon = {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hasFilter) {
                DropdownMenuItem(
                    text = { Text("Show all") },
                    onClick = {
                        onClear()
                        expanded = false
                    }
                )
                HorizontalDivider()
            }
            providers.forEach { provider ->
                val isSelected = provider.instanceId in selectedIds
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    onClick = { onToggle(provider.instanceId) },
                    leadingIcon = {
                        ProviderBadges(
                            providerDomains = listOf(provider.domain),
                            cache = cache,
                            iconSize = 20.dp,
                            maxIcons = 1
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
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
    onPlayClick: ((T) -> Unit)? = null,
    providerDomains: (T) -> List<String> = { emptyList() }
) {
    val providerCache = LocalProviderManifestCache.current
    when (displayMode) {
        LibraryDisplayMode.LIST -> {
            val listState = rememberLazyListState()
            InfiniteListHandler(listState, items.size, threshold = 5, onLoadMore = onLoadMore)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().fadingEdges(), contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)) {
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
                        onPlayClick = onPlayClick?.let { { it(item) } },
                        providerDomains = providerDomains(item),
                        providerCache = providerCache
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
                modifier = Modifier.fillMaxSize().fadingEdges(),
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = LocalMiniPlayerPadding.current),
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
                        onLongClick = { onLongClick(item) },
                        providerDomains = providerDomains(item),
                        providerCache = providerCache
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
            LazyColumn(modifier = Modifier.fillMaxSize().fadingEdges(), contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)) {
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
                                        "playlist" -> Icons.AutoMirrored.Filled.QueueMusic
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
