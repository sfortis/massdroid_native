package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.AddToPlaylistDialog
import net.asksakis.massdroidv2.ui.components.EqualizerBars
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaActionSheetExtraAction
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onArtistClick: ((Artist) -> Unit)? = null,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albumName by viewModel.albumName.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val currentTrackUri by viewModel.currentTrackUri.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }
    var addToPlaylistTrackUri by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(albumName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleAlbumFavorite() }) {
                        Icon(
                            if (album?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (album?.favorite == true) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumHeader(
                        isLandscape = true,
                        album = album,
                        albumName = albumName,
                        tracks = tracks,
                        onArtistClick = onArtistClick,
                        viewModel = viewModel
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                ) {
                    itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                        AlbumTrackItem(
                            track = track,
                            index = index,
                            isCurrent = track.uri == currentTrackUri,
                            isPlaying = isPlaying,
                            album = album,
                            onPlay = { viewModel.playTrack(track) },
                            onAction = { actionSheetItem = it }
                        )
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AlbumHeader(
                            isLandscape = false,
                            album = album,
                            albumName = albumName,
                            tracks = tracks,
                            onArtistClick = onArtistClick,
                            viewModel = viewModel
                        )
                    }
                }
                itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                    AlbumTrackItem(
                        track = track,
                        index = index,
                        isCurrent = track.uri == currentTrackUri,
                        isPlaying = isPlaying,
                        album = album,
                        onPlay = { viewModel.playTrack(track) },
                        onAction = { actionSheetItem = it }
                    )
                }
            }
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
            extraActions = if (target.mediaType == MediaType.TRACK) listOf(
                MediaActionSheetExtraAction(
                    title = "Add to Playlist",
                    icon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
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
            onStartRadio = { viewModel.startRadio(target.uri) },
            onDismiss = { actionSheetItem = null }
        )
    }

    addToPlaylistTrackUri?.let { trackUri ->
        val editablePlaylists by viewModel.editablePlaylists.collectAsStateWithLifecycle()
        val isLoadingPlaylists by viewModel.isLoadingEditablePlaylists.collectAsStateWithLifecycle()
        val addingToPlaylistId by viewModel.addingToPlaylistId.collectAsStateWithLifecycle()
        val playlistContainsTrack by viewModel.playlistContainsTrack.collectAsStateWithLifecycle()
        AddToPlaylistDialog(
            playlists = editablePlaylists,
            isLoading = isLoadingPlaylists,
            addingToPlaylistId = addingToPlaylistId,
            onDismiss = { addToPlaylistTrackUri = null },
            onRetry = { viewModel.loadEditablePlaylists(trackUri) },
            onPlaylistClick = { playlist -> viewModel.addTrackToPlaylist(playlist, trackUri) },
            onCreatePlaylist = { name -> viewModel.createPlaylistAndAddTrack(name, trackUri) },
            onRemoveFromPlaylist = { playlist -> viewModel.removeTrackFromPlaylist(playlist, trackUri) },
            containsTrack = playlistContainsTrack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumHeader(
    isLandscape: Boolean,
    album: Album?,
    albumName: String,
    tracks: List<Track>,
    onArtistClick: ((Artist) -> Unit)?,
    viewModel: AlbumDetailViewModel
) {
    val imageUrl = album?.imageUrl ?: tracks.firstOrNull()?.imageUrl

    val artModifier = if (isLandscape) {
        Modifier.widthIn(max = 120.dp).aspectRatio(1f)
    } else {
        Modifier.fillMaxWidth().padding(horizontal = 48.dp).aspectRatio(1f)
    }

    AsyncImage(
        model = imageUrl,
        contentDescription = "Album art",
        modifier = artModifier.clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Crop
    )
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = albumName,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    val artistDisplayName = album?.artistNames?.ifBlank { null }
        ?: tracks.firstOrNull()?.artistNames?.ifBlank { null }
    val clickableArtist = album?.artists?.firstOrNull()
    if (artistDisplayName != null) {
        Spacer(modifier = Modifier.height(4.dp))
        if (clickableArtist != null && onArtistClick != null) {
            Text(
                text = artistDisplayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onArtistClick(clickableArtist) }
            )
        } else {
            Text(
                text = artistDisplayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val metaParts = mutableListOf<String>()
    val typeYear = formatAlbumTypeYear(album?.albumType, album?.year)
    if (typeYear.isNotBlank()) metaParts.add(typeYear)
    val genres = album?.genres ?: emptyList()
    if (genres.isNotEmpty()) metaParts.add(genres.take(2).joinToString(", "))
    album?.label?.let { if (it.isNotBlank()) metaParts.add(it) }
    if (metaParts.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = metaParts.joinToString(" \u00b7 "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val description = album?.description
    if (!description.isNullOrBlank()) {
        var expanded by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.animateContentSize()
        )
        Text(
            text = if (expanded) "Show less" else "Show more",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { expanded = !expanded }
        )
    }

    if (tracks.isNotEmpty()) {
        val totalSeconds = tracks.mapNotNull { it.duration }.sum()
        val durationText = if (totalSeconds > 0) {
            val mins = (totalSeconds / 60).toInt()
            val secs = (totalSeconds % 60).toInt()
            "%d:%02d".format(mins, secs)
        } else null
        val infoParts = mutableListOf("${tracks.size} tracks")
        durationText?.let { infoParts.add(it) }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = infoParts.joinToString(" \u00b7 "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    var showPlaySheet by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { viewModel.playAll() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Play All")
        }
        TextButton(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackItem(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    album: Album?,
    onPlay: () -> Unit,
    onAction: (ActionSheetItem) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                track.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        },
        supportingContent = {
            Text(track.artistNames, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (isCurrent && isPlaying) {
                EqualizerBars(modifier = Modifier.size(24.dp))
            } else {
                Text(
                    "${track.position?.takeIf { it > 0 } ?: (index + 1)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                track.duration?.let { dur ->
                    Text(
                        "%d:%02d".format((dur / 60).toInt(), (dur % 60).toInt()),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(
                    onClick = {
                        onAction(
                            ActionSheetItem(
                                title = track.name,
                                subtitle = track.artistNames,
                                uri = track.uri,
                                imageUrl = track.imageUrl,
                                favorite = track.favorite,
                                mediaType = MediaType.TRACK,
                                itemId = track.itemId,
                                primaryArtistUri = track.artistUri ?: album?.artists?.firstOrNull()?.uri,
                                primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim()
                                    .orEmpty()
                                    .ifBlank { album?.artists?.firstOrNull()?.name ?: "Artist" }
                            )
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = {
                    onAction(
                        ActionSheetItem(
                            title = track.name,
                            subtitle = track.artistNames,
                            uri = track.uri,
                            imageUrl = track.imageUrl,
                            favorite = track.favorite,
                            mediaType = MediaType.TRACK,
                            itemId = track.itemId,
                            primaryArtistUri = track.artistUri ?: album?.artists?.firstOrNull()?.uri,
                            primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim()
                                .orEmpty()
                                .ifBlank { album?.artists?.firstOrNull()?.name ?: "Artist" }
                        )
                    )
                }
            )
    )
}
