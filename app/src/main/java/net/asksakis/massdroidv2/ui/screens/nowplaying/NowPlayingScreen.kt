package net.asksakis.massdroidv2.ui.screens.nowplaying

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.AudioQualityBadges
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.HoldRepeatIconButton
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.KeepScreenOn
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.PlayerOptionsSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.QualityActionRow
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SeekBar
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SendspinStatusSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.TrackInfoSection
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.TransportControls
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.playback.SleepTimerBridge
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
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.LyricsSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SwipeableAlbumArt
import kotlin.math.max
import coil.compose.AsyncImage

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
    val isAudiobook by viewModel.isAudiobook.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val title = currentTrack?.name ?: player?.currentMedia?.title
        ?: cachedTrackDisplay?.title ?: "No track"
    // For an audiobook the artist/album lines carry author + chapter progress.
    val artist = if (isAudiobook && currentTrack?.authors?.isNotEmpty() == true) {
        currentTrack.authors.joinToString(", ")
    } else {
        currentTrack?.artistNames ?: player?.currentMedia?.artist
            ?: cachedTrackDisplay?.artist ?: ""
    }
    val album = if (isAudiobook && chapters.isNotEmpty() && currentChapterIndex >= 0) {
        "Chapter ${currentChapterIndex + 1} of ${chapters.size}"
    } else {
        currentTrack?.albumName ?: player?.currentMedia?.album
            ?: cachedTrackDisplay?.album ?: ""
    }
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
        if (isForeground) {
            // Pull fresh active-queue state on open. MA pushes position/queue
            // events sparsely, so a freshly-opened player must fetch rather than
            // show stale/empty state until the next (possibly far-off) event.
            viewModel.refreshNowPlaying()
        } else {
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

    // A dislike buries the track permanently and skips past it, so it needs a
    // way back: it is one tap next to the heart and the two are easy to confuse.
    LaunchedEffect(Unit) {
        viewModel.dislikeUndo.collectLatest { undo ->
            val result = snackbarHostState.showSnackbar(
                message = "Won't play \"${undo.trackName}\" again",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDislike(undo)
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
                        MdIconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
                        }
                    },
                    actions = {
                        MdIconButton(onClick = { showPlayerMenu = true }, modifier = Modifier.size(40.dp)) {
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
            chapterCount = if (isAudiobook) chapters.size else 0,
            onShowChapters = { showQueueSheet = true },
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
            val syncDelayMs by viewModel.sendspinSyncDelayMs.collectAsStateWithLifecycle(initialValue = 0)
            val isBt = viewModel.acoustic.isBtRoute()
            val calibrations by viewModel.acoustic.acousticRouteCalibrations.collectAsStateWithLifecycle(initialValue = emptyMap())
            val micPathUs by viewModel.acoustic.acousticMicPathUs.collectAsStateWithLifecycle(initialValue = 0L)
            val btRouteKey = viewModel.acoustic.getBtRouteKey()
            val acousticCorrectionMs = (calibrations[btRouteKey]?.correctionUs ?: 0L) / 1000
            val dstmStates by viewModel.queueDstmStates.collectAsStateWithLifecycle()

            net.asksakis.massdroidv2.ui.components.PlayerSettingsDialog(
                player = currentPlayer,
                initialDstmEnabled = dstmStates[currentPlayer.playerId] ?: false,
                isSendspinPlayer = currentPlayer.provider == "sendspin",
                isLocalPlayer = ssClientId != null && currentPlayer.playerId == ssClientId,
                initialAudioFormat = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(audioFormat),
                initialSyncDelayMs = syncDelayMs,
                onLoadConfig = { viewModel.getPlayerConfig(it) },
                onSave = { id, values -> viewModel.savePlayerConfig(id, values) },
                onDstmChanged = { viewModel.setDontStopTheMusic(currentPlayer.playerId, it) },
                onAudioFormatChanged = { viewModel.setAudioFormat(it) },
                onSyncDelayChanged = { viewModel.setSendspinSyncDelayMs(it) },
                isBtRoute = isBt,
                acousticCorrectionMs = acousticCorrectionMs.toInt(),
                acoustic = viewModel.acoustic,
                micPathCalibratedMs = micPathUs / 1000,
                isPlaybackActive = viewModel.acoustic.isPlaybackActive(),
                onPausePlayback = { viewModel.acoustic.pauseForCalibration() },
                onResumePlayback = { viewModel.acoustic.resumeAfterCalibration() },
                btRouteName = viewModel.acoustic.getBtRouteName(),
                onResetBtCalibration = { viewModel.acoustic.resetCalibration() },
                onResetMicPath = { viewModel.acoustic.resetMicPath() },
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
            inputAudioFormat = audioFormat,
            syncHistory = syncHistory,
            onSyncDelayChanged = { viewModel.setSendspinSyncDelayMs(it) },
            onDismiss = { showSendspinStatusSheet = false }
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerSheet(
            isActive = sleepTimerActive,
            remainingMinutes = sleepTimerRemainingMin,
            onStart = { minutes ->
                val targetId = player?.playerId
                if (targetId != null) {
                    viewModel.sleepTimerBridge.requestStart(minutes, targetId)
                }
            },
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
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
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
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
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
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
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
                MdIconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
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
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
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
            MdIconButton(
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
            // Keep metadata, seekbar, actions, and transport controls on the same
            // visual centerline in landscape.
            Column(
                modifier = Modifier.fillMaxWidth(0.94f),
                horizontalAlignment = Alignment.CenterHorizontally
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

                Spacer(modifier = Modifier.height(28.dp))

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
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                )

            }
            }
        }
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

