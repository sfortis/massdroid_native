package net.asksakis.massdroidv2.ui.screens.nowplaying

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.isSystemInDarkTheme
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.VolumeSlider
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToArtist: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    onNavigateToAlbum: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    val player by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val elapsedTime by viewModel.elapsedTime.collectAsStateWithLifecycle()
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoadingPlaylists by viewModel.isLoadingPlaylists.collectAsStateWithLifecycle()
    val addingToPlaylistId by viewModel.addingToPlaylistId.collectAsStateWithLifecycle()

    val currentTrack = queueState?.currentItem?.track
    val currentArtistUri = MediaIdentity.canonicalArtistKey(
        itemId = currentTrack?.artistItemId,
        uri = currentTrack?.artistUri
    )
    val artistBlocked = currentArtistUri?.let { it in blockedArtistUris } ?: false
    val canToggleArtistBlock = currentArtistUri != null
    var showPlayerMenu by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsStateWithLifecycle()
    val title = currentTrack?.name ?: player?.currentMedia?.title ?: "No track"
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist ?: ""
    val album = currentTrack?.albumName ?: player?.currentMedia?.album ?: ""
    val imageUrl = currentTrack?.imageUrl ?: queueState?.currentItem?.imageUrl
        ?: player?.currentMedia?.imageUrl
    val duration = currentTrack?.duration ?: queueState?.currentItem?.duration
        ?: player?.currentMedia?.duration ?: 0.0
    val audioFormat = queueState?.currentItem?.audioFormat
    val isPlaying = player?.state == PlaybackState.PLAYING
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showPlayerSettingsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.error.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val isDark = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val dominantColor by extractDominantColor(imageUrl, isDark)
    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 320),
        label = "bg_color"
    )
    val gradientAlpha = if (isDark) 0.35f else 0.25f
    val gradient = Brush.verticalGradient(
        colors = listOf(animatedColor.copy(alpha = gradientAlpha), surfaceColor)
    )

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { Text(player?.displayName ?: "Now Playing") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showPlayerMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Player options")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .background(gradient)
        ) {
            if (isLandscape) {
                NowPlayingLandscape(
                    paddingValues = paddingValues,
                    imageUrl = imageUrl,
                    title = title,
                    artist = artist,
                    album = album,
                    audioFormat = audioFormat,
                    isPlaying = isPlaying,
                    currentTrack = currentTrack,
                    queueState = queueState,
                    elapsedTime = elapsedTime,
                    duration = duration,
                    player = player,
                    viewModel = viewModel,
                    onBack = onBack,
                    onNavigateToQueue = onNavigateToQueue,
                    onShowPlaylistDialog = {
                        showPlaylistDialog = true
                        viewModel.loadPlaylists(force = true)
                    },
                    onShowLyrics = {
                        showLyricsSheet = true
                        viewModel.loadLyrics()
                    },
                    onShowPlayerMenu = { showPlayerMenu = true },
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum
                )
            } else {
                NowPlayingPortrait(
                    paddingValues = paddingValues,
                    imageUrl = imageUrl,
                    title = title,
                    artist = artist,
                    album = album,
                    audioFormat = audioFormat,
                    isPlaying = isPlaying,
                    currentTrack = currentTrack,
                    queueState = queueState,
                    elapsedTime = elapsedTime,
                    duration = duration,
                    player = player,
                    viewModel = viewModel,
                    onShowPlaylistDialog = {
                        showPlaylistDialog = true
                        viewModel.loadPlaylists(force = true)
                    },
                    onShowLyrics = {
                        showLyricsSheet = true
                        viewModel.loadLyrics()
                    },
                    onNavigateToQueue = onNavigateToQueue,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum
                )
            }
        }
    }

    if (showPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            isLoading = isLoadingPlaylists,
            addingToPlaylistId = addingToPlaylistId,
            onDismiss = { showPlaylistDialog = false },
            onRetry = { viewModel.loadPlaylists(force = true) },
            onPlaylistClick = { playlist ->
                viewModel.addCurrentTrackToPlaylist(playlist) {
                    showPlaylistDialog = false
                }
            }
        )
    }

    if (showPlayerMenu) {
        PlayerOptionsSheet(
            artistBlocked = artistBlocked,
            canToggleArtistBlock = canToggleArtistBlock,
            onDismiss = { showPlayerMenu = false },
            onPlayerSettings = {
                showPlayerMenu = false
                showPlayerSettingsDialog = true
            },
            onClick = {
                showPlayerMenu = false
                viewModel.toggleCurrentArtistBlocked()
            }
        )
    }

    player?.let { currentPlayer ->
        if (showPlayerSettingsDialog) {
            PlayerSettingsDialog(
                player = currentPlayer,
                viewModel = viewModel,
                onDismiss = { showPlayerSettingsDialog = false }
            )
        }
    }

    if (showLyricsSheet) {
        LyricsSheet(
            lyrics = lyrics,
            isLoading = isLoadingLyrics,
            elapsedTime = elapsedTime,
            onDismiss = { showLyricsSheet = false }
        )
    }
}

