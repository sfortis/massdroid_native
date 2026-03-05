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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
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

    val currentTrack = queueState?.currentItem?.track
    val currentArtistUri = MediaIdentity.canonicalArtistKey(
        itemId = currentTrack?.artistItemId,
        uri = currentTrack?.artistUri
    )
    val artistBlocked = currentArtistUri?.let { it in blockedArtistUris } ?: false
    val canToggleArtistBlock = currentArtistUri != null
    var showPlayerMenu by remember { mutableStateOf(false) }
    val title = currentTrack?.name ?: player?.currentMedia?.title ?: "No track"
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist ?: ""
    val album = currentTrack?.albumName ?: player?.currentMedia?.album ?: ""
    val imageUrl = currentTrack?.imageUrl ?: queueState?.currentItem?.imageUrl
        ?: player?.currentMedia?.imageUrl
    val duration = currentTrack?.duration ?: queueState?.currentItem?.duration
        ?: player?.currentMedia?.duration ?: 0.0
    val isPlaying = player?.state == PlaybackState.PLAYING

    val isDark = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val dominantColor by extractDominantColor(imageUrl, isDark)
    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 800),
        label = "bg_color"
    )
    val gradientAlpha = if (isDark) 0.35f else 0.25f
    val gradient = Brush.verticalGradient(
        colors = listOf(animatedColor.copy(alpha = gradientAlpha), surfaceColor)
    )

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
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
                        IconButton(onClick = onNavigateToQueue) {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Default.QueueMusic, contentDescription = "Queue")
                        }
                        Box {
                            IconButton(onClick = { showPlayerMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Player options")
                            }
                            DropdownMenu(
                                expanded = showPlayerMenu,
                                onDismissRequest = { showPlayerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (artistBlocked) "Allow this Artist"
                                            else "Do Not Play this Artist"
                                        )
                                    },
                                    onClick = {
                                        showPlayerMenu = false
                                        if (canToggleArtistBlock) {
                                            viewModel.toggleCurrentArtistBlocked()
                                        }
                                    },
                                    enabled = canToggleArtistBlock
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
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
                    isPlaying = isPlaying,
                    currentTrack = currentTrack,
                    queueState = queueState,
                    elapsedTime = elapsedTime,
                    duration = duration,
                    player = player,
                    viewModel = viewModel,
                    artistBlocked = artistBlocked,
                    canToggleArtistBlock = canToggleArtistBlock,
                    onBack = onBack,
                    onNavigateToQueue = onNavigateToQueue,
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
                    isPlaying = isPlaying,
                    currentTrack = currentTrack,
                    queueState = queueState,
                    elapsedTime = elapsedTime,
                    duration = duration,
                    player = player,
                    viewModel = viewModel,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum
                )
            }
        }
    }
}

