package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RepeatMode

@Composable
fun TvNowPlayingScreen(
    onOpenQueue: () -> Unit = {},
    viewModel: TvNowPlayingViewModel = hiltViewModel(),
    settingsViewModel: TvSettingsViewModel = hiltViewModel()
) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val elapsed by viewModel.elapsed.collectAsStateWithLifecycle()
    val queue by viewModel.queueState.collectAsStateWithLifecycle()
    val outputDelay by settingsViewModel.outputDelay.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    val media = player?.currentMedia
    val duration = media?.duration ?: 0.0
    val playing = player?.state == PlaybackState.PLAYING
    val shuffleOn = queue?.shuffleEnabled == true
    val repeatMode = queue?.repeatMode ?: RepeatMode.OFF

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                AsyncImage(
                    model = media?.imageUrl,
                    contentDescription = media?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(360.dp)
                )

                Column(modifier = Modifier.width(720.dp)) {
                    Text(
                        player?.displayName ?: "Player",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        media?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        media?.artist.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(28.dp))
                    SeekBar(
                        elapsed = elapsed,
                        duration = duration,
                        enabled = duration > 0.0,
                        onSeekBy = viewModel::seekBy
                    )

                    Spacer(Modifier.height(24.dp))
                    val playFocus = remember { FocusRequester() }
                    // Row 1: transport (centered) with shuffle + repeat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportIcon(
                            Icons.Filled.Shuffle,
                            "Shuffle",
                            active = shuffleOn
                        ) { viewModel.toggleShuffle() }
                        TransportIcon(Icons.Filled.SkipPrevious, "Previous") { viewModel.previous() }
                        TransportIcon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause" else "Play",
                            modifier = Modifier.focusRequester(playFocus)
                        ) { viewModel.playPause() }
                        TransportIcon(Icons.Filled.SkipNext, "Next") { viewModel.next() }
                        TransportIcon(
                            if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repeat",
                            active = repeatMode != RepeatMode.OFF
                        ) { viewModel.cycleRepeat() }
                    }
                    Spacer(Modifier.height(16.dp))
                    // Row 2: volume (centered)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportIcon(Icons.Filled.VolumeDown, "Volume down") { viewModel.volumeDown() }
                        Text(
                            "${player?.volumeLevel ?: 0}%",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TransportIcon(Icons.Filled.VolumeUp, "Volume up") { viewModel.volumeUp() }
                    }

                    if (showOptions) {
                        Spacer(Modifier.height(24.dp))
                        OutputDelayControl(outputDelay = outputDelay, onChange = settingsViewModel::setOutputDelay)
                    }
                    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
                }
            }

            // Top-right actions: the active queue, then audio options (3-dots),
            // like the phone's player settings entry point.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
                IconButton(onClick = { showOptions = !showOptions }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Audio options")
                }
            }
        }
    }
}

@Composable
private fun TransportIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    // Active toggles (shuffle on / repeat) get a filled primary container; the
    // icon inherits the IconButton's focus-aware content color (turns dark when
    // the button is focused), so nothing is hardcoded to a fixed brightness.
    val colors = if (active) {
        IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        IconButtonDefaults.colors()
    }
    IconButton(onClick = onClick, modifier = modifier, colors = colors) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun SeekBar(
    elapsed: Double,
    duration: Double,
    enabled: Boolean,
    onSeekBy: (Double) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0.0) (elapsed / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled)
                .onKeyEvent { e ->
                    if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_S); true }
                        Key.DirectionRight -> { onSeekBy(SEEK_STEP_S); true }
                        else -> false
                    }
                }
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val SEEK_STEP_S = 10.0

private fun formatTime(seconds: Double): String {
    val t = seconds.toInt().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
