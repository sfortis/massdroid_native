package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaItemRow
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit = {},
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val similarArtists by viewModel.similarArtists.collectAsStateWithLifecycle()
    val artistName by viewModel.artistName.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val artistBlocked = artist?.uri?.let { uri ->
        val key = MediaIdentity.canonicalArtistKey(uri = uri)
        key != null && key in blockedArtistUris
    } ?: false

    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(artistName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val willBlock = !artistBlocked
                            viewModel.toggleArtistBlocked(artist?.uri, artistName)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    if (willBlock) "Artist blocked" else "Artist allowed"
                                )
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (artistBlocked) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = if (artistBlocked) "Allow artist again" else "Block artist",
                            tint = if (artistBlocked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
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
                    ArtistHeader(
                        isLandscape = true,
                        artist = artist,
                        artistName = artistName,
                        albums = albums,
                        tracks = tracks,
                        artistBlocked = artistBlocked
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                ) {
                    ArtistContentItems(
                        similarArtists = similarArtists,
                        albums = albums,
                        tracks = tracks,
                        artist = artist,
                        artistName = artistName,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        viewModel = viewModel,
                        onAction = { actionSheetItem = it }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ArtistHeader(
                            isLandscape = false,
                            artist = artist,
                            artistName = artistName,
                            albums = albums,
                            tracks = tracks,
                            artistBlocked = artistBlocked
                        )
                    }
                }
                ArtistContentItems(
                    similarArtists = similarArtists,
                    albums = albums,
                    tracks = tracks,
                    artist = artist,
                    artistName = artistName,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    viewModel = viewModel,
                    onAction = { actionSheetItem = it }
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

@Composable
private fun ArtistHeader(
    isLandscape: Boolean,
    artist: Artist?,
    artistName: String,
    albums: List<Album>,
    tracks: List<Track>,
    artistBlocked: Boolean
) {
    val imageUrl = artist?.imageUrl
        ?: albums.firstOrNull()?.imageUrl
        ?: tracks.firstOrNull()?.imageUrl

    if (imageUrl != null) {
        val artModifier = if (isLandscape) {
            Modifier.widthIn(max = 120.dp).aspectRatio(1f)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).aspectRatio(16f / 9f)
        }
        AsyncImage(
            model = imageUrl,
            contentDescription = "Artist image",
            modifier = artModifier.clip(MaterialTheme.shapes.medium),
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

    if (artistBlocked) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Artist blocked",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
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

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.ArtistContentItems(
    similarArtists: List<Artist>,
    albums: List<Album>,
    tracks: List<Track>,
    artist: Artist?,
    artistName: String,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    viewModel: ArtistDetailViewModel,
    onAction: (ActionSheetItem) -> Unit
) {
    if (similarArtists.isNotEmpty()) {
        item {
            Text(
                "Similar Artists",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(similarArtists, key = { it.uri }) { similarArtist ->
                    Column(
                        modifier = Modifier
                            .width(90.dp)
                            .clickable { onArtistClick(similarArtist) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = similarArtist.imageUrl,
                            contentDescription = similarArtist.name,
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0x1F888888), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = similarArtist.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
                formatAlbumTypeYear(album.albumType, album.year).ifBlank { null },
                album.version?.ifEmpty { null }
            ).joinToString(" \u00b7 ")
            MediaItemRow(
                title = album.name,
                subtitle = subtitle,
                imageUrl = album.imageUrl,
                onClick = { onAlbumClick(album) },
                favorite = album.favorite,
                onLongClick = {
                    onAction(
                        ActionSheetItem(
                            title = album.name,
                            subtitle = "",
                            uri = album.uri,
                            imageUrl = album.imageUrl,
                            favorite = album.favorite,
                            mediaType = MediaType.ALBUM,
                            itemId = album.itemId,
                            primaryArtistUri = album.artists.firstOrNull()?.uri ?: artist?.uri,
                            primaryArtistName = album.artists.firstOrNull()?.name ?: artistName
                        )
                    )
                },
                onPlayClick = { viewModel.quickPlay(album.uri) }
            )
        }
    }

    if (tracks.isNotEmpty()) {
        item {
            ArtistTracksHeader(viewModel = viewModel)
        }
        items(tracks, key = { it.uri }) { track ->
            MediaItemRow(
                title = track.name,
                subtitle = track.albumName,
                imageUrl = track.imageUrl,
                onClick = { viewModel.playTrack(track) },
                favorite = track.favorite,
                onMoreClick = {
                    onAction(
                        ActionSheetItem(
                            title = track.name,
                            subtitle = track.artistNames,
                            uri = track.uri,
                            imageUrl = track.imageUrl,
                            favorite = track.favorite,
                            mediaType = MediaType.TRACK,
                            itemId = track.itemId,
                            primaryArtistUri = track.artistUri ?: artist?.uri,
                            primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim().orEmpty().ifBlank { artistName }
                        )
                    )
                },
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
                            primaryArtistUri = track.artistUri ?: artist?.uri,
                            primaryArtistName = track.artistNames.split(",").firstOrNull()?.trim().orEmpty().ifBlank { artistName }
                        )
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistTracksHeader(viewModel: ArtistDetailViewModel) {
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
        ModalBottomSheet(
            onDismissRequest = { showPlaySheet = false },
            containerColor = SheetDefaults.containerColor()
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Add to Queue") },
                    modifier = Modifier.clickable {
                        viewModel.playAllTracks(option = "add")
                        showPlaySheet = false
                    }
                )
                ListItem(
                    colors = SheetDefaults.listItemColors(),
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