@Composable
private fun NowPlayingPortrait(
    paddingValues: PaddingValues,
    imageUrl: String?,
    title: String,
    artist: String,
    album: String,
    isPlaying: Boolean,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    elapsedTime: Double,
    duration: Double,
    player: net.asksakis.massdroidv2.domain.model.Player?,
    viewModel: NowPlayingViewModel,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit
) {
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
            onPrevious = { viewModel.previous() }
        )

        Spacer(modifier = Modifier.height(48.dp))

        TrackInfoSection(
            title = title,
            artist = artist,
            album = album,
            currentTrack = currentTrack,
            viewModel = viewModel,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum
        )

        Spacer(modifier = Modifier.height(16.dp))

        SeekBar(
            elapsed = elapsedTime,
            duration = duration,
            onSeek = { viewModel.seek(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        TransportControls(
            isPlaying = isPlaying,
            queueState = queueState,
            viewModel = viewModel
        )

        Spacer(modifier = Modifier.height(16.dp))

        VolumeSlider(
            volume = player?.volumeLevel ?: 0,
            isMuted = player?.volumeMuted ?: false,
            onVolumeChange = { viewModel.setVolume(it) },
            onMuteToggle = { viewModel.toggleMute() }
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
    isPlaying: Boolean,
    currentTrack: net.asksakis.massdroidv2.domain.model.Track?,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    elapsedTime: Double,
    duration: Double,
    player: net.asksakis.massdroidv2.domain.model.Player?,
    viewModel: NowPlayingViewModel,
    artistBlocked: Boolean,
    canToggleArtistBlock: Boolean,
    onBack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit
) {
    var showPlayerMenu by remember { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.Center
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateToQueue, modifier = Modifier.size(36.dp)) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.QueueMusic, contentDescription = "Queue", modifier = Modifier.size(20.dp))
                    }
                    Box {
                        IconButton(onClick = { showPlayerMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Player options", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showPlayerMenu,
                            onDismissRequest = { showPlayerMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (artistBlocked) "Allow this Artist"
                                        else "Do Not Play this Artist"
                                    )
                                },
                                onClick = {
                                    showPlayerMenu = false
                                    if (canToggleArtistBlock) {
                                        viewModel.toggleCurrentArtistBlocked()
                                    }
                                },
                                enabled = canToggleArtistBlock
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TrackInfoSection(
                title = title,
                artist = artist,
                album = album,
                currentTrack = currentTrack,
                viewModel = viewModel,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                compact = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            SeekBar(
                elapsed = elapsedTime,
                duration = duration,
                onSeek = { viewModel.seek(it) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            TransportControls(
                isPlaying = isPlaying,
                queueState = queueState,
                viewModel = viewModel,
                compact = true
            )

            Spacer(modifier = Modifier.height(4.dp))

            VolumeSlider(
                volume = player?.volumeLevel ?: 0,
                isMuted = player?.volumeMuted ?: false,
                onVolumeChange = { viewModel.setVolume(it) },
                onMuteToggle = { viewModel.toggleMute() }
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
    viewModel: NowPlayingViewModel,
    onNavigateToArtist: (String, String, String) -> Unit,
    onNavigateToAlbum: (String, String, String) -> Unit,
    compact: Boolean = false
) {
    val titleStyle = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall
    val artistStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
    val albumStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = titleStyle,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = if (compact) 44.dp else 56.dp)
                .basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp)
        )
        IconButton(
            onClick = { viewModel.toggleFavorite() },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                if (currentTrack?.favorite == true) Icons.Default.Favorite
                else Icons.Default.FavoriteBorder,
                contentDescription = "Toggle favorite",
                tint = if (currentTrack?.favorite == true) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
    val artistClickable = currentTrack?.artistItemId != null && currentTrack.artistProvider != null
    Text(
        text = artist,
        style = artistStyle,
        color = if (artistClickable) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (artistClickable) Modifier.clickable {
            onNavigateToArtist(currentTrack.artistItemId!!, currentTrack.artistProvider!!, artist)
        } else Modifier
    )
    if (album.isNotBlank()) {
        Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))
        val albumClickable = currentTrack?.albumItemId != null && currentTrack.albumProvider != null
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
private fun TransportControls(
    isPlaying: Boolean,
    queueState: net.asksakis.massdroidv2.domain.model.QueueState?,
    viewModel: NowPlayingViewModel,
    compact: Boolean = false
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
        IconButton(onClick = { viewModel.toggleShuffle() }) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (queueState?.shuffleEnabled == true)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = { viewModel.previous() }, modifier = Modifier.size(buttonSize)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(iconSize))
        }

        FilledIconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(playSize)) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(playIconSize)
            )
        }

        IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(buttonSize)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(iconSize))
        }

        IconButton(onClick = { viewModel.cycleRepeat() }) {
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
        Modifier.fillMaxHeight().aspectRatio(1f)
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
                                onNext()
                                offsetX = width
                                animateOffsetTo(0f, 170)
                            } else if (current > threshold) {
                                animateOffsetTo(width, 130)
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
    onSeek: (Double) -> Unit
) {
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
                onSeek(seekValue.toDouble())
                seekTarget = seekValue
                seeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(displayValue.toDouble()), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
