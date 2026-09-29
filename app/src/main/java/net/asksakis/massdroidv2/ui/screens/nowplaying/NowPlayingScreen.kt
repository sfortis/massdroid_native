package net.asksakis.massdroidv2.ui.screens.nowplaying

import net.asksakis.massdroidv2.ui.components.MdIconButton

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.landscapeArtSurfaceWidth
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.KeepScreenOn
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.PlayerOptionsSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.QualityActionRow
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SeekBar
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SendspinStatusSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.TrackInfoSection
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.TransportControls
import net.asksakis.massdroidv2.playback.SleepTimerBridge
import net.asksakis.massdroidv2.ui.components.SleepTimerSheet
import androidx.compose.material.icons.filled.Bedtime
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.QueueSource
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.ui.components.AddToPlaylistDialog
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.grain
import net.asksakis.massdroidv2.domain.nfc.NfcTagAction
import net.asksakis.massdroidv2.ui.nfc.NfcWriteChoice
import net.asksakis.massdroidv2.ui.nfc.NfcWriteSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.LyricsSheet
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.SwipeableAlbumArt
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import net.asksakis.massdroidv2.ui.util.BlurTransformation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    onNavigateToAlbum: (itemId: String, provider: String, name: String) -> Unit = { _, _, _ -> },
    isForeground: Boolean = true,
    /**
     * Insets the top bar keeps clear of. Zero inside the expanding player sheet, which
     * starts below the status bar on its own; the status bar when this screen is a
     * navigation destination (Players screen, a widget tap), where nothing else pads it.
     */
    topBarInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
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
    // What the tag could be written with, captured when the action is tapped. Reading it
    // live let the track advance underneath an open sheet, which silently retargeted the
    // tag, and left the sheet to reopen by itself on the next track that had one.
    var nfcWriteChoices by remember { mutableStateOf<List<NfcWriteChoice>>(emptyList()) }
    // Offered only where there is a chip to write with.
    val nfcContext = androidx.compose.ui.platform.LocalContext.current
    val hasNfc = remember(nfcContext) {
        nfcContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
    }
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
    // Follows the track the server has confirmed, and only once it has stayed on
    // it. Skipping through tracks would otherwise repaint the whole screen for
    // each one on the way, and a colour that is about to be replaced is not
    // worth showing. Colouring from the cover the swipe was carrying had the
    // same fault: it started the change with the gesture, so every step of a
    // burst turned the background over.
    var settledArtwork by remember { mutableStateOf(imageUrl) }
    LaunchedEffect(imageUrl) {
        delay(BACKGROUND_SETTLE_DELAY_MS)
        settledArtwork = imageUrl
    }
    val dominantColor by extractDominantColor(settledArtwork, isDark)
    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        // Slow enough to read as the background settling rather than switching.
        // A whole screen changing colour needs longer than a control does.
        animationSpec = tween(durationMillis = BACKGROUND_COLOR_DURATION_MS, easing = FastOutSlowInEasing),
        label = "bg_color"
    )
    val gradientAlpha = if (isDark) 0.35f else 0.25f
    val gradient = Brush.verticalGradient(
        colors = listOf(animatedColor.copy(alpha = gradientAlpha), surfaceColor)
    )
    // Read from the settled cover, which is what the backdrop draws. Taking it from the
    // live url instead said there was artwork while the backdrop still had none, and the
    // screen sat on a bare scrim for the whole settle delay.
    val hasArtwork = !settledArtwork.isNullOrBlank()

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
                    windowInsets = topBarInsets
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
        ) {
            if (hasArtwork) {
                // The settled cover, not the live one, for the same reason the colour
                // uses it: skipping through tracks would turn the whole background over
                // for each one passed on the way.
                AlbumArtBackdrop(imageUrl = settledArtwork, isDark = isDark)
            } else {
                // No artwork to take the colour from, so fall back to the tint the
                // screen has always had rather than leaving a flat surface.
                Box(modifier = Modifier.fillMaxSize().background(gradient).grain(grainAlphaFor(isDark)))
            }
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
            onPlaylistsVisible = viewModel::onPlaylistsVisible,
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
            // The album, not the track: a tag is worth a sitting's worth of music, and a
            // single track would be a queue of one. A track whose album the server did not
            // name leaves nothing to write, so the option is not offered at all.
            // Two things can be meant here and neither is obviously the one: the playlist
            // or album the queue is playing from, which the server names in the queue's
            // source, and the album of the track itself. Both are offered when they differ.
            onWriteNfcTag = nfcWriteChoicesFor(queueState?.source, currentTrack, album)
                .takeIf { it.isNotEmpty() && hasNfc }
                ?.let { choices -> { nfcWriteChoices = choices } },
            onClick = {
                showPlayerMenu = false
                viewModel.toggleCurrentArtistBlocked()
            }
        )
    }

    if (nfcWriteChoices.isNotEmpty()) {
        NfcWriteSheet(
            choices = nfcWriteChoices,
            onDismiss = { nfcWriteChoices = emptyList() }
        )
    }

    if (showTransferSheet) {
        val otherPlayers = allPlayers.filter { it.available && it.playerId != player?.playerId }
            .sortedBy { it.displayName.lowercase() }
        ModalBottomSheet(
            onDismissRequest = { showTransferSheet = false },
            sheetState = SheetDefaults.sheetState(),
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
            val autoplayStates by viewModel.queueAutoplayStates.collectAsStateWithLifecycle()
            val crossfadeStates by viewModel.queueCrossfadeStates.collectAsStateWithLifecycle()
            val proximityConfig by viewModel.proximityConfig.collectAsStateWithLifecycle()

            net.asksakis.massdroidv2.ui.components.PlayerSettingsDialog(
                player = currentPlayer,
                initialAutoplayEnabled = autoplayStates[currentPlayer.playerId] ?: false,
                isSendspinPlayer = currentPlayer.provider == "sendspin",
                isLocalPlayer = ssClientId != null && currentPlayer.playerId == ssClientId,
                initialAudioFormat = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(audioFormat),
                initialSyncDelayMs = syncDelayMs,
                onLoadConfig = { viewModel.getPlayerConfig(it) },
                onSave = { id, values -> viewModel.savePlayerConfig(id, values) },
                onAutoplayEnabledChanged = { viewModel.setAutoplayEnabled(currentPlayer.playerId, it) },
                onLoadQueueSettings = { queueId -> viewModel.getQueueSettings(queueId) },
                onAutoplayChanged = { mode, playlistUri ->
                    viewModel.setAutoplayConfig(currentPlayer.playerId, mode, playlistUri)
                },
                initialCrossfadeEnabled = crossfadeStates[currentPlayer.playerId],
                onCrossfadeEnabledChanged = {
                    viewModel.setCrossfadeEnabled(currentPlayer.playerId, it)
                },
                onQueueConfigChanged = { key, value ->
                    viewModel.setQueueConfigValue(currentPlayer.playerId, key, value)
                },
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
                rooms = proximityConfig.rooms,
                onAssignRoom = { roomId ->
                    viewModel.assignPlayerToRoom(roomId, currentPlayer)
                },
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
            .padding(paddingValues),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.5f))

        // The only child that runs the full width of the screen. A swipe has to bring a
        // cover in from an edge and take one out at an edge, and the surface this is
        // clipped to is what decides where that edge is. Everything below is inset
        // instead, which is where the screen's margin moved to.
        SwipeableAlbumArt(
            imageUrl = imageUrl,
            previousImageUrl = previousImageUrl,
            nextImageUrl = nextImageUrl,
            onNext = { if (controlsEnabled) viewModel.next() },
            onPrevious = { if (controlsEnabled) viewModel.previousTrack() },
            canSwipePrevious = controlsEnabled && (queueState?.currentIndex ?: 0) > 0,
            canSwipeNext = controlsEnabled,
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        )

        Spacer(modifier = Modifier.weight(0.5f))

        // Everything under the artwork carries the screen margin that the column
        // used to apply to all of its children, the artwork included.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
    val infoColumnWidth = (LocalConfiguration.current.screenWidthDp.dp * INFO_COLUMN_SHARE)
        .coerceIn(INFO_COLUMN_MIN, INFO_COLUMN_MAX)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
    // Each column takes what it needs and the pair is centred, rather than the two sharing
    // the row out between them. The cover's column is the width its surface will use at this
    // height, so no leftover sits inside it pushing the controls towards the edge.
    val coverColumnWidth = landscapeArtSurfaceWidth(maxHeight - COVER_COLUMN_VERTICAL_INSET) +
        COVER_COLUMN_HORIZONTAL_INSET
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: close/name overlay + centered album art.
        // The cover is sized by the width this column gets, so the split is what decides how
        // big it is. The right hand column holds the track, the seek bar and the transport,
        // which need a fixed amount of room rather than a share of it, so widening this one
        // takes from slack rather than from anything that has to fit.
        Box(
            modifier = Modifier
                .width(coverColumnWidth)
                .fillMaxHeight()
                // No inset at the top: the close button and the player name are the first
                // thing under the status bar, and the row's own 40dp button already centres
                // the name well clear of it. With 8dp here as well the name sat 29dp below
                // the status bar and read as hanging in the middle of nothing.
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
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

            // Album art (true center). The inset is what is left for the close button and
            // the player name above it, so it stays only as deep as that row needs.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                SwipeableAlbumArt(
                    imageUrl = imageUrl,
                    previousImageUrl = previousImageUrl,
                    nextImageUrl = nextImageUrl,
                    onNext = { if (controlsEnabled) viewModel.next() },
                    onPrevious = { if (controlsEnabled) viewModel.previousTrack() },
                    canSwipePrevious = controlsEnabled && (queueState?.currentIndex ?: 0) > 0,
                    canSwipeNext = controlsEnabled,
                    onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    fillMaxWidth = false
                )
            }
        }

        // Right: track info + controls, sized to what they need rather than to a share of
        // the screen. A fixed share is the wrong tool here: this column holds a fixed set of
        // controls while the cover beside it can use any width at all, so a share that leaves
        // the cover room on a wide phone squeezes the controls on a narrow one. At the split
        // this replaced, a 640dp landscape screen gave this column 266dp against the 280 its
        // icon row needs. It now takes its share up to a ceiling and never below that floor,
        // and the cover column takes whatever is left.
        Box(
            modifier = Modifier
                .width(infoColumnWidth)
                .fillMaxHeight()
                .padding(start = 16.dp, end = 8.dp)
        ) {
            // Menu overlay top-right (doesn't affect centering). The same 40dp as the close
            // button opposite it, so the two sit on one line: at 36dp its centre landed 3dp
            // above the player name beside it, which reads as a misalignment across the top
            // of the screen. The portrait top bar uses 40dp for both as well.
            MdIconButton(
                onClick = onShowPlayerMenu,
                modifier = Modifier.size(40.dp).align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Player options", modifier = Modifier.size(22.dp))
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
}

/**
 * The album art, blurred, filling the screen behind the player.
 *
 * Two things blur it, and the second one is why it looks smooth.
 *
 * The artwork is decoded at [BACKDROP_SOURCE_PX] and stretched to the screen, which
 * softens it but leaves the faceted diamonds that bilinear upscaling always produces.
 * [BlurTransformation] removes those, and because it works on an image that carries no
 * detail to begin with, a small radius reads as a long, even falloff rather than a
 * smeared photograph.
 *
 * The blur happens once, at decode. `Modifier.blur` was tried first and measured a
 * 49% jank rate on a 120 Hz phone, almost all of it in slow draw commands, because it
 * re-blurs the whole screen every frame for an image that only changes with the track.
 *
 * The scrim above it is what keeps the text readable. It is deliberately heavier at
 * the bottom, where the controls and the track title sit, than at the top.
 */
/**
 * The album art, blurred, filling the screen behind the player.
 *
 * Two things blur it, and the second one is why it looks smooth.
 *
 * The artwork is decoded at [BACKDROP_SOURCE_PX] and stretched to the screen, which
 * softens it but leaves the faceted diamonds that bilinear upscaling always produces.
 * [BlurTransformation] removes those, and because it works on an image that carries no
 * detail to begin with, a small radius reads as a long, even falloff rather than a
 * smeared photograph.
 *
 * The blur happens once, at decode. `Modifier.blur` was tried first and measured a
 * 49% jank rate on a 120 Hz phone, almost all of it in slow draw commands, because it
 * re-blurs the whole screen every frame for an image that only changes with the track.
 *
 * The scrim above it is what keeps the text readable. It is deliberately heavier at
 * the bottom, where the controls and the track title sit, than at the top.
 */
@Composable
private fun AlbumArtBackdrop(imageUrl: String?, isDark: Boolean) {
    val context = LocalContext.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    // Lighter in the light theme: the same ring that frames a dark screen reads as
    // dirt on a bright one.
    val vignetteStrength = if (isDark) 0.55f else 0.28f
    val scrim = Brush.verticalGradient(
        colors = listOf(
            surfaceColor.copy(alpha = if (isDark) 0.30f else 0.45f),
            surfaceColor.copy(alpha = if (isDark) 0.62f else 0.72f),
            surfaceColor.copy(alpha = if (isDark) 0.88f else 0.92f)
        )
    )
    // Two layers that both stay laid out, with only the hidden one ever given a new url.
    // One AsyncImage handed a new url drops the picture it is showing and loads the new
    // one from nothing, so its own crossfade fades in over the bare surface: the
    // background went out and came back instead of turning over. Here the picture
    // leaving is still on screen underneath while the one arriving fades in above it.
    val slotUrls = remember { mutableStateListOf(imageUrl, null) }
    // The upper layer's opacity, and the whole of the crossfade: the lower layer is
    // always opaque, so fading the upper one to 1 reveals it and fading it to 0 gives
    // the lower one back. One value drives both directions.
    val upperAlpha = remember { Animatable(0f) }

    val lowerPainter = rememberAsyncImagePainter(
        model = remember(slotUrls[0]) { backdropRequest(context, slotUrls[0]) }
    )
    val upperPainter = rememberAsyncImagePainter(
        model = remember(slotUrls[1]) { backdropRequest(context, slotUrls[1]) }
    )

    LaunchedEffect(imageUrl) {
        // Which layer is showing is read from the opacity rather than tracked beside it. A
        // fade cancelled halfway used to leave a separate "front slot" claiming the layer
        // it never reached, and the next change then aimed at the layer already on screen
        // and never moved the opacity again, leaving the two albums blended for good.
        val front = if (upperAlpha.value >= 0.5f) 1 else 0
        if (imageUrl == slotUrls[front]) return@LaunchedEffect
        val target = 1 - front
        slotUrls[target] = imageUrl
        // Wait for the arriving picture before fading to it, because fading to a layer
        // that has not decoded yet dissolves the old one into the bare surface and then
        // pops. Bounded, so a picture that never arrives still hands the background over
        // rather than leaving it on the album before last.
        //
        // The wait has to name the picture. A slot that already carries one still reports
        // success for it until the new request replaces it, so a bare check for success
        // returned at once from the second album onward and the fade ran a full second
        // back towards the album before last.
        val arriving = if (target == 1) upperPainter else lowerPainter
        withTimeoutOrNull(BACKDROP_LOAD_WAIT_MS) {
            snapshotFlow { arriving.state }.first { state ->
                state is AsyncImagePainter.State.Success &&
                    state.result.request.data == imageUrl
            }
        }
        upperAlpha.animateTo(
            targetValue = if (target == 1) 1f else 0f,
            animationSpec = tween(BACKDROP_FADE_MS, easing = FastOutSlowInEasing)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = lowerPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = upperPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Read in the graphics phase, so the fade costs no recomposition.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = upperAlpha.value }
        )
        Box(modifier = Modifier.fillMaxSize().background(scrim))
        // A second scrim over the top strip alone. The one above is lightest at the top,
        // because that is where the artwork should show through, and the player name and
        // the options button sit in exactly that strip: over a pale cover they were left
        // reading dark text on a bright background. This pulls that strip back towards the
        // surface colour, which is the colour the text is drawn against everywhere else, so
        // it works in both themes rather than only darkening.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            surfaceColor.copy(alpha = if (isDark) 0.50f else 0.60f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(modifier = Modifier.fillMaxSize().drawWithCache {
            // Deliberate vignette. The blur already darkens the rim on its own, because
            // it samples past the edge of the artwork and finds nothing there, but that
            // is a property of one GPU on one Android version rather than something the
            // platform promises, and below API 31 there is no blur and so no rim at all.
            // Drawing it explicitly makes every device show the same picture.
            val radial = Brush.radialGradient(
                colorStops = arrayOf(
                    VIGNETTE_CLEAR_STOP to Color.Transparent,
                    1f to Color.Black.copy(alpha = vignetteStrength)
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                // Reach past the corners, otherwise the gradient ends mid-edge and the
                // corners read as four dark spots instead of one ring.
                radius = size.maxDimension * VIGNETTE_RADIUS_SCALE
            )
            onDrawBehind { drawRect(radial) }
        })
        // Last, so the grain sits over the blur, the scrim and the vignette alike: it is
        // the finish on the whole background rather than a layer inside it.
        Box(modifier = Modifier.fillMaxSize().grain(grainAlphaFor(isDark)))
    }
}

