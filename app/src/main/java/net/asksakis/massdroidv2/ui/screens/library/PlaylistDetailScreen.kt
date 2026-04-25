package net.asksakis.massdroidv2.ui.screens.library

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaActionSheetExtraAction
import net.asksakis.massdroidv2.ui.components.MediaItemRow
import net.asksakis.massdroidv2.ui.components.SheetDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val playlistName by viewModel.playlistName.collectAsStateWithLifecycle()
    val isFavorite by viewModel.favorite.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val busyTrackUri by viewModel.busyTrackUri.collectAsStateWithLifecycle()
    val sortKey by viewModel.sortKey.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val currentTrackUri by viewModel.currentTrackUri.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }
    var moveTrack by remember { mutableStateOf<Track?>(null) }
    var moveFallbackPosition by remember { mutableStateOf(0) }
    var showSortSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.error.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(playlistName) },
                navigationIcon = {
                    MdIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    MdIconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Sort options")
                    }
                    MdIconButton(onClick = { viewModel.togglePlaylistFavorite() }) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .fadingEdges(),
            contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)
        ) {
            item(key = "play-all") {
                var showPlaySheet by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MdTextButton(
                        onClick = { viewModel.playAll() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play All")
                    }
                    MdTextButton(
                        onClick = { showPlaySheet = true },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "More options")
                    }
                }

                if (showPlaySheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showPlaySheet = false },
                        containerColor = SheetDefaults.containerColor()
                    ) {
                        Column(modifier = Modifier.padding(bottom = 32.dp)) {
                            ListItem(
                                colors = SheetDefaults.listItemColors(),
                                headlineContent = { Text("Play Next") },
                                supportingContent = { Text("Insert after current track") },
                                leadingContent = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showPlaySheet = false
                                    viewModel.playAllNext()
                                }
                            )
                            ListItem(
                                colors = SheetDefaults.listItemColors(),
                                headlineContent = { Text("Add to Queue") },
                                supportingContent = { Text("Add to end of queue") },
                                leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showPlaySheet = false
                                    viewModel.addAllToQueue()
                                }
                            )
                            ListItem(
                                colors = SheetDefaults.listItemColors(),
                                headlineContent = { Text("Replace Queue") },
                                supportingContent = { Text("Replace queue without playing") },
                                leadingContent = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showPlaySheet = false
                                    viewModel.replaceQueue()
                                }
                            )
                            ListItem(
                                colors = SheetDefaults.listItemColors(),
                                headlineContent = { Text("Start Radio") },
                                supportingContent = { Text("Play similar tracks") },
                                leadingContent = { Icon(Icons.Default.Radio, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showPlaySheet = false
                                    viewModel.startRadioAll()
                                }
                            )
                        }
                    }
                }
            }
            items(items = tracks, key = { track -> "${track.uri}:${track.position ?: -1}" }) { track ->
                val fallbackPosition = tracks.indexOf(track)
                val isCurrent = currentTrackUri == track.uri
                MediaItemRow(
                    title = track.name,
                    subtitle = "${track.artistNames} - ${track.albumName}".trimEnd(' ', '-'),
                    imageUrl = track.imageUrl,
                    titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    onClick = { viewModel.playTrack(track) },
                    onPlayClick = { viewModel.playTrack(track) },
                    favorite = track.favorite,
                    showEqualizer = isCurrent && isPlaying,
                    onMoreClick = {
                        actionSheetItem = ActionSheetItem(
                            title = track.name,
                            subtitle = track.artistNames,
                            uri = track.uri,
                            imageUrl = track.imageUrl,
                            favorite = track.favorite,
                            mediaType = MediaType.TRACK,
                            itemId = track.itemId,
                            position = track.position ?: fallbackPosition,
                            primaryArtistUri = track.artistUri,
                            primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "Artist" }
                        )
                    },
                    onLongClick = {
                        actionSheetItem = ActionSheetItem(
                            title = track.name,
                            subtitle = track.artistNames,
                            uri = track.uri,
                            imageUrl = track.imageUrl,
                            favorite = track.favorite,
                            mediaType = MediaType.TRACK,
                            itemId = track.itemId,
                            position = track.position ?: fallbackPosition,
                            primaryArtistUri = track.artistUri,
                            primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "Artist" }
                        )
                    }
                )
            }
        }
    }

    if (showSortSheet) {
        PlaylistSortSheet(
            sortKey = sortKey,
            sortDescending = sortDescending,
            favoritesOnly = favoritesOnly,
            onSortSelect = { viewModel.setSortKey(it) },
            onToggleDirection = { viewModel.setSortKey(sortKey) },
            onToggleFavorites = { viewModel.toggleFavoritesOnly() },
            onDismiss = { showSortSheet = false }
        )
    }

    actionSheetItem?.let { target ->
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
            extraActions = listOf(
                MediaActionSheetExtraAction(
                    title = "Remove from Playlist",
                    icon = { Icon(Icons.Default.PlaylistRemove, contentDescription = null) },
                    onClick = {
                        val track = tracks.firstOrNull { it.uri == target.uri && (it.position ?: target.position) == target.position }
                            ?: return@MediaActionSheetExtraAction
                        viewModel.removeTrackFromPlaylist(track, target.position ?: 0)
                    }
                ),
                MediaActionSheetExtraAction(
                    title = "Move to Playlist",
                    icon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = {
                        moveTrack = tracks.firstOrNull { it.uri == target.uri && (it.position ?: target.position) == target.position }
                        moveFallbackPosition = target.position ?: 0
                    }
                )
            ),
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = { viewModel.startRadio(target.uri) },
            onDismiss = { actionSheetItem = null }
        )
    }

    moveTrack?.let { track ->
        MoveTrackDialog(
            playlists = playlists.filterNot { it.itemId == viewModel.itemId && it.provider == viewModel.provider },
            onDismiss = { moveTrack = null },
            onPlaylistSelected = { destination ->
                viewModel.moveTrackToPlaylist(track, moveFallbackPosition, destination)
                moveTrack = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSortSheet(
    sortKey: PlaylistSortKey,
    sortDescending: Boolean,
    favoritesOnly: Boolean,
    onSortSelect: (PlaylistSortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleFavorites: () -> Unit,
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
                text = "Playlist options",
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
                        selected = sortKey,
                        onSelect = onSortSelect
                    )
                    FilterChip(
                        selected = sortDescending,
                        onClick = onToggleDirection,
                        label = { Text("Descending") }
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Display",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = onToggleFavorites,
                    label = { Text("Favorites only") }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SortDropdown(
    selected: PlaylistSortKey,
    onSelect: (PlaylistSortKey) -> Unit
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
            PlaylistSortKey.entries.forEach { option ->
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
private fun MoveTrackDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (Playlist) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("No other playlists available.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(playlists, key = { it.uri }) { playlist ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                            onClick = { onPlaylistSelected(playlist) }
                        ) {
                            Text(
                                text = playlist.name,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MdTextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