@Composable
private fun NowPlayingPortrait(
    paddingValues: PaddingValues,
    imageUrl: String?,
    title: String,
    artist: String,
    album: String,
    audioFormat: AudioFormatInfo?,
    isPlaying: Boolean,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    elapsedTime: Double,
    duration: Double,
    player: net.asksakis.massdroidv2.domain.model.Player?,
    viewModel: NowPlayingViewModel,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        SwipeableAlbumArt(
            imageUrl = imageUrl,
            onNext = { viewModel.next() },
            onPrevious = { viewModel.previous() },
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )

        Spacer(modifier = Modifier.height(32.dp))

        QualityActionRow(
            audioFormat = audioFormat,
            currentTrack = currentTrack,
            viewModel = viewModel,
            onShowPlaylistDialog = onShowPlaylistDialog,
            onShowLyrics = onShowLyrics,
            onNavigateToQueue = onNavigateToQueue
        )

        Spacer(modifier = Modifier.height(12.dp))

        TrackInfoSection(
            title = title,
            artist = artist,
            album = album,
            currentTrack = currentTrack,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum
        )

        Spacer(modifier = Modifier.height(16.dp))

        SeekBar(
            elapsed = elapsedTime,
            duration = duration,
            onSeek = { viewModel.seek(it) },
            compact = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        TransportControls(
            isPlaying = isPlaying,
            queueState = queueState,
            viewModel = viewModel,
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        VolumeSlider(
            volume = player?.volumeLevel ?: 0,
            isMuted = player?.volumeMuted ?: false,
            onVolumeChange = { viewModel.setVolume(it) },
            onMuteToggle = { viewModel.toggleMute() },
            compact = true
        )
    }
}

@Composable
private fun NowPlayingLandscape(
    paddingValues: PaddingValues,
    imageUrl: String?,
    title: String,
    artist: String,
    album: String,
    audioFormat: AudioFormatInfo?,
    isPlaying: Boolean,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    elapsedTime: Double,
    duration: Double,
    player: net.asksakis.massdroidv2.domain.model.Player?,
    viewModel: NowPlayingViewModel,
    onBack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: album art (fills available height)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            SwipeableAlbumArt(
                imageUrl = imageUrl,
                onNext = { viewModel.next() },
                onPrevious = { viewModel.previous() },
                onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                fillMaxWidth = false
            )
        }

        // Right: controls
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(start = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Back + queue row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
                Text(
                    text = player?.displayName ?: "Now Playing",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .align(Alignment.CenterVertically),
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = onShowPlayerMenu, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Player options", modifier = Modifier.size(20.dp))
                }
            }

            QualityActionRow(
                audioFormat = audioFormat,
                currentTrack = currentTrack,
                viewModel = viewModel,
                onShowPlaylistDialog = onShowPlaylistDialog,
                onShowLyrics = onShowLyrics,
                onNavigateToQueue = onNavigateToQueue,
                compact = true
            )

            TrackInfoSection(
                title = title,
                artist = artist,
                album = album,
                currentTrack = currentTrack,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                compact = true
            )

            SeekBar(
                elapsed = elapsedTime,
                duration = duration,
                onSeek = { viewModel.seek(it) },
                compact = true
            )

            TransportControls(
                isPlaying = isPlaying,
                queueState = queueState,
                viewModel = viewModel,
                compact = true,
                onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            )

            VolumeSlider(
                volume = player?.volumeLevel ?: 0,
                isMuted = player?.volumeMuted ?: false,
                onVolumeChange = { viewModel.setVolume(it) },
                onMuteToggle = { viewModel.toggleMute() },
                compact = true
            )
        }
    }
}