/**
 * How strongly the background carries its grain.
 *
 * Lighter in the light theme for the same reason the vignette is: the texture that gives a
 * dark screen its matte finish reads as dust on a bright one.
 */
private fun grainAlphaFor(isDark: Boolean): Float = if (isDark) 0.065f else 0.045f

/**
 * Decode size of the backdrop artwork. Small enough that stretching it to a phone
 * screen leaves no detail to distract from the text, large enough to keep the
 * colours and their rough arrangement.
 */
private const val BACKDROP_SOURCE_PX = 48

/**
 * Blur radius in SOURCE pixels, so it scales with [BACKDROP_SOURCE_PX] rather than with
 * the screen. Six of forty-eight pixels is a wide blur once stretched.
 */
private const val BACKDROP_BLUR_PASSES = 6

/**
 * The landscape split between the cover and the controls beside it.
 *
 * [INFO_COLUMN_SHARE] is what the controls take on a screen wide enough for both.
 * [INFO_COLUMN_MIN] is the width its icon row needs, measured at 275dp on the device with a
 * little over for the padding, below which the icons crowd. [INFO_COLUMN_MAX] stops a tablet
 * from spreading the controls across half a metre when the cover could use that width, where
 * the cover stops growing at the height of the screen anyway.
 */
/** What the cover's column spends on padding, so its width can be worked out in advance. */
private val COVER_COLUMN_HORIZONTAL_INSET = 16.dp

