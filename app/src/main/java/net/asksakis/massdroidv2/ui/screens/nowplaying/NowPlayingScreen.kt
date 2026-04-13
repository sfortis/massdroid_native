package net.asksakis.massdroidv2.ui.screens.nowplaying

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.service.SleepTimerBridge
import net.asksakis.massdroidv2.ui.components.SleepTimerSheet
import androidx.compose.material.icons.filled.Bedtime
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.AddToPlaylistDialog
import net.asksakis.massdroidv2.ui.components.MediaArtwork
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.VolumeSlider
import net.asksakis.massdroidv2.ui.screens.nowplaying.LyricsAvailability
import kotlin.math.max
import coil.compose.AsyncImage

private enum class SwipeCommitDirection { NEXT, PREVIOUS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    onNavigateToAlbum: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    isForeground: Boolean = true,
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    val player by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val liveElapsedTime by viewModel.elapsedTime.collectAsStateWithLifecycle()
    val optimisticElapsed by viewModel.optimisticElapsed.collectAsStateWithLifecycle()
    val elapsedTime = if (liveElapsedTime > 0.0 || optimisticElapsed == null) liveElapsedTime
        else optimisticElapsed ?: 0.0
    val blockedArtistUris by viewModel.blockedArtistUris.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoadingPlaylists by viewModel.isLoadingPlaylists.collectAsStateWithLifecycle()
    val addingToPlaylistId by viewModel.addingToPlaylistId.collectAsStateWithLifecycle()
    val playlistContainsTrack by viewModel.playlistContainsTrack.collectAsStateWithLifecycle()

