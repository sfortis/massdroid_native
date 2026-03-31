package net.asksakis.massdroidv2.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlin.math.absoluteValue
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
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

private val smartMixPhrases = listOf(
    "Analyzing your taste...",
    "Picking your favorites...",
    "Building the perfect mix...",
    "Studying your history...",
    "Balancing the genres...",
    "Curating tracks for you...",
    "Reading your mood...",
    "Assembling the playlist...",
    "Scoring every track...",
    "Finding the sweet spot..."
)

private val HomeTitleFont = FontFamily(
    Font(R.font.goldman_regular, FontWeight.Normal),
    Font(R.font.goldman_bold, FontWeight.Bold)
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            SmartMixFab(
                isBusy = isBuildingSmartMix,
                onClick = { viewModel.makePlaylistForMe() },
                modifier = Modifier.padding(bottom = LocalMiniPlayerPadding.current)
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo_md_monochrome),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "MassDroid",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = HomeTitleFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                lineHeight = 28.sp
                            )
                        )
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
            val isDisconnected = connectionState is ConnectionState.Disconnected ||
                connectionState is ConnectionState.Error
            if (isLoading && sections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (sections.isEmpty() && isDisconnected) {
                EmptyStateView(onNavigateToSettings)
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullToRefreshState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().fadingEdges(),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = LocalMiniPlayerPadding.current + 96.dp)
                    ) {
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
                    enter = fadeIn(animationSpec = tween(durationMillis = 140)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 140)),
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

            // Smart mix overlay
            if (isBuildingSmartMix) {
                SmartMixOverlay()
            }
        }
    }

    LaunchedEffect(smartMixMessage) {
        if (smartMixMessage != null) {
            delay(2500)
            viewModel.clearSmartMixMessage()
        }
    }

    LaunchedEffect(showConnectionDialog) {
        if (showConnectionDialog) {
            viewModel.startContinuousConnectionProbe()
        } else {
            viewModel.stopContinuousConnectionProbe()
        }
    }

    if (showConnectionDialog) {
        ConnectionStatusDialog(
            connectionState = connectionState,
            probeState = connectionProbe,
            onDismiss = { showConnectionDialog = false }
        )
    }
}

@Composable
private fun EmptyStateView(onNavigateToSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Not connected to Music Assistant",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToSettings) {
                Text("Configure Server")
            }
        }
    }
}

@Composable
private fun SmartMixFab(
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sparkleScale = remember { Animatable(1f) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(
        androidx.lifecycle.Lifecycle.State.RESUMED
    )

    LaunchedEffect(isBusy, isResumed) {
        if (!isBusy && isResumed) {
            val framesPerPulse = 24          // 24 * 50ms = 1.2s per pulse
            val pi = Math.PI.toFloat()
            while (true) {
                repeat(2) {                  // 2 magnify pulses
                    repeat(framesPerPulse) { i ->
                        delay(50)            // ~20fps, Choreographer-independent
                        val t = kotlin.math.sin(pi * i / framesPerPulse)
                        sparkleScale.snapTo(1f + t * 0.15f)  // 1.0 → 1.15 → 1.0
                    }
                }
                sparkleScale.snapTo(1f)
                delay(3000)
            }
        } else {
            sparkleScale.snapTo(1f)
        }
    }

    ExtendedFloatingActionButton(
        onClick = { if (!isBusy) onClick() },
        modifier = modifier,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp
        ),
        icon = {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFD8D8D8)
                )
            } else {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer {
                        scaleX = sparkleScale.value
                        scaleY = sparkleScale.value
                    }
                )
            }
        },
        text = { Text("Smart Mix") }
    )
}

