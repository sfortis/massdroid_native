package net.asksakis.massdroidv2.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear
import net.asksakis.massdroidv2.ui.components.EqualizerBars

private val radioStartPhrases = listOf(
    "Tuning the airwaves...",
    "Mixing the perfect blend...",
    "Finding hidden gems...",
    "Crafting your vibe...",
    "Summoning the beat...",
    "Spinning up the groove...",
    "Consulting the music gods...",
    "Connecting sonic dots...",
    "Warming up the speakers...",
    "Shuffling the universe...",
    "Dialing in the frequency...",
    "Unleashing the rhythm..."
)

// Fixed heights for LazyColumn item prefetch (avoids layout thrashing)
private val ArtistRowHeight = 114.dp  // 90 image + 4 spacer + 20 text
private val AlbumRowHeight = 148.dp   // 110 image + 4 spacer + 18 name + 16 artist
private val PlaylistRowHeight = 148.dp
private val TrackRowHeight = 48.dp
private val GenreRowHeight = 70.dp    // 140 * 0.5 aspect ratio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val radioOverlayGenre by viewModel.radioOverlayGenre.collectAsStateWithLifecycle()
    val isBuildingSmartMix by viewModel.isBuildingSmartMix.collectAsStateWithLifecycle()
    val smartMixMessage by viewModel.smartMixMessage.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectionProbe by viewModel.connectionProbe.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()
    var showConnectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(60.dp)
                                .aspectRatio(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MassDroid")
                    }
                },
                actions = {
                    IconButton(onClick = { showConnectionDialog = true }) {
                        val (icon, tint) = when (connectionState) {
                            is ConnectionState.Connected -> Icons.Default.Cloud to MaterialTheme.colorScheme.primary
                            is ConnectionState.Connecting -> Icons.Default.CloudSync to MaterialTheme.colorScheme.tertiary
                            is ConnectionState.Disconnected -> Icons.Default.CloudOff to MaterialTheme.colorScheme.error
                            is ConnectionState.Error -> Icons.Default.CloudOff to MaterialTheme.colorScheme.error
                        }
                        Icon(icon, contentDescription = "Connection status", tint = tint)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading && sections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullToRefreshState,
                    indicator = {},
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        item(key = "smart_mix_action") {
                            SmartMixActionCard(
                                isBusy = isBuildingSmartMix,
                                message = smartMixMessage,
                                onClick = { viewModel.makePlaylistForMe() }
                            )
                        }
                        itemsIndexed(
                            items = sections,
                            key = { _, section -> sectionKey(section) },
                            contentType = { _, section -> section::class.simpleName ?: "section" }
                        ) { _, section ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (section) {
                                    is DiscoverSection.ArtistSection -> {
                                        SectionHeader(section.title)
                                        ArtistRow(
                                            artists = section.artists,
                                            onArtistClick = onArtistClick,
                                            modifier = Modifier.height(ArtistRowHeight)
                                        )
                                    }
                                    is DiscoverSection.AlbumSection -> {
                                        SectionHeader(section.title)
                                        AlbumRow(
                                            albums = section.albums,
                                            onAlbumClick = onAlbumClick,
                                            modifier = Modifier.height(AlbumRowHeight)
                                        )
                                    }
                                    is DiscoverSection.PlaylistSection -> {
                                        SectionHeader(section.title)
                                        PlaylistRow(
                                            playlists = section.playlists,
                                            onPlaylistClick = onPlaylistClick,
                                            modifier = Modifier.height(PlaylistRowHeight)
                                        )
                                    }
                                    is DiscoverSection.TrackSection -> {
                                        SectionHeader(section.title)
                                        TrackRow(
                                            tracks = section.tracks,
                                            onTrackClick = { track -> viewModel.playTrack(track) },
                                            modifier = Modifier.height(TrackRowHeight)
                                        )
                                    }
                                    is DiscoverSection.GenreRadioSection -> {
                                        SectionHeader(section.title)
                                        GenreRow(
                                            genres = section.genres,
                                            onGenreClick = { genre ->
                                                viewModel.startGenreRadio(genre.name)
                                            },
                                            modifier = Modifier.height(GenreRowHeight)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isRefreshing,
                    enter = fadeIn(animationSpec = tween(durationMillis = 250)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 250)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.50f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EqualizerBars(
                                modifier = Modifier.height(56.dp),
                                barWidth = 6.dp,
                                spacing = 4.dp,
                                barCount = 5,
                                bpm = 120,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Refreshing...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Radio start overlay
            if (radioOverlayGenre != null) {
                RadioStartOverlay(genre = radioOverlayGenre ?: "")
            }
        }
    }

    LaunchedEffect(smartMixMessage) {
        if (smartMixMessage != null) {
            delay(2800)
            viewModel.clearSmartMixMessage()
        }
    }

    LaunchedEffect(showConnectionDialog) {
        if (showConnectionDialog) {
            viewModel.probeConnection()
        }
    }

    if (showConnectionDialog) {
        ConnectionStatusDialog(
            connectionState = connectionState,
            probeState = connectionProbe,
            onRefresh = { viewModel.probeConnection() },
            onDismiss = { showConnectionDialog = false }
        )
    }
}

@Composable
private fun SmartMixActionCard(
    isBusy: Boolean,
    message: String?,
    onClick: () -> Unit
) {
    val glowTransition = rememberInfiniteTransition(label = "smart_mix_glow")
    val glowAlpha by glowTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.46f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "smart_mix_glow_alpha"
    )
    val secondaryGlowAlpha by glowTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "smart_mix_secondary_glow_alpha"
    )
    val glowShape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(glowShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = secondaryGlowAlpha),
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha)
                        )
                    )
                )
                .padding(2.5.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(glowShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.35f))
                    .padding(1.5.dp)
            ) {
            ElevatedCard(
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isBusy, onClick = onClick),
                shape = glowShape
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = if (isBusy) "Building Smart Mix..." else "Smart Mix",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            }
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectionStatusDialog(
    connectionState: ConnectionState,
    probeState: ConnectionProbeState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val statusText = when (connectionState) {
        is ConnectionState.Connected -> "Connected (v${connectionState.serverInfo.serverVersion})"
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Error: ${connectionState.message}"
    }
    val samples = probeState.samplesMs
    val avgMs = if (samples.isNotEmpty()) samples.average().toInt() else null
    val minMs = samples.minOrNull()?.toInt()
    val maxMs = samples.maxOrNull()?.toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText, style = MaterialTheme.typography.bodyMedium)

                when {
                    probeState.inProgress -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Measuring roundtrip...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    avgMs != null -> {
                        Text(
                            text = "Method: ${probeState.probeMethod ?: "Unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Roundtrip: avg ${avgMs}ms (min ${minMs}ms, max ${maxMs}ms)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (probeState.failedSamples > 0) {
                            Text(
                                text = "Failed samples: ${probeState.failedSamples}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    probeState.error != null -> {
                        Text(
                            text = probeState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        Text("No RTT data yet", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !probeState.inProgress) {
                Text("Measure Again")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun RadioStartOverlay(genre: String) {
    var phrase by remember { mutableStateOf(radioStartPhrases.random()) }

    LaunchedEffect(genre) {
        while (true) {
            delay(800)
            phrase = radioStartPhrases.random()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EqualizerBars(
                modifier = Modifier.height(48.dp),
                barWidth = 6.dp,
                spacing = 4.dp,
                barCount = 5,
                bpm = 120,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Tuning into $genre Radio",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = phrase,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

private fun sectionTitle(section: DiscoverSection): String {
    return when (section) {
        is DiscoverSection.ArtistSection -> section.title
        is DiscoverSection.AlbumSection -> section.title
        is DiscoverSection.PlaylistSection -> section.title
        is DiscoverSection.TrackSection -> section.title
        is DiscoverSection.GenreRadioSection -> section.title
    }
}

private fun sectionKey(section: DiscoverSection): String {
    return "${section::class.simpleName}:${sectionTitle(section)}"
}

@Composable
private fun rememberSizedImageModel(
    url: String?,
    widthPx: Int,
    heightPx: Int = widthPx
): Any? {
    if (url == null) return null
    val context = LocalContext.current
    return remember(url, widthPx, heightPx, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .crossfade(false)
            .build()
    }
}

@Composable
private fun ArtistRow(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(artists, key = { it.uri }) { artist ->
            ArtistCard(artist = artist, onClick = { onArtistClick(artist) })
        }
    }
}

@Composable
private fun ArtistCard(
    artist: Artist,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(90.dp)
            .semantics { contentDescription = artist.name }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = rememberSizedImageModel(artist.imageUrl, widthPx = 236),
            contentDescription = null,
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Color(0x1F888888), CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AlbumRow(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.uri }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(110.dp)
            .semantics {
                contentDescription = if (album.artistNames.isNotBlank()) {
                    "${album.name}, ${album.artistNames}"
                } else {
                    album.name
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        AsyncImage(
            model = rememberSizedImageModel(album.imageUrl, widthPx = 289),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color(0x1F888888), MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatAlbumTypeYear(album.albumType, album.year).ifBlank {
                album.artistNames.ifBlank { "\u00A0" }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistRow(
    playlists: List<Playlist>,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(playlists, key = { it.uri }) { playlist ->
            PlaylistCard(playlist = playlist, onClick = { onPlaylistClick(playlist) })
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(110.dp)
            .semantics { contentDescription = playlist.name }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        AsyncImage(
            model = rememberSizedImageModel(playlist.imageUrl, widthPx = 289),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color(0x1F888888), MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrackRow(
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tracks, key = { it.uri }) { track ->
            TrackCard(track = track, onClick = { onTrackClick(track) })
        }
    }
}

@Composable
private fun TrackCard(
    track: Track,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .width(200.dp)
            .semantics {
                contentDescription = if (track.artistNames.isNotBlank()) {
                    "${track.name}, ${track.artistNames}"
                } else {
                    track.name
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = rememberSizedImageModel(track.imageUrl, widthPx = 126),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color(0x1F888888), MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artistNames.ifBlank { "\u00A0" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GenreRow(
    genres: List<GenreItem>,
    onGenreClick: (GenreItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(genres, key = { it.name }) { genre ->
            GenreChip(genre = genre, onClick = { onGenreClick(genre) })
        }
    }
}

@Composable
private fun GenreChip(
    genre: GenreItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    ElevatedCard(
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(2f)
                .semantics { contentDescription = genre.name }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            genre.imageUrl?.let { url ->
                AsyncImage(
                    model = rememberSizedImageModel(
                        url = url,
                        widthPx = 368,
                        heightPx = 184
                    ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
                )
            }
            Text(
                text = genre.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