    val currentTrack = queueState?.currentItem?.track
    val currentArtistUri = MediaIdentity.canonicalArtistKey(
        itemId = currentTrack?.artistItemId,
        uri = currentTrack?.artistUri
    )
    val artistBlocked = currentArtistUri?.let { it in blockedArtistUris } ?: false
    val canToggleArtistBlock = currentArtistUri != null
    val allPlayers by viewModel.allPlayers.collectAsStateWithLifecycle()
    var showPlayerMenu by remember { mutableStateOf(false) }
    var showTransferSheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsStateWithLifecycle()
    val lyricsTimingOffsetMs by viewModel.lyricsTimingOffsetMs.collectAsStateWithLifecycle(initialValue = 0)
    val sendspinStatus by viewModel.sendspinStatus.collectAsStateWithLifecycle()
    val isSendspinPlayer by viewModel.isSendspinPlayer.collectAsStateWithLifecycle()
    val cachedTrackDisplay by viewModel.cachedTrackDisplay.collectAsStateWithLifecycle()
    val adjacentArtwork by viewModel.adjacentArtwork.collectAsStateWithLifecycle()
    val title = currentTrack?.name ?: player?.currentMedia?.title
        ?: cachedTrackDisplay?.title ?: "No track"
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist
        ?: cachedTrackDisplay?.artist ?: ""
    val album = currentTrack?.albumName ?: player?.currentMedia?.album
        ?: cachedTrackDisplay?.album ?: ""
    val imageUrl = currentTrack?.imageUrl ?: queueState?.currentItem?.imageUrl
        ?: player?.currentMedia?.imageUrl ?: cachedTrackDisplay?.imageUrl
    val duration = currentTrack?.duration ?: queueState?.currentItem?.duration
        ?: player?.currentMedia?.duration ?: cachedTrackDisplay?.duration ?: 0.0
    val audioFormat = queueState?.currentItem?.audioFormat
    val isPlaying = player?.state == PlaybackState.PLAYING
    val sleepTimerState by viewModel.sleepTimerBridge.state.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs = viewModel.sleepTimerBridge.remainingMs()
    val sleepTimerRemainingMin = (sleepTimerRemainingMs / 60_000).toInt()
    val sleepTimerActive = sleepTimerState !is SleepTimerBridge.State.Idle
    val sleepTimerLabel = when {
        sleepTimerRemainingMin >= 60 -> "${sleepTimerRemainingMin / 60}h ${sleepTimerRemainingMin % 60}min"
        sleepTimerRemainingMin > 0 -> "${sleepTimerRemainingMin}min"
        sleepTimerRemainingMs > 0 -> "${sleepTimerRemainingMs / 1000}s"
        else -> ""
    }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showPlayerSettingsDialog by remember { mutableStateOf(false) }
    var showSendspinStatusSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isForeground) {
        if (!isForeground) {
            showQueueSheet = false
            showPlayerMenu = false
            showTransferSheet = false
            showLyricsSheet = false
            showPlaylistDialog = false
            showPlayerSettingsDialog = false
            showSendspinStatusSheet = false
            showSleepTimerDialog = false
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(imageUrl, adjacentArtwork.previousImageUrl, adjacentArtwork.nextImageUrl) {
        val imageLoader = context.imageLoader
        listOfNotNull(imageUrl, adjacentArtwork.previousImageUrl, adjacentArtwork.nextImageUrl)
            .distinct()
            .forEach { url ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(768)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                )
            }
    }

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
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                player?.displayName ?: "Now Playing",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (sleepTimerActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Bedtime,
                                    contentDescription = "Sleep timer active",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showPlayerMenu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Player options", modifier = Modifier.size(22.dp))
                        }
                    },
                    expandedHeight = 48.dp,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    windowInsets = WindowInsets(0, 0, 0, 0)
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
                    previousImageUrl = adjacentArtwork.previousImageUrl,
                    nextImageUrl = adjacentArtwork.nextImageUrl,
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
                    controlsEnabled = player != null,
                    isSendspinPlayer = isSendspinPlayer,
                    sleepTimerActive = sleepTimerActive,
                    viewModel = viewModel,
                    onBack = onBack,
                    onNavigateToQueue = { showQueueSheet = true },
                    onShowPlaylistDialog = {
                        showPlaylistDialog = true
                        viewModel.loadPlaylists(force = true)
                    },
                    onShowLyrics = {
                        Log.d(
                            "LyricsDbg",
                            "request open portrait uri=${currentTrack?.uri} title=${currentTrack?.name} isForeground=$isForeground"
                        )
                        showLyricsSheet = true
                        viewModel.loadLyrics()
                    },
                    onShowSendspinStatus = { showSendspinStatusSheet = true },
                    onShowPlayerMenu = { showPlayerMenu = true },
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum
                )
            } else {
                NowPlayingPortrait(
                    paddingValues = paddingValues,
                    imageUrl = imageUrl,
                    previousImageUrl = adjacentArtwork.previousImageUrl,
                    nextImageUrl = adjacentArtwork.nextImageUrl,
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
                    controlsEnabled = player != null,
                    isSendspinPlayer = isSendspinPlayer,
                    viewModel = viewModel,
                    onShowPlaylistDialog = {
                        showPlaylistDialog = true
                        viewModel.loadPlaylists(force = true)
                    },
                    onShowLyrics = {
                        Log.d(
                            "LyricsDbg",
                            "request open landscape uri=${currentTrack?.uri} title=${currentTrack?.name} isForeground=$isForeground"
                        )
                        showLyricsSheet = true
                        viewModel.loadLyrics()
                    },
                    onShowSendspinStatus = { showSendspinStatusSheet = true },
                    onNavigateToQueue = { showQueueSheet = true },
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
                viewModel.addCurrentTrackToPlaylist(playlist) {}
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name) {
                    showPlaylistDialog = false
                }
            },
            onRemoveFromPlaylist = { playlist ->
                viewModel.removeCurrentTrackFromPlaylist(playlist) {}
            },
            containsTrack = playlistContainsTrack
        )
    }

    if (showPlayerMenu) {
        val otherPlayers = allPlayers.filter { it.available && it.playerId != player?.playerId }
        PlayerOptionsSheet(
            artistBlocked = artistBlocked,
            canToggleArtistBlock = canToggleArtistBlock,
            hasOtherPlayers = otherPlayers.isNotEmpty(),
            sleepTimerActive = sleepTimerActive,
            sleepTimerLabel = sleepTimerLabel,
            onDismiss = { showPlayerMenu = false },
            onPlayerSettings = {
                showPlayerMenu = false
                showPlayerSettingsDialog = true
            },
            onTransferQueue = {
                showPlayerMenu = false
                showTransferSheet = true
            },
            onSleepTimer = { showSleepTimerDialog = true },
            onStartSongRadio = currentTrack?.uri?.let { uri ->
                { viewModel.startSongRadio(uri) }
            },
            onClick = {
                showPlayerMenu = false
                viewModel.toggleCurrentArtistBlocked()
            }
        )
    }

    if (showTransferSheet) {
        val otherPlayers = allPlayers.filter { it.available && it.playerId != player?.playerId }
            .sortedBy { it.displayName.lowercase() }
        ModalBottomSheet(
            onDismissRequest = { showTransferSheet = false },
            containerColor = SheetDefaults.containerColor()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Column {
                    SheetDefaults.HeaderTitle(
                        text = "Transfer queue to",
                        modifier = Modifier.padding(
                            horizontal = SheetDefaults.HeaderHorizontalPadding,
                            vertical = SheetDefaults.HeaderVerticalPadding
                        )
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                }
                otherPlayers.forEach { target ->
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text(target.displayName) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Speaker,
                                contentDescription = null,
                                tint = if (target.state == PlaybackState.PLAYING) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.transferQueue(target.playerId)
                            showTransferSheet = false
                        }
                    )
                }
            }
        }
    }

    player?.let { currentPlayer ->
        if (showPlayerSettingsDialog) {
            val ssClientId by viewModel.sendspinClientId.collectAsStateWithLifecycle(initialValue = viewModel.cachedSendspinClientId)
            val audioFormat by viewModel.sendspinAudioFormat.collectAsStateWithLifecycle(initialValue = viewModel.cachedSendspinAudioFormat)
            val staticDelayMs by viewModel.sendspinStaticDelayMs.collectAsStateWithLifecycle(initialValue = 0)
            val isBt = viewModel.isBtRoute()
            val phoneBaseline by viewModel.acousticPhoneBaselineUs.collectAsStateWithLifecycle(initialValue = 0L)
            val calibrations by viewModel.acousticRouteCalibrations.collectAsStateWithLifecycle(initialValue = emptyMap())
            val btRouteKey = viewModel.getBtRouteKey()
            val acousticCorrectionMs = (calibrations[btRouteKey]?.correctionUs ?: 0L) / 1000

            net.asksakis.massdroidv2.ui.components.PlayerSettingsDialog(
                player = currentPlayer,
                initialDstmEnabled = viewModel.queueState.value?.dontStopTheMusicEnabled ?: false,
                isSendspinPlayer = currentPlayer.provider == "sendspin",
                isLocalPlayer = ssClientId != null && currentPlayer.playerId == ssClientId,
                initialAudioFormat = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(audioFormat),
                initialStaticDelayMs = staticDelayMs,
                onLoadConfig = { viewModel.getPlayerConfig(it) },
                onSave = { id, values -> viewModel.savePlayerConfig(id, values) },
                onDstmChanged = { viewModel.setDontStopTheMusic(currentPlayer.playerId, it) },
                onAudioFormatChanged = { viewModel.setAudioFormat(it) },
                onStaticDelayChanged = { viewModel.setSendspinStaticDelayMs(it) },
                isBtRoute = isBt,
                acousticCorrectionMs = acousticCorrectionMs.toInt(),
                calibrator = viewModel.acousticCalibrator,
                hasPhoneBaseline = phoneBaseline > 0L,
                phoneBaselineUs = phoneBaseline,
                isPlaybackActive = viewModel.isPlaybackActive(),
                onPausePlayback = { viewModel.pauseForCalibration() },
                btRouteName = viewModel.getBtRouteName(),
                onBaselineComplete = { viewModel.saveAcousticBaseline(it) },
                onAcousticCalibrationComplete = { correctionUs, quality ->
                    viewModel.saveAcousticCalibration(correctionUs, quality)
                },
                onResetPhoneBaseline = { viewModel.resetAcousticBaseline() },
                onResetBtCalibration = { viewModel.resetAcousticCalibration() },
                onDismiss = { showPlayerSettingsDialog = false }
            )
        }
    }

    if (showLyricsSheet && isForeground) {
        KeepScreenOn()
        Log.d("LyricsDbg", "sheet open content=${lyrics::class.simpleName} loading=$isLoadingLyrics")
        LyricsSheet(
            lyrics = lyrics,
            isLoading = isLoadingLyrics,
            elapsedTime = elapsedTime,
            title = title,
            artist = artist,
            lyricsTimingOffsetMs = lyricsTimingOffsetMs,
            onLyricsTimingOffsetChanged = { viewModel.setLyricsTimingOffsetMs(it) },
            onLyricsTimingOffsetDelta = { viewModel.adjustLyricsTimingOffsetBy(it) },
            onSeekToLyricsPosition = { viewModel.seek(it) },
            onDismiss = { showLyricsSheet = false }
        )
    }

    val statusSnapshot = sendspinStatus
    val syncHistory by viewModel.sendspinSyncHistory.collectAsStateWithLifecycle()
    if (showSendspinStatusSheet && statusSnapshot != null) {
        SendspinStatusSheet(
            status = statusSnapshot,
            syncHistory = syncHistory,
            onStaticDelayChanged = { viewModel.setSendspinStaticDelayMs(it) },
            onDismiss = { showSendspinStatusSheet = false }
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerSheet(
            isActive = sleepTimerActive,
            remainingMinutes = sleepTimerRemainingMin,
            onStart = { viewModel.sleepTimerBridge.requestStart(it) },
            onCancel = { viewModel.sleepTimerBridge.requestCancel() },
            onDismiss = { showSleepTimerDialog = false }
        )
    }

    if (showQueueSheet) {
        net.asksakis.massdroidv2.ui.screens.queue.QueueSheet(
            onDismiss = { showQueueSheet = false }
        )
    }
}