/** What the cover's column spends above and below the surface, for the same reason. */
private val COVER_COLUMN_VERTICAL_INSET = 20.dp

private const val INFO_COLUMN_SHARE = 0.45f
private val INFO_COLUMN_MIN = 280.dp
private val INFO_COLUMN_MAX = 360.dp

/**
 * How far down the extra scrim behind the player name and the options button reaches.
 *
 * It covers the status bar and the 48dp top bar under it with room to fade out, so the text
 * sits on the strongest part and nothing below the bar shows an edge where it ends.
 */
private val TOP_SCRIM_HEIGHT = 140.dp

/** Where the vignette starts to darken. Below this the backdrop is untouched. */
private const val VIGNETTE_CLEAR_STOP = 0.55f

/** Multiplier on the longest edge, so the gradient reaches beyond the corners. */
private const val VIGNETTE_RADIUS_SCALE = 0.78f

/** Long enough to read as the artwork changing, short enough not to lag the track. */
/**
 * How long the background takes to turn from one album to the next. It matches the colour
 * fade above it, so the picture and the tint it carries move together.
 */
private const val BACKDROP_FADE_MS = 900

/**
 * How long to wait for the arriving picture before fading to it anyway. The artwork itself
 * has already been fetched for the cover by the time this runs, so this only covers the
 * decode of the small blurred copy.
 */
