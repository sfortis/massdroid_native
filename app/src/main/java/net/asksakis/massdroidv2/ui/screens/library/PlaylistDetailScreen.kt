package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }
    var moveTrack by remember { mutableStateOf<Track?>(null) }
    var moveFallbackPosition by remember { mutableStateOf(0) }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.togglePlaylistFavorite() }) {
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
            items(items = tracks, key = { track -> "${track.uri}:${track.position ?: -1}" }) { track ->
                val fallbackPosition = tracks.indexOf(track)
                MediaItemRow(
                    title = track.name,
                    subtitle = "${track.artistNames} - ${track.albumName}".trimEnd(' ', '-'),
                    imageUrl = track.imageUrl,
                    onClick = { viewModel.playTrack(track) },
                    onPlayClick = { viewModel.playTrack(track) },
                    favorite = track.favorite,
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
                    },
                    showEqualizer = busyTrackUri == track.uri
                )
            }
        }
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
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