@Composable
private fun AudioQualityBadges(
    audioFormat: AudioFormatInfo?,
    compact: Boolean = false
) {
    val badges = remember(audioFormat) { buildAudioQualityBadges(audioFormat) }
    if (badges.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { badge ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                tonalElevation = 0.dp
            ) {
                Text(
                    text = badge,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 6.dp)
                )
            }
        }
    }
}

@Composable
private fun QualityActionRow(
    audioFormat: AudioFormatInfo?,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    viewModel: NowPlayingViewModel,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onNavigateToQueue: () -> Unit,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val actionButtonSize = if (compact) 36.dp else 44.dp
    val actionIconSize = if (compact) 18.dp else 24.dp

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(if (compact) 0.88f else 0.92f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onShowPlaylistDialog()
                    },
                    modifier = Modifier.size(actionButtonSize)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add to playlist",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onShowLyrics()
                    },
                    modifier = Modifier.size(actionButtonSize)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Subject,
                        contentDescription = "Lyrics",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AudioQualityBadges(audioFormat = audioFormat, compact = compact)
            Row(verticalAlignment = Alignment.CenterVertically) {
                @Suppress("DEPRECATION")
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToQueue()
                    },
                    modifier = Modifier.size(actionButtonSize)
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleFavorite()
                    },
                    modifier = Modifier.size(actionButtonSize)
                ) {
                    Icon(
                        if (currentTrack?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Toggle favorite",
                        modifier = Modifier.size(actionIconSize),
                        tint = if (currentTrack?.favorite == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSheet(
    lyrics: LyricsProvider.LyricsResult?,
    isLoading: Boolean,
    elapsedTime: Double,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            SheetDefaults.HeaderTitle(
                text = "Lyrics",
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                lyrics == null || (lyrics.plainText == null && lyrics.syncedLrc == null) -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                lyrics.syncedLrc != null -> {
                    SyncedLyricsContent(
                        lrc = lyrics.syncedLrc,
                        elapsedTimeMs = (elapsedTime * 1000).toLong()
                    )
                }
                else -> {
                    PlainLyricsContent(text = lyrics.plainText!!)
                }
            }
        }
    }
}

@Composable
private fun PlainLyricsContent(text: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4
            )
        }
    }
}

@Composable
private fun SyncedLyricsContent(lrc: String, elapsedTimeMs: Long) {
    val lines = remember(lrc) { LyricsProvider.parseLrc(lrc) }
    val listState = rememberLazyListState()

    val currentIndex = remember(elapsedTimeMs, lines) {
        val idx = lines.indexOfLast { it.timeMs <= elapsedTimeMs }
        if (idx < 0) 0 else idx
    }

    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty() && currentIndex >= 0) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -200
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 200.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == currentIndex
            Text(
                text = line.text.ifBlank { " " },
                style = if (isCurrent) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }
    }
}