private const val BACKDROP_LOAD_WAIT_MS = 1500L

/**
 * The backdrop's picture request. Built here rather than inline so both layers ask for
 * exactly the same thing and share one cache entry per album.
 */
private fun backdropRequest(context: android.content.Context, url: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(BACKDROP_SOURCE_PX)
        .memoryCacheKey("backdrop_$url")
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .transformations(BlurTransformation(BACKDROP_BLUR_PASSES))
        .build()

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

/**
 * How long the background takes to settle on a new track's colour. The whole
 * screen is changing, so it needs longer than a control would; at 320 ms the
 * change read as a switch rather than as a fade.
 */
private const val BACKGROUND_COLOR_DURATION_MS = 900

/**
 * How long a confirmed track has to stay current before its colour is taken up.
 * Tracks passed through on the way to the one the user wants are confirmed by
 * the server too, and repainting the screen for each of them is noise. Measured
 * gaps between confirmations while skipping were 763 to 3591 ms, so this folds
 * the closest of them without holding back an ordinary track change.
 */
private const val BACKGROUND_SETTLE_DELAY_MS = 800L

/**
 * What a tag written from the player could start.
 *
 * The queue's source comes first when there is one, because it is what the listener put on
 * and the one thing the tracks on screen cannot tell them. The current track's album
 * follows, and is left out when it is the same container.
 */
private fun nfcWriteChoicesFor(
    source: QueueSource?,
    currentTrack: Track?,
    albumName: String
): List<NfcWriteChoice> {
    val fromSource = source
        ?.takeIf { it.uri.isNotBlank() && it.name.isNotBlank() }
        ?.let {
            NfcWriteChoice(
                action = NfcTagAction.PlayMedia(it.uri),
                title = it.name,
                detail = sourceKindLabel(it.mediaType),
                tagLabel = it.name
            )
        }
    val fromTrack = currentTrack?.albumUri
        ?.takeIf { it.isNotBlank() && it != fromSource?.mediaUriOrNull() }
        ?.let { uri ->
            val name = currentTrack.albumName.takeIf { it.isNotBlank() } ?: albumName
            NfcWriteChoice(
                action = NfcTagAction.PlayMedia(uri),
                title = name,
                detail = "Album of the current track",
                tagLabel = name
            )
        }
    return listOfNotNull(fromSource, fromTrack)
}

private fun sourceKindLabel(mediaType: MediaType): String = when (mediaType) {
    MediaType.PLAYLIST -> "Playlist, what is playing now"
    MediaType.ALBUM -> "Album, what is playing now"
    MediaType.ARTIST -> "Artist, what is playing now"
    MediaType.RADIO -> "Radio, what is playing now"
    MediaType.PODCAST -> "Podcast, what is playing now"
    MediaType.AUDIOBOOK -> "Audiobook, what is playing now"
    else -> "What is playing now"
}

/** The uri a choice plays, when it plays one. */
private fun NfcWriteChoice.mediaUriOrNull(): String? =
    (action as? NfcTagAction.PlayMedia)?.uri