@Composable
private fun NowPlayingPortrait(
    paddingValues: PaddingValues,
    imageUrl: String?,
    previousImageUrl: String?,
    nextImageUrl: String?,
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
    controlsEnabled: Boolean,
    isSendspinPlayer: Boolean,
    viewModel: NowPlayingViewModel,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowSendspinStatus: () -> Unit,
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
        Spacer(modifier = Modifier.weight(0.5f))

        SwipeableAlbumArt(
            imageUrl = imageUrl,
            previousImageUrl = previousImageUrl,
            nextImageUrl = nextImageUrl,
            onNext = { if (controlsEnabled) viewModel.next() },
            onPrevious = { if (controlsEnabled) viewModel.previousTrack() },
            canSwipePrevious = controlsEnabled && (queueState?.currentIndex ?: 0) > 0,
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )

        Spacer(modifier = Modifier.weight(0.5f))

        QualityActionRow(
            audioFormat = audioFormat,
            currentTrack = currentTrack,
            viewModel = viewModel,
            onShowPlaylistDialog = onShowPlaylistDialog,
            onShowLyrics = onShowLyrics,
            onNavigateToQueue = onNavigateToQueue,
            onShowSendspinStatus = onShowSendspinStatus,
            isSendspinPlayer = isSendspinPlayer,
            enabled = controlsEnabled
        )

        Spacer(modifier = Modifier.height(28.dp))

        TrackInfoSection(
            title = title,
            artist = artist,
            album = album,
            currentTrack = currentTrack,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum
        )

        Spacer(modifier = Modifier.height(28.dp))

        SeekBar(
            elapsed = elapsedTime,
            duration = duration,
            onSeek = { if (controlsEnabled) viewModel.seek(it) },
            enabled = controlsEnabled,
            compact = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        TransportControls(
            isPlaying = isPlaying,
            queueState = queueState,
            viewModel = viewModel,
            enabled = controlsEnabled,
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Volume indicator: fade in on change, fade out after 2s
        val currentVolume = player?.volumeLevel ?: 0
        val volumeMuted = player?.volumeMuted ?: false
        var lastShownVolume by remember { mutableIntStateOf(currentVolume) }
        val volumeAlpha = remember { Animatable(0f) }

        LaunchedEffect(currentVolume, volumeMuted) {
            if (lastShownVolume != currentVolume) {
                lastShownVolume = currentVolume
                volumeAlpha.animateTo(1f, tween(300))
                delay(2000)
                volumeAlpha.animateTo(0f, tween(1000))
            }
        }

        // Fixed height so it doesn't push other components
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (volumeAlpha.value > 0.01f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = volumeAlpha.value },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (volumeMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { currentVolume / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                    )
                    Text(
                        text = "$currentVolume%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NowPlayingLandscape(
    paddingValues: PaddingValues,
    imageUrl: String?,
    previousImageUrl: String?,
    nextImageUrl: String?,
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
    controlsEnabled: Boolean,
    isSendspinPlayer: Boolean,
    sleepTimerActive: Boolean,
    viewModel: NowPlayingViewModel,
    onBack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowSendspinStatus: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: close/name overlay + centered album art
        Box(
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            // Close + player name (overlay, doesn't affect art centering)
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
                }
                Text(
                    text = player?.displayName ?: "Now Playing",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (sleepTimerActive) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep timer active",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Album art (true center)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                SwipeableAlbumArt(
                    imageUrl = imageUrl,
                    previousImageUrl = previousImageUrl,
                    nextImageUrl = nextImageUrl,
                    onNext = { if (controlsEnabled) viewModel.next() },
                    onPrevious = { if (controlsEnabled) viewModel.previousTrack() },
                    canSwipePrevious = controlsEnabled && (queueState?.currentIndex ?: 0) > 0,
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    fillMaxWidth = false
                )
            }
        }

        // Right: track info + controls (grouped, constrained width)
        Box(
            modifier = Modifier
                .weight(1.32f)
                .fillMaxHeight()
                .padding(start = 16.dp, end = 8.dp)
        ) {
            // Menu overlay top-right (doesn't affect centering)
            IconButton(
                onClick = onShowPlayerMenu,
                modifier = Modifier.size(36.dp).align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Player options", modifier = Modifier.size(20.dp))
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

            TrackInfoSection(
                title = title,
                artist = artist,
                album = album,
                currentTrack = currentTrack,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                compact = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Constrained controls block
            Column(
                modifier = Modifier.fillMaxWidth(0.94f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SeekBar(
                    elapsed = elapsedTime,
                    duration = duration,
                    onSeek = { if (controlsEnabled) viewModel.seek(it) },
                    enabled = controlsEnabled,
                    compact = false
                )

                // Action row (playlist, lyrics, badges, queue) below seekbar
                QualityActionRow(
                    audioFormat = audioFormat,
                    currentTrack = currentTrack,
                    viewModel = viewModel,
                    onShowPlaylistDialog = onShowPlaylistDialog,
                    onShowLyrics = onShowLyrics,
                    onNavigateToQueue = onNavigateToQueue,
                    onShowSendspinStatus = onShowSendspinStatus,
                    isSendspinPlayer = isSendspinPlayer,
                    enabled = controlsEnabled,
                    compact = false
                )

                Spacer(modifier = Modifier.height(20.dp))

                TransportControls(
                    isPlaying = isPlaying,
                    queueState = queueState,
                    viewModel = viewModel,
                    enabled = controlsEnabled,
                    compact = false,
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                )

            }
            }
        }
    }
}

@Composable
private fun AudioQualityBadges(
    audioFormat: AudioFormatInfo?,
    outputCodec: String? = null,
    isSendspinPlayer: Boolean = false,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val badges = remember(audioFormat, outputCodec) { buildAudioQualityBadges(audioFormat, outputCodec) }
    val displayBadges = if (badges.isEmpty() && isSendspinPlayer) listOf("Sendspin") else badges
    if (displayBadges.isEmpty()) return

    Row(
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(999.dp)).clickable { onClick() } else Modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayBadges.forEach { badge ->
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
    onShowSendspinStatus: () -> Unit,
    isSendspinPlayer: Boolean = false,
    enabled: Boolean = true,
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
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add to playlist",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val lyricsAvailability by viewModel.lyricsAvailability.collectAsStateWithLifecycle()
                val lyricsTint = when (lyricsAvailability) {
                    LyricsAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
                    LyricsAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    LyricsAvailability.LOADING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    LyricsAvailability.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val lyricsEnabled = lyricsAvailability != LyricsAvailability.LOADING &&
                    lyricsAvailability != LyricsAvailability.UNAVAILABLE
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Log.d(
                            "LyricsDbg",
                            "icon tap availability=$lyricsAvailability uri=${currentTrack?.uri} title=${currentTrack?.name} enabled=$lyricsEnabled"
                        )
                        when (lyricsAvailability) {
                            LyricsAvailability.AVAILABLE -> onShowLyrics()
                            LyricsAvailability.UNKNOWN -> viewModel.loadLyrics()
                            LyricsAvailability.LOADING,
                            LyricsAvailability.UNAVAILABLE -> Unit
                        }
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = lyricsEnabled
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Lyrics",
                        modifier = Modifier.size(actionIconSize),
                        tint = lyricsTint
                    )
                }
            }
            val streamCodec by viewModel.sendspinStreamCodec.collectAsStateWithLifecycle(initialValue = null)
            val outputCodec = streamCodec
            AudioQualityBadges(
                audioFormat = audioFormat,
                outputCodec = outputCodec,
                isSendspinPlayer = isSendspinPlayer,
                compact = compact,
                onClick = if (isSendspinPlayer) onShowSendspinStatus else null
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                @Suppress("DEPRECATION")
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToQueue()
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
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
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
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
private fun SendspinStatusSheet(
    status: SendspinStatusUi,
    syncHistory: List<net.asksakis.massdroidv2.data.sendspin.SendspinManager.SyncSample> = emptyList(),
    onStaticDelayChanged: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bufferSeconds = status.activeBufferMs / 1000f
    val maxSeconds = 30f
    val bufferColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    val stateLabel = when (status.syncState) {
        SyncState.IDLE -> "Idle"
        SyncState.SYNCHRONIZED -> "Synchronized"
        SyncState.HOLDOVER_PLAYING_FROM_BUFFER -> "Holdover"
        SyncState.SYNC_ERROR_REBUFFERING -> "Rebuffering"
    }
    val transportLabel = when (status.connectionState) {
        SendspinState.STREAMING -> "Streaming"
        SendspinState.SYNCING -> "Syncing"
        SendspinState.HANDSHAKING -> "Handshaking"
        SendspinState.AUTHENTICATING -> "Authenticating"
        SendspinState.CONNECTING -> "Connecting"
        SendspinState.ERROR -> "Error"
        SendspinState.DISCONNECTED -> "Disconnected"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetDefaults.HeaderTitle(text = "Streaming Status")

            StatusLine(label = "Transport", value = transportLabel)
            StatusLine(label = "Playback", value = stateLabel)
            StatusLine(label = "Codec", value = status.codec ?: "Unknown")
            StatusLine(label = "Mode", value = status.configuredFormat)
            StatusLine(label = "Network", value = status.networkMode)

            HorizontalDivider()

            // Sync details in compact layout
            val smallStyle = MaterialTheme.typography.labelMedium
            val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
            val valueColor = MaterialTheme.colorScheme.onSurface
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallStatusLine("Sync mode", status.correctionMode, smallStyle, dimColor, valueColor)
                SmallStatusLine("Engine correction", "${"%.1f".format(status.absoluteSyncMs)}ms${if (status.syncMuted) "  (muted)" else ""}", smallStyle, dimColor, valueColor)
                SmallStatusLine("DAC drift", "${"%.1f".format(status.dacSyncErrorMs)}ms", smallStyle, dimColor, valueColor)
                SmallStatusLine("Latency model", "${status.outputLatencyMs}ms output + ${status.acousticCorrectionMs}ms acoustic", smallStyle, dimColor, valueColor)
                SmallStatusLine("Clock", "${status.clockSamples} samples / ${status.clockErrorUs / 1000.0}ms err", smallStyle, dimColor, valueColor)
                SmallStatusLine("Resyncs", "${status.resyncs}", smallStyle, dimColor, valueColor)
                SmallStatusLine("Buffer", String.format(java.util.Locale.US, "%.1fs  /  %d KB", bufferSeconds, status.bufferBytes / 1000), smallStyle, dimColor, valueColor)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                val progress = (bufferSeconds / maxSeconds).coerceIn(0f, 1f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = bufferColor,
                        size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0s", style = MaterialTheme.typography.labelSmall, color = dimColor)
                Text("30s", style = MaterialTheme.typography.labelSmall, color = dimColor)
            }

            if (syncHistory.size >= 2) {
                HorizontalDivider()
                var staticDelayMs by remember { mutableIntStateOf(status.staticDelayMs) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Static delay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                staticDelayMs = (staticDelayMs - 2).coerceAtLeast(0)
                                onStaticDelayChanged(staticDelayMs)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            "${staticDelayMs}ms",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        IconButton(
                            onClick = {
                                staticDelayMs = (staticDelayMs + 2).coerceAtMost(200)
                                onStaticDelayChanged(staticDelayMs)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                net.asksakis.massdroidv2.ui.components.SyncErrorGraph(syncHistory)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previous
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SmallStatusLine(
    label: String,
    value: String,
    style: androidx.compose.ui.text.TextStyle,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = style, color = labelColor)
        Text(value, style = style, color = valueColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSheet(
    lyrics: LyricsProvider.LyricsContent,
    isLoading: Boolean,
    elapsedTime: Double,
    title: String,
    artist: String,
    lyricsTimingOffsetMs: Int,
    onLyricsTimingOffsetChanged: (Int) -> Unit,
    onLyricsTimingOffsetDelta: (Int) -> Unit,
    onSeekToLyricsPosition: (Double) -> Unit,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = buildString {
                        append(title)
                        if (artist.isNotBlank()) {
                            append(" - ")
                            append(artist)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                lyrics == LyricsProvider.LyricsContent.None -> {
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
                lyrics is LyricsProvider.LyricsContent.Synced -> {
                    SyncedLyricsContent(
                        parsedLines = lyrics.parsedLines,
                        elapsedTimeMs = (elapsedTime * 1000).toLong(),
                        lyricsTimingOffsetMs = lyricsTimingOffsetMs,
                        onSeekToPosition = onSeekToLyricsPosition,
                        modifier = Modifier.weight(1f)
                    )
                    LyricsTimingAdjuster(
                        offsetMs = lyricsTimingOffsetMs,
                        onOffsetChanged = onLyricsTimingOffsetChanged,
                        onOffsetDelta = onLyricsTimingOffsetDelta
                    )
                }
                lyrics is LyricsProvider.LyricsContent.Plain -> {
                    PlainLyricsContent(text = lyrics.text)
                }
                else -> {
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SyncedLyricsContent(
    parsedLines: List<LyricsProvider.LrcLine>,
    elapsedTimeMs: Long,
    lyricsTimingOffsetMs: Int,
    onSeekToPosition: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    data class DisplayLine(
        val timeMs: Long,
        val text: String,
        val isBreak: Boolean,
        val breakEndMs: Long? = null
    )

    val introBreakLeadMs = 5_000L
    val musicalBreakThresholdMs = 5_000L
    val noteCount = 7
    val highlightLeadMs = 500L
    val lines = remember(parsedLines) {
        val breakMarkers = parsedLines
            .filter { it.timeMs > 0L && it.text.isBlank() }
            .map { it.timeMs }
            .toSet()
        val contentLines = parsedLines.filter { it.text.isNotBlank() }
        buildList {
            if (contentLines.isNotEmpty()) {
                val firstTime = contentLines.first().timeMs
                if (firstTime >= introBreakLeadMs) {
                    add(
                        DisplayLine(
                            timeMs = firstTime - introBreakLeadMs,
                            text = "",
                            isBreak = true,
                            breakEndMs = firstTime
                        )
                    )
                }
            }

            contentLines.forEachIndexed { index, line ->
                add(
                    DisplayLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        isBreak = false
                    )
                )

                val nextLine = contentLines.getOrNull(index + 1) ?: return@forEachIndexed
                val markerTime = breakMarkers.firstOrNull { it > line.timeMs && it < nextLine.timeMs }
                if (markerTime != null && nextLine.timeMs - markerTime >= musicalBreakThresholdMs) {
                    add(
                        DisplayLine(
                            timeMs = markerTime,
                            text = "",
                            isBreak = true,
                            breakEndMs = nextLine.timeMs
                        )
                    )
                }
            }
        }
    }
    var currentIndex by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var containerHeight by remember { mutableFloatStateOf(0f) }
    val topPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * 0.16f).toDp() }
    }
    val bottomPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * 0.38f).toDp() }
    }

    LaunchedEffect(elapsedTimeMs, lines, lyricsTimingOffsetMs) {
        if (lines.isEmpty()) return@LaunchedEffect
        val effectiveElapsedMs = elapsedTimeMs + highlightLeadMs + lyricsTimingOffsetMs
        val idx = lines.indexOfLast { it.timeMs <= effectiveElapsedMs }
        val newIdx = if (idx < 0) -1 else idx
        if (newIdx != currentIndex) {
            currentIndex = newIdx
            if (newIdx >= 0) {
                val anchorPx = containerHeight * 0.35f
                val targetVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == newIdx }
                if (targetVisibleItem != null) {
                    val targetDelta = (targetVisibleItem.offset + targetVisibleItem.size / 2f) - anchorPx
                    listState.animateScrollBy(
                        value = targetDelta,
                        animationSpec = tween(
                            durationMillis = 520,
                            easing = FastOutSlowInEasing
                        )
                    )
                } else {
                    listState.animateScrollToItem(index = newIdx)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { containerHeight = it.size.height.toFloat() }
            .clipToBounds(),
        contentPadding = PaddingValues(
            top = topPaddingDp,
            bottom = bottomPaddingDp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(lines) { index, line ->
                val distance = if (currentIndex >= 0) kotlin.math.abs(index - currentIndex) else index
                val targetAlpha = when {
                    index == currentIndex -> 1f
                    distance <= 1 -> 0.62f
                    distance <= 2 -> 0.40f
                    distance <= 3 -> 0.24f
                    else -> 0.14f
                }
                val alpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = tween(500),
                    label = "alpha$index"
                )
                if (line.isBreak) {
                    val durationMs = ((line.breakEndMs ?: line.timeMs) - highlightLeadMs - line.timeMs).coerceAtLeast(1L)
                    val elapsedInBreakMs = (elapsedTimeMs + lyricsTimingOffsetMs - line.timeMs).coerceAtLeast(0L)
                    val noteProgress = (elapsedInBreakMs.toFloat() / durationMs.toFloat()) * noteCount
                    val filledNotes = noteProgress.toInt().coerceIn(0, noteCount)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(noteCount) { noteIndex ->
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (index == currentIndex && noteIndex < filledNotes) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha.coerceAtLeast(0.22f))
                                    },
                                    modifier = Modifier.size(if (index == currentIndex) 18.dp else 16.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = line.text,
                        style = if (index == currentIndex) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleMedium,
                        fontWeight = if (index == currentIndex) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSeekToPosition(line.timeMs / 1000.0)
                            }
                            .padding(vertical = 6.dp)
                    )
                }
        }
    }
}

@Composable
private fun LyricsTimingAdjuster(
    offsetMs: Int,
    onOffsetChanged: (Int) -> Unit,
    onOffsetDelta: (Int) -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f)
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val stepMs = 500
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                HoldRepeatIconButton(
                    contentDescription = "Lyrics earlier",
                    tint = iconTint,
                    icon = Icons.Default.Remove,
                    onStep = { onOffsetDelta(-stepMs) }
                )
                Text(
                    text = "Timing ${formatLyricsOffset(offsetMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { onOffsetChanged(0) }
                )
                HoldRepeatIconButton(
                    contentDescription = "Lyrics later",
                    tint = iconTint,
                    icon = Icons.Default.Add,
                    onStep = { onOffsetDelta(stepMs) }
                )
            }
        }
    }
}

@Composable
private fun HoldRepeatIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onStep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .pointerInput(onStep) {
                detectTapGestures(
                    onPress = {
                        onStep()
                        val releasedEarly = withTimeoutOrNull(420) { tryAwaitRelease() } == true
                        if (releasedEarly) return@detectTapGestures
                        while (true) {
                            onStep()
                            val released = withTimeoutOrNull(220) { tryAwaitRelease() } == true
                            if (released) break
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun formatLyricsOffset(offsetMs: Int): String {
    val seconds = offsetMs / 1000f
    return String.format(
        java.util.Locale.US,
        "%+.1fs",
        seconds
    )
}

private fun buildAudioQualityBadges(audioFormat: AudioFormatInfo?, outputCodec: String? = null): List<String> {
    if (audioFormat == null && outputCodec == null) return emptyList()

    val sourceCodec = audioFormat?.contentType
        ?.replace('_', ' ')
        ?.uppercase()
        ?.takeIf { it.isNotBlank() && it != "?" }

    val qualityLabel = when {
        (audioFormat?.bitDepth ?: 0) >= 24 -> "HQ"
        (audioFormat?.contentType ?: "").equals("flac", ignoreCase = true) -> "HQ"
        (audioFormat?.bitRate ?: 0) >= 900_000 -> "HQ"
        audioFormat != null -> "LQ"
        else -> null
    }

    val parts = mutableListOf<String>()
    if (qualityLabel != null) parts += qualityLabel
    if (sourceCodec != null && outputCodec != null && sourceCodec != outputCodec) {
        parts += "$sourceCodec \u2192 $outputCodec"
    } else if (sourceCodec != null) {
        parts += sourceCodec
    } else if (outputCodec != null) {
        parts += outputCodec
    }

    val badge = parts.joinToString(" \u2022 ").ifBlank { null }
    return listOfNotNull(badge)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    artistBlocked: Boolean,
    canToggleArtistBlock: Boolean,
    hasOtherPlayers: Boolean,
    sleepTimerActive: Boolean,
    sleepTimerLabel: String,
    onDismiss: () -> Unit,
    onPlayerSettings: () -> Unit,
    onTransferQueue: () -> Unit,
    onSleepTimer: () -> Unit,
    onStartSongRadio: (() -> Unit)?,
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
            if (hasOtherPlayers) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Transfer Queue") },
                    supportingContent = {
                        Text(
                            "Move playback to another player",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable(onClick = onTransferQueue)
                )
            }
            if (onStartSongRadio != null) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Start Song Radio") },
                    supportingContent = {
                        Text(
                            "Play similar tracks based on current song",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable {
                        onStartSongRadio()
                        onDismiss()
                    }
                )
            }
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = {
                    Text(if (sleepTimerActive) "Sleep Timer ($sleepTimerLabel)" else "Sleep Timer")
                },
                supportingContent = {
                    Text(
                        if (sleepTimerActive) "Tap to change or cancel" else "Stop playback after a set time",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                    )
                },
                modifier = Modifier.clickable {
                    onSleepTimer()
                    onDismiss()
                }
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

    // Title: bold, centered, marquee
    Text(
        text = title,
        style = titleStyle,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp)
    )
    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

    if (compact && album.isNotBlank() && artist.isNotBlank()) {
        // Landscape: "Album · Artist" on one line, centered, with overflow protection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = album,
                style = albumStyle,
                color = if (albumClickable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (albumClickable) Modifier.clickable {
                    onNavigateToAlbum(currentTrack.albumItemId!!, currentTrack.albumProvider!!, album)
                } else Modifier
            )
            Text(
                text = " · ",
                style = albumStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = artist,
                style = albumStyle,
                color = if (artistClickable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (artistClickable) Modifier.clickable {
                    onNavigateToArtist(currentTrack.artistItemId!!, currentTrack.artistProvider!!, artist)
                } else Modifier
            )
        }
    } else {
        // Portrait (or compact without album): separate lines
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
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    viewModel: NowPlayingViewModel,
    enabled: Boolean = true,
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
        }, enabled = enabled) {
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
        }, modifier = Modifier.size(buttonSize), enabled = enabled) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(iconSize))
        }

        FilledIconButton(onClick = {
            onHaptic()
            viewModel.playPause()
        }, modifier = Modifier.size(playSize), enabled = enabled) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(playIconSize)
            )
        }

        IconButton(onClick = {
            onHaptic()
            viewModel.next()
        }, modifier = Modifier.size(buttonSize), enabled = enabled) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(iconSize))
        }

        IconButton(onClick = {
            onHaptic()
            viewModel.cycleRepeat()
        }, enabled = enabled) {
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
    previousImageUrl: String?,
    nextImageUrl: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    canSwipePrevious: Boolean = true,
    onHaptic: () -> Unit = {},
    fillMaxWidth: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableIntStateOf(1) }
    var pendingCommittedImageUrl by remember { mutableStateOf<String?>(null) }
    var pendingCommitDirection by remember { mutableStateOf<SwipeCommitDirection?>(null) }
    var committedOffsetX by remember { mutableFloatStateOf(0f) }
    val canSwipeNext = nextImageUrl != null
    val incomingTravelFactor = 0.94f

    val shape = MaterialTheme.shapes.medium

    val outerModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
    } else {
        Modifier.fillMaxWidth(0.82f).heightIn(max = 196.dp).aspectRatio(1f)
    }
    val artworkModifier = if (fillMaxWidth) {
        Modifier.fillMaxWidth(0.915f).aspectRatio(1f)
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = outerModifier
            .clip(shape)
            .clipToBounds()
            .pointerInput(canSwipePrevious, canSwipeNext, previousImageUrl, nextImageUrl) {
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
                                animateOffsetTo(-width, 180)
                                pendingCommittedImageUrl = nextImageUrl
                                committedOffsetX = -width + (width * incomingTravelFactor)
                                pendingCommitDirection = SwipeCommitDirection.NEXT
                            } else if (current > threshold) {
                                if (!canSwipePrevious) {
                                    animateOffsetTo(0f, 200)
                                    return@launch
                                }
                                animateOffsetTo(width, 180)
                                pendingCommittedImageUrl = previousImageUrl
                                committedOffsetX = width - (width * incomingTravelFactor)
                                pendingCommitDirection = SwipeCommitDirection.PREVIOUS
                            } else {
                                animateOffsetTo(0f, 200)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            val start = offsetX
                            animate(
                                initialValue = start,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                            ) { value, _ ->
                                offsetX = value
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val width = containerWidth.toFloat().coerceAtLeast(1f)
                        val minOffset = if (canSwipeNext) -width else 0f
                        val maxOffset = if (canSwipePrevious) width else 0f
                        offsetX = (offsetX + dragAmount).coerceIn(minOffset, maxOffset)
                    }
                )
            }
        ,
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(imageUrl, pendingCommittedImageUrl) {
            if (pendingCommittedImageUrl != null && imageUrl == pendingCommittedImageUrl) {
                val start = committedOffsetX
                animate(
                    initialValue = start,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                ) { value, _ ->
                    committedOffsetX = value
                }
                offsetX = 0f
                committedOffsetX = 0f
                pendingCommittedImageUrl = null
            }
        }

        LaunchedEffect(pendingCommitDirection) {
            when (pendingCommitDirection) {
                SwipeCommitDirection.NEXT -> {
                    onHaptic()
                    onNext()
                    pendingCommitDirection = null
                }
                SwipeCommitDirection.PREVIOUS -> {
                    onHaptic()
                    onPrevious()
                    pendingCommitDirection = null
                }
                null -> Unit
            }
        }

        fun buildImageRequest(url: String?) = ImageRequest.Builder(context)
            .data(url)
            .size(max(containerWidth, 512))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        val imageRequest = remember(imageUrl, containerWidth) { buildImageRequest(imageUrl) }
        val previousImageRequest = remember(previousImageUrl, containerWidth) { buildImageRequest(previousImageUrl) }
        val nextImageRequest = remember(nextImageUrl, containerWidth) { buildImageRequest(nextImageUrl) }

        val progress = if (containerWidth > 0) {
            (offsetX / containerWidth).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val targetProgress = kotlin.math.abs(progress)
        val easedProgress = FastOutSlowInEasing.transform(targetProgress)
        val currentScale = 1f - 0.20f * easedProgress
        val currentAlpha = 1f - 0.88f * easedProgress
        val targetScale = 0.74f + 0.26f * easedProgress
        val targetAlpha = (0.02f + 0.98f * easedProgress).coerceIn(0f, 1f)
        val width = containerWidth.toFloat().coerceAtLeast(1f)
        val incomingTravel = width * incomingTravelFactor
        val inCommittedPhase = pendingCommittedImageUrl != null
        val commitProgress = if (inCommittedPhase) {
            (1f - (kotlin.math.abs(offsetX) / width)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val committedScale = 1f
        val committedAlpha = 0.94f + 0.06f * commitProgress

        val displayedCurrentImageUrl = pendingCommittedImageUrl ?: imageUrl
        val displayedCurrentRequest = remember(displayedCurrentImageUrl, containerWidth) {
            buildImageRequest(displayedCurrentImageUrl)
        }

        if (!inCommittedPhase && offsetX > 0f && previousImageUrl != null && canSwipePrevious) {
            MediaArtwork(
                model = previousImageRequest,
                contentDescription = "Previous album art",
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier
                    .then(artworkModifier)
                    .graphicsLayer {
                        translationX = offsetX - incomingTravel
                        scaleX = targetScale
                        scaleY = targetScale
                        this.alpha = targetAlpha
                    },
                shape = shape,
                iconSize = 64.dp,
                contentScale = ContentScale.Crop
            )
        } else if (!inCommittedPhase && offsetX < 0f && nextImageUrl != null && canSwipeNext) {
            MediaArtwork(
                model = nextImageRequest,
                contentDescription = "Next album art",
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier
                    .then(artworkModifier)
                    .graphicsLayer {
                        translationX = offsetX + incomingTravel
                        scaleX = targetScale
                        scaleY = targetScale
                        this.alpha = targetAlpha
                    },
                shape = shape,
                iconSize = 64.dp,
                contentScale = ContentScale.Crop
            )
        }

        MediaArtwork(
            model = displayedCurrentRequest,
            contentDescription = "Album art",
            fallbackIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .then(artworkModifier)
                .graphicsLayer {
                    translationX = if (inCommittedPhase) committedOffsetX else offsetX
                    scaleX = if (inCommittedPhase) committedScale else currentScale
                        scaleY = if (inCommittedPhase) committedScale else currentScale
                        this.alpha = if (inCommittedPhase) committedAlpha else currentAlpha
                },
            shape = shape,
            iconSize = 64.dp,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    elapsed: Double,
    duration: Double,
    onSeek: (Double) -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
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
    val thumbWidth = if (compact) 5.dp else 5.dp
    val thumbHeight = if (compact) 14.dp else 16.dp
    val trackHeight = if (compact) 6.dp else 8.dp

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
            enabled = enabled,
            modifier = if (compact) Modifier.height(30.dp) else Modifier.height(34.dp),
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(thumbWidth, thumbHeight)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(trackHeight),
                    thumbTrackGapSize = 0.dp
                )
            }
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