private fun buildAudioQualityBadges(audioFormat: AudioFormatInfo?): List<String> {
    if (audioFormat == null) return emptyList()

    val codec = audioFormat.contentType
        ?.replace('_', ' ')
        ?.uppercase()
        ?.takeIf { it.isNotBlank() && it != "?" }

    val qualityLabel = when {
        (audioFormat.bitDepth ?: 0) >= 24 -> "HQ"
        (audioFormat.contentType ?: "").equals("flac", ignoreCase = true) -> "HQ"
        (audioFormat.bitRate ?: 0) >= 900_000 -> "HQ"
        else -> "LQ"
    }

    val primary = listOfNotNull(qualityLabel, codec).joinToString(" • ").ifBlank { null }
    return listOfNotNull(primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    artistBlocked: Boolean,
    canToggleArtistBlock: Boolean,
    onDismiss: () -> Unit,
    onPlayerSettings: () -> Unit,
    onClick: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column {
                SheetDefaults.HeaderTitle(
                    text = "Player Options",
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            }
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = { Text("Player Settings") },
                supportingContent = {
                    Text(
                        "Rename player, crossfade and volume normalization",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                modifier = Modifier.clickable(onClick = onPlayerSettings)
            )
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = {
                    Text(
                        if (artistBlocked) "Allow Artist Again" else "Block This Artist",
                        color = if (artistBlocked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                },
                supportingContent = {
                    Text(
                        if (artistBlocked) "Artist can appear again in queue and recommendations"
                        else "Hide this artist from queue and smart listening",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = if (artistBlocked) Icons.Default.PersonAdd else Icons.Default.Block,
                        contentDescription = null,
                        tint = if (artistBlocked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                },
                modifier = Modifier.clickable(enabled = canToggleArtistBlock, onClick = onClick)
            )
        }
    }
}

@Composable
private fun TrackInfoSection(
    title: String,
    artist: String,
    album: String,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit,
    compact: Boolean = false
) {
    val titleStyle = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall
    val artistStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
    val albumStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall
    val artistClickable = currentTrack?.artistItemId != null && currentTrack.artistProvider != null
    val albumClickable = currentTrack?.albumItemId != null && currentTrack.albumProvider != null

    Text(
        text = title,
        style = titleStyle,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp)
    )
    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
    Text(
        text = artist,
        style = artistStyle,
        color = if (artistClickable) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = (if (artistClickable) {
            Modifier.clickable {
                onNavigateToArtist(currentTrack.artistItemId!!, currentTrack.artistProvider!!, artist)
            }
        } else Modifier).fillMaxWidth()
    )
    if (album.isNotBlank()) {
        Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            text = album,
            style = albumStyle,
            color = if (albumClickable) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (albumClickable) Modifier.clickable {
                onNavigateToAlbum(currentTrack.albumItemId!!, currentTrack.albumProvider!!, album)
            } else Modifier
        )
    }
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    isLoading: Boolean,
    addingToPlaylistId: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                playlists.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No playlists available.")
                        TextButton(onClick = onRetry) {
                            Text("Reload")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.uri }) { playlist ->
                            val isAdding = addingToPlaylistId == playlist.itemId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable(enabled = !isAdding) { onPlaylistClick(playlist) },
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = playlist.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = playlist.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isAdding) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
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

@Composable
private fun PlayerSettingsDialog(
    player: net.asksakis.massdroidv2.domain.model.Player,
    viewModel: NowPlayingViewModel,
    onDismiss: () -> Unit
) {
    var config by remember { mutableStateOf<PlayerConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(player.displayName) }
    var crossfadeMode by remember { mutableStateOf(CrossfadeMode.DISABLED) }
    var volumeNormalization by remember { mutableStateOf(false) }

    LaunchedEffect(player.playerId) {
        val loaded = viewModel.getPlayerConfig(player.playerId)
        if (loaded != null) {
            config = loaded
            name = loaded.name.ifBlank { player.displayName }
            crossfadeMode = loaded.crossfadeMode
            volumeNormalization = loaded.volumeNormalization
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Player Settings") },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Player name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Crossfade", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        CrossfadeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = crossfadeMode == mode,
                                onClick = { crossfadeMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = CrossfadeMode.entries.size
                                ),
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Volume normalization")
                        Switch(
                            checked = volumeNormalization,
                            onCheckedChange = { volumeNormalization = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val values = mutableMapOf<String, Any>(
                        "smart_fades_mode" to crossfadeMode.apiValue,
                        "volume_normalization" to volumeNormalization
                    )
                    if (name.isNotBlank() && name.trim() != player.displayName) {
                        values["name"] = name.trim()
                    }
                    viewModel.savePlayerConfig(player.playerId, values)
                    onDismiss()
                },
                enabled = !isLoading
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    viewModel: NowPlayingViewModel,
    compact: Boolean = false,
    onHaptic: () -> Unit = {}
) {
    val buttonSize = if (compact) 40.dp else 48.dp
    val playSize = if (compact) 52.dp else 64.dp
    val iconSize = if (compact) 26.dp else 32.dp
    val playIconSize = if (compact) 30.dp else 36.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            onHaptic()
            viewModel.toggleShuffle()
        }) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (queueState?.shuffleEnabled == true)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = {
            onHaptic()
            viewModel.previous()
        }, modifier = Modifier.size(buttonSize)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(iconSize))
        }

        FilledIconButton(onClick = {
            onHaptic()
            viewModel.playPause()
        }, modifier = Modifier.size(playSize)) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(playIconSize)
            )
        }

        IconButton(onClick = {
            onHaptic()
            viewModel.next()
        }, modifier = Modifier.size(buttonSize)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(iconSize))
        }

        IconButton(onClick = {
            onHaptic()
            viewModel.cycleRepeat()
        }) {
            Icon(
                when (queueState?.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = if (queueState?.repeatMode != RepeatMode.OFF)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwipeableAlbumArt(
    imageUrl: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onHaptic: () -> Unit = {},
    fillMaxWidth: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableIntStateOf(1) }

    val shape = MaterialTheme.shapes.medium

    val sizeModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth(0.75f).aspectRatio(1f)
    } else {
        Modifier.heightIn(max = 240.dp).aspectRatio(1f)
    }

    Box(
        modifier = sizeModifier
            .pointerInput(Unit) {
                containerWidth = size.width
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = containerWidth * 0.25f
                        val current = offsetX
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        scope.launch {
                            suspend fun animateOffsetTo(target: Float, durationMs: Int) {
                                val start = offsetX
                                animate(
                                    initialValue = start,
                                    targetValue = target,
                                    animationSpec = tween(durationMillis = durationMs)
                                ) { value, _ ->
                                    offsetX = value
                                }
                            }
                            if (current < -threshold) {
                                animateOffsetTo(-width, 130)
                                onHaptic()
                                onNext()
                                offsetX = width
                                animateOffsetTo(0f, 170)
                            } else if (current > threshold) {
                                animateOffsetTo(width, 130)
                                onHaptic()
                                onPrevious()
                                offsetX = -width
                                animateOffsetTo(0f, 170)
                            } else {
                                animateOffsetTo(0f, 170)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            val start = offsetX
                            animate(
                                initialValue = start,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 170)
                            ) { value, _ ->
                                offsetX = value
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        offsetX = (offsetX + dragAmount).coerceIn(-width, width)
                    }
                )
            }
    ) {
        val imageRequest = remember(imageUrl, containerWidth) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .size(max(containerWidth, 512))
                .crossfade(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }

        val progress = if (containerWidth > 0) {
            (offsetX / containerWidth).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val scale = 1f - 0.05f * kotlin.math.abs(progress)
        val alpha = 1f - 0.3f * kotlin.math.abs(progress)

        AsyncImage(
            model = imageRequest,
            contentDescription = "Album art",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(shape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun extractDominantColor(imageUrl: String?, isDark: Boolean): State<Color> {
    val context = LocalContext.current
    return produceState(initialValue = Color.Transparent, imageUrl, isDark) {
        if (imageUrl.isNullOrBlank()) {
            value = Color.Transparent
            return@produceState
        }

        value = withContext(Dispatchers.Default) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(96)
                    .allowHardware(false)
                    .crossfade(false)
                    .memoryCacheKey("palette_$imageUrl")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable
                    ?.let { it as? BitmapDrawable }
                    ?.bitmap
                    ?: return@withContext Color.Transparent
                extractColor(bitmap, isDark)
            } catch (_: Exception) {
                Color.Transparent
            }
        }
    }
}

private fun extractColor(bitmap: Bitmap, isDark: Boolean): Color {
    val palette = Palette.from(bitmap).generate()

    val swatch = if (isDark) {
        palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
    } else {
        palette.mutedSwatch ?: palette.lightMutedSwatch ?: palette.dominantSwatch
    }

    if (swatch == null) return Color.Transparent

    val r = swatch.rgb.red
    val g = swatch.rgb.green
    val b = swatch.rgb.blue

    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(r, g, b, hsl)

    // Clamp lightness to avoid too bright or too dark colors
    hsl[2] = hsl[2].coerceIn(0.2f, 0.6f)
    hsl[1] = hsl[1].coerceIn(0.3f, 0.7f)

    val clamped = ColorUtils.HSLToColor(hsl)

    return Color(clamped)
}

@Composable
private fun SeekBar(
    elapsed: Double,
    duration: Double,
    onSeek: (Double) -> Unit,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableFloatStateOf(-1f) }

    // Release hold once server position catches up to the seek target
    if (seekTarget >= 0f && !seeking) {
        if (kotlin.math.abs(elapsed.toFloat() - seekTarget) < 2f) {
            seekTarget = -1f
        }
    }

    val displayValue = when {
        seeking -> seekValue
        seekTarget >= 0f -> seekTarget
        else -> elapsed.toFloat()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSeek(seekValue.toDouble())
                seekTarget = seekValue
                seeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = if (compact) Modifier.height(28.dp) else Modifier
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val timeStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
            Text(formatTime(displayValue.toDouble()), style = timeStyle)
            Text(formatTime(duration), style = timeStyle)
        }
    }
}

private fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