@Composable
private fun ConnectionStatusDialog(
    connectionState: ConnectionState,
    probeState: ConnectionProbeState,
    onDismiss: () -> Unit
) {
    val statusText = when (connectionState) {
        is ConnectionState.Connected -> "Connected (v${connectionState.serverInfo.serverVersion})"
        is ConnectionState.Connecting -> "Connecting..."
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Error: ${connectionState.message}"
    }
    val historySamples = probeState.historyMs.filter { it > 0L }
    val avgMs = if (historySamples.isNotEmpty()) historySamples.average().toInt() else null
    val hasGraphData = historySamples.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText, style = MaterialTheme.typography.bodyMedium)

                if (hasGraphData) {
                    ConnectionLatencyGraph(samples = probeState.historyMs)
                } else if (probeState.error != null) {
                    Text(
                        text = probeState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text("No RTT data yet", style = MaterialTheme.typography.bodyMedium)
                }

                if (avgMs != null) {
                    Text(
                        text = "Roundtrip: avg ${avgMs}ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (probeState.failedSamples > 0) {
                    Text(
                        text = "Failed samples: ${probeState.failedSamples}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ConnectionLatencyGraph(samples: List<Long>) {
    val filtered = samples.takeLast(24)
    if (filtered.size < 2) return

    val validSamples = filtered.filter { it > 0L }
    val maxSample = (validSamples.maxOrNull() ?: 1L).toFloat().coerceAtLeast(1f)
    val minSample = (validSamples.minOrNull() ?: 0L)
    val currentSample = validSamples.lastOrNull()
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val errorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 34.dp, end = 40.dp)
        ) {
            val stepX = size.width / (filtered.lastIndex.coerceAtLeast(1))
            val topInset = 6.dp.toPx()
            val bottomInset = 6.dp.toPx()
            val graphHeight = (size.height - topInset - bottomInset).coerceAtLeast(1f)

            val smoothedSamples = filtered.mapIndexed { index, sample ->
                if (sample <= 0L) {
                    0f
                } else {
                    val prev = filtered.getOrNull(index - 1)?.takeIf { it > 0L }?.toFloat() ?: sample.toFloat()
                    val next = filtered.getOrNull(index + 1)?.takeIf { it > 0L }?.toFloat() ?: sample.toFloat()
                    ((prev * 0.2f) + (sample.toFloat() * 0.6f) + (next * 0.2f))
                }
            }

            val points = smoothedSamples.mapIndexed { index, sample ->
                val x = stepX * index
                val normalized = if (sample <= 0f) 0f else (sample / maxSample).coerceIn(0f, 1f)
                val y = size.height - bottomInset - (normalized * graphHeight)
                Offset(x, y)
            }

            val linePath = Path()
            val fillPath = Path()
            linePath.moveTo(points.first().x, points.first().y)
            fillPath.moveTo(points.first().x, size.height - bottomInset)
            fillPath.lineTo(points.first().x, points.first().y)

            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val midX = (previous.x + current.x) / 2f
                val midY = (previous.y + current.y) / 2f
                linePath.quadraticTo(previous.x, previous.y, midX, midY)
                fillPath.quadraticTo(previous.x, previous.y, midX, midY)
            }

            linePath.lineTo(points.last().x, points.last().y)
            fillPath.lineTo(points.last().x, points.last().y)

            fillPath.lineTo(points.last().x, size.height - bottomInset)
            fillPath.close()

            drawPath(path = fillPath, color = fillColor)
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3.dp.toPx())
            )

            filtered.forEachIndexed { index, sample ->
                if (sample <= 0L) {
                    val x = stepX * index
                    drawLine(
                        color = errorColor,
                        start = Offset(x, topInset),
                        end = Offset(x, size.height - bottomInset),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }

        Text(
            text = "${maxSample.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = "${minSample}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        currentSample?.let {
            Text(
                text = "${it}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
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
private fun SmartMixOverlay() {
    var phrase by remember { mutableStateOf(smartMixPhrases.random()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            phrase = smartMixPhrases.random()
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
                text = "Creating Smart Mix",
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
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
    val (bgA, bgB, glow) = remember(genre.name) { genrePalette(genre.name) }
    ElevatedCard(
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(2f)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(bgA, bgB)
                    )
                )
                .semantics { contentDescription = genre.name }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawCircle(
                            color = glow,
                            radius = size.minDimension * 0.42f,
                            center = Offset(size.width * 0.18f, size.height * 0.24f)
                        )
                        drawCircle(
                            color = glow.copy(alpha = glow.alpha * 0.55f),
                            radius = size.minDimension * 0.34f,
                            center = Offset(size.width * 0.82f, size.height * 0.78f)
                        )
                    }
            )
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
                style = MaterialTheme.typography.titleSmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.35f),
                        blurRadius = 12f
                    )
                ),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

private fun genrePalette(name: String): Triple<Color, Color, Color> {
    val palettes = listOf(
        Triple(Color(0xFF233247), Color(0xFF101722), Color(0x553DB7FF)),
        Triple(Color(0xFF35273E), Color(0xFF16121B), Color(0x554DA3FF)),
        Triple(Color(0xFF21383A), Color(0xFF0F1718), Color(0x5540D6C3)),
        Triple(Color(0xFF40311F), Color(0xFF17120D), Color(0x55FF9F43)),
        Triple(Color(0xFF2B2B46), Color(0xFF11111C), Color(0x556C8CFF)),
        Triple(Color(0xFF3A2531), Color(0xFF160E12), Color(0x55FF6FAE))
    )
    return palettes[(normalizeGenre(name).hashCode().absoluteValue) % palettes.size]
}
