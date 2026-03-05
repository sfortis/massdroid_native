package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear
import net.asksakis.massdroidv2.ui.components.MediaActionSheet

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

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }

    Scaffold(
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
            // Album header with artwork and metadata
            item {
                val imageUrl = album?.imageUrl ?: tracks.firstOrNull()?.imageUrl
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Album art",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Album name
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Artist name (clickable if we have artist info)
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

                    // Metadata line: type/year, genres, label
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

                    // Description (expandable)
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

                    // Track count and total duration
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

                    // Split play button
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
                        ModalBottomSheet(onDismissRequest = { showPlaySheet = false }) {
                            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                                ListItem(
                                    headlineContent = { Text("Play Next") },
                                    supportingContent = { Text("Insert after current track") },
                                    leadingContent = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showPlaySheet = false
                                        viewModel.playAllNext()
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Add to Queue") },
                                    supportingContent = { Text("Add to end of queue") },
                                    leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showPlaySheet = false
                                        viewModel.addAllToQueue()
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Replace Queue") },
                                    supportingContent = { Text("Replace queue without playing") },
                                    leadingContent = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showPlaySheet = false
                                        viewModel.replaceQueue()
                                    }
                                )
                                ListItem(
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
            }

            itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                ListItem(
                    headlineContent = {
                        Text(
                            track.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(track.artistNames, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Text(
                            "${track.position?.takeIf { it > 0 } ?: (index + 1)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        track.duration?.let { dur ->
                            Text(
                                "%d:%02d".format((dur / 60).toInt(), (dur % 60).toInt()),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .combinedClickable(
                            onClick = { viewModel.playTrack(track) },
                            onLongClick = {
                                actionSheetItem = ActionSheetItem(
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
                            }
                        )
                )
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
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = { viewModel.startRadio(target.uri) },
            onDismiss = { actionSheetItem = null }
        )
    }
}
