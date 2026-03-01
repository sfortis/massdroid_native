package net.asksakis.massdroidv2.ui.screens.nowplaying

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.ui.components.VolumeSlider

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

    val currentTrack = queueState?.currentItem?.track
    val title = currentTrack?.name ?: player?.currentMedia?.title ?: "No track"
    val artist = currentTrack?.artistNames ?: player?.currentMedia?.artist ?: ""
    val album = currentTrack?.albumName ?: player?.currentMedia?.album ?: ""
    val imageUrl = currentTrack?.imageUrl ?: queueState?.currentItem?.imageUrl
        ?: player?.currentMedia?.imageUrl
    val duration = currentTrack?.duration ?: queueState?.currentItem?.duration
        ?: player?.currentMedia?.duration ?: 0.0
    val isPlaying = player?.state == PlaybackState.PLAYING

    Scaffold(
        topBar = {
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
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Artwork
            AsyncImage(
                model = imageUrl,
                contentDescription = "Album art",
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Track info + favorite
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 48.dp)
                        .basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp)
                )
                IconButton(
                    onClick = { viewModel.toggleFavorite() },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        if (currentTrack?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Toggle favorite",
                        tint = if (currentTrack?.favorite == true) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val artistClickable = currentTrack?.artistItemId != null && currentTrack.artistProvider != null
            Text(
                text = artist,
                style = MaterialTheme.typography.titleMedium,
                color = if (artistClickable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (artistClickable) Modifier.clickable {
                    onNavigateToArtist(currentTrack.artistItemId!!, currentTrack.artistProvider!!, artist)
                } else Modifier
            )
            if (album.isNotBlank()) {
                val albumClickable = currentTrack?.albumItemId != null && currentTrack.albumProvider != null
                Text(
                    text = album,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (albumClickable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (albumClickable) Modifier.clickable {
                        onNavigateToAlbum(currentTrack.albumItemId!!, currentTrack.albumProvider!!, album)
                    } else Modifier
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Seek bar
            SeekBar(
                elapsed = elapsedTime,
                duration = duration,
                onSeek = { viewModel.seek(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Transport controls
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

                IconButton(
                    onClick = { viewModel.previous() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp))
                }

                FilledIconButton(
                    onClick = { viewModel.playPause() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = { viewModel.next() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp))
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

            Spacer(modifier = Modifier.height(16.dp))

            // Volume
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
