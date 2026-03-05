package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaItemRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val artistName by viewModel.artistName.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artistName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleArtistFavorite() }) {
                        Icon(
                            if (artist?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (artist?.favorite == true) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero header
            item {
                val imageUrl = artist?.imageUrl
                    ?: albums.firstOrNull()?.imageUrl
                    ?: tracks.firstOrNull()?.imageUrl
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = "Artist image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val genres = artist?.genres ?: emptyList()
                    if (genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = genres.take(3).joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val parts = mutableListOf<String>()
                    if (albums.isNotEmpty()) parts.add("${albums.size} albums")
                    if (tracks.isNotEmpty()) parts.add("${tracks.size} tracks")
                    if (parts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = parts.joinToString(" \u00b7 "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Description (expandable)
                    val description = artist?.description
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
                }
            }

            if (albums.isNotEmpty()) {
                item {
                    Text(
                        "Albums",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(albums, key = { it.uri }) { album ->
                    val subtitle = listOfNotNull(
                        album.year?.toString(),
                        album.version?.ifEmpty { null }
                    ).joinToString(" \u00b7 ")
                    MediaItemRow(
                        title = album.name,
                        subtitle = subtitle,
                        imageUrl = album.imageUrl,
                        onClick = { onAlbumClick(album) },
                        favorite = album.favorite,
                        onLongClick = {
                            actionSheetItem = ActionSheetItem(album.name, "", album.uri, album.imageUrl, album.favorite, MediaType.ALBUM, album.itemId)
                        },
                        onPlayClick = { viewModel.quickPlay(album.uri) }
                    )
                }
            }

            if (tracks.isNotEmpty()) {
                item {
                    var showPlaySheet by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Top Tracks",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { viewModel.playAllTracks() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play All", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { showPlaySheet = true },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "More options")
                            }
                        }
                    }
                    if (showPlaySheet) {
                        ModalBottomSheet(onDismissRequest = { showPlaySheet = false }) {
                            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                                ListItem(
                                    headlineContent = { Text("Add to Queue") },
                                    modifier = Modifier.clickable {
                                        viewModel.playAllTracks(option = "add")
                                        showPlaySheet = false
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Play Next") },
                                    modifier = Modifier.clickable {
                                        viewModel.playAllTracks(option = "next")
                                        showPlaySheet = false
                                    }
                                )
                            }
                        }
                    }
                }
                items(tracks, key = { it.uri }) { track ->
                    MediaItemRow(
                        title = track.name,
                        subtitle = track.albumName,
                        imageUrl = track.imageUrl,
                        onClick = { viewModel.playTrack(track) },
                        favorite = track.favorite,
                        onLongClick = {
                            actionSheetItem = ActionSheetItem(track.name, track.artistNames, track.uri, track.imageUrl, track.favorite, MediaType.TRACK, track.itemId)
                        }
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
