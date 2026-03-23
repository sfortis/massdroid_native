package net.asksakis.massdroidv2.ui.screens.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.ui.components.EqualizerBars
import net.asksakis.massdroidv2.ui.components.SoundWaveIcon
import net.asksakis.massdroidv2.ui.components.PlayerNameWithBadge
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.PlayerType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.SubcomposeAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    val selectedPlayer by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val suppressConnectionPrompt by viewModel.suppressConnectionPrompt.collectAsStateWithLifecycle()
    val sendspinClientId by viewModel.sendspinClientId.collectAsStateWithLifecycle(initialValue = null)
    val proximityConfig by viewModel.proximityConfig.collectAsStateWithLifecycle(
        initialValue = net.asksakis.massdroidv2.data.proximity.ProximityConfig()
    )
    val playerRoomMap = remember(proximityConfig) {
        proximityConfig.rooms.groupBy { it.playerId }.mapValues { (_, rooms) -> rooms.map { it.name } }
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isInitializing || connectionState is ConnectionState.Connecting || suppressConnectionPrompt) {
                ConnectingIndicator()
            } else when (connectionState) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    ConnectionPrompt(
                        state = connectionState,
                        onSettings = onNavigateToSettings,
                        onRetry = { viewModel.connectIfNeeded() }
                    )
                }
                is ConnectionState.Connecting -> { /* handled above */ }
                is ConnectionState.Connected -> {
                    var iconPickerPlayer by remember { mutableStateOf<Player?>(null) }
                    var queueMenuPlayer by remember { mutableStateOf<Player?>(null) }
                    var settingsPlayer by remember { mutableStateOf<Player?>(null) }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            players.filter { it.available }.sortedBy { it.displayName.lowercase() },
                            key = { it.playerId }
                        ) { player ->
                            PlayerListItem(
                                player = player,
                                isSelected = player.playerId == selectedPlayer?.playerId,
                                isLocalPlayer = sendspinClientId != null && player.playerId == sendspinClientId,
                                roomNames = playerRoomMap[player.playerId] ?: emptyList(),
                                onClick = { viewModel.selectPlayer(player) },
                                onIconLongPress = { iconPickerPlayer = player },
                                onQueueMenuClick = { queueMenuPlayer = player },
                                onVolumeChange = { volume ->
                                    viewModel.setVolume(player.playerId, volume)
                                }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    iconPickerPlayer?.let { player ->
                        IconPickerDialog(
                            playerName = player.displayName,
                            currentIcon = player.icon,
                            onIconSelected = { mdiName ->
                                viewModel.updatePlayerIcon(player.playerId, mdiName)
                                iconPickerPlayer = null
                            },
                            onDismiss = { iconPickerPlayer = null }
                        )
                    }

                    queueMenuPlayer?.let { player ->
                        PlayerQueueSheet(
                            player = player,
                            allPlayers = players.filter { it.available },
                            sendspinClientId = sendspinClientId,
                            playerRoomMap = playerRoomMap,
                            onPlayerSettings = {
                                settingsPlayer = player
                                queueMenuPlayer = null
                            },
                            onClearQueue = {
                                viewModel.clearQueue(player.playerId)
                                queueMenuPlayer = null
                            },
                            onTransferQueue = { targetId ->
                                viewModel.transferQueue(player.playerId, targetId)
                                queueMenuPlayer = null
                            },
                            onDismiss = { queueMenuPlayer = null }
                        )
                    }

                    settingsPlayer?.let { player ->
                        PlayerSettingsDialog(
                            player = player,
                            viewModel = viewModel,
                            onDismiss = { settingsPlayer = null }
                        )
                    }

                }
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerListItem(
    player: Player,
    isSelected: Boolean,
    isLocalPlayer: Boolean = false,
    roomNames: List<String> = emptyList(),
    onClick: () -> Unit,
    onIconLongPress: () -> Unit,
    onQueueMenuClick: () -> Unit,
    onVolumeChange: (Int) -> Unit
) {
    var volumeSliderValue by remember { mutableFloatStateOf(player.volumeLevel.toFloat()) }

    LaunchedEffect(player.volumeLevel) {
        volumeSliderValue = player.volumeLevel.toFloat()
    }

    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    ListItem(
        colors = ListItemDefaults.colors(containerColor = containerColor),
        headlineContent = {
            PlayerNameWithBadge(
                name = player.displayName,
                isLocalPlayer = isLocalPlayer,
                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else null
            )
        },
        supportingContent = {
            Column {
                val stateText = when (player.state) {
                    PlaybackState.PLAYING -> player.currentMedia?.let { "${it.title} - ${it.artist}" } ?: "Playing"
                    PlaybackState.PAUSED -> player.currentMedia?.let { "${it.title} - ${it.artist}" } ?: "Paused"
                    PlaybackState.IDLE -> "Idle"
                }
                Text(
                    text = stateText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = volumeSliderValue,
                        onValueChange = { volumeSliderValue = it },
                        onValueChangeFinished = { onVolumeChange(volumeSliderValue.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp),
                        thumb = {},
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                drawStopIndicator = null,
                                thumbTrackGapSize = 0.dp
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${volumeSliderValue.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (roomNames.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        roomNames.forEach { room ->
                            Text(
                                text = room,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        leadingContent = {
            val iconTint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            SoundWaveIcon(
                isPlaying = player.state == PlaybackState.PLAYING,
                waveColor = iconTint,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .combinedClickable(
                    onClick = {},
                    onLongClick = onIconLongPress
                )
            ) {
                PlayerIcon(
                    player = player,
                    modifier = Modifier.size(40.dp),
                    tint = iconTint
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onQueueMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Queue options",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerQueueSheet(
    player: Player,
    allPlayers: List<Player>,
    sendspinClientId: String?,
    playerRoomMap: Map<String, List<String>> = emptyMap(),
    onPlayerSettings: () -> Unit,
    onClearQueue: () -> Unit,
    onTransferQueue: (targetId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showTransferList by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val otherPlayers = remember(allPlayers, player.playerId) {
        allPlayers.filter { it.playerId != player.playerId }.sortedBy { it.displayName.lowercase() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column {
                SheetDefaults.HeaderTitle(
                    text = player.displayName,
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            }
            if (!showTransferList) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Player Settings") },
                    leadingContent = {
                        Icon(Icons.Default.Tune, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onPlayerSettings)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (otherPlayers.isNotEmpty()) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Transfer Queue") },
                        leadingContent = {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable { showTransferList = true }
                    )
                }
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Clear Queue") },
                    leadingContent = {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onClearQueue)
                )
            } else {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = {
                        Text("Transfer queue to", style = MaterialTheme.typography.labelMedium)
                    },
                    leadingContent = {
                        IconButton(onClick = { showTransferList = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                otherPlayers.forEach { target ->
                    val isPlaying = target.state == PlaybackState.PLAYING
                    val iconTint = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = {
                            PlayerNameWithBadge(
                                name = target.displayName,
                                isLocalPlayer = sendspinClientId != null && target.playerId == sendspinClientId
                            )
                        },
                        leadingContent = {
                            SoundWaveIcon(
                                isPlaying = isPlaying,
                                waveColor = iconTint
                            ) {
                                PlayerIcon(
                                    player = target,
                                    modifier = Modifier.size(32.dp),
                                    tint = iconTint
                                )
                            }
                        },
                        modifier = Modifier.clickable { onTransferQueue(target.playerId) }
                    )
                }
            }
        }
    }
}

private data class IconOption(val mdiName: String, val label: String, val icon: ImageVector)

private val iconOptions = listOf(
    IconOption("mdi-speaker", "Speaker", Icons.Default.Speaker),
    IconOption("mdi-speaker-multiple", "Group", Icons.Default.SpeakerGroup),
    IconOption("mdi-cast", "Cast", Icons.Default.Cast),
    IconOption("mdi-cast-connected", "Cast Connected", Icons.Default.CastConnected),
    IconOption("mdi-television", "TV", Icons.Default.Tv),
    IconOption("mdi-cellphone", "Phone", Icons.Default.PhoneAndroid),
    IconOption("mdi-laptop", "Laptop", Icons.Default.Laptop),
    IconOption("mdi-radio", "Radio", Icons.Default.Radio),
    IconOption("mdi-headphones", "Headphones", Icons.Default.Headphones),
    IconOption("mdi-bluetooth", "Bluetooth", Icons.Default.Bluetooth),
    IconOption("mdi-music-note", "Music", Icons.Default.MusicNote),
    IconOption("mdi-monitor", "Monitor", Icons.Default.Monitor)
)

@Composable
private fun IconPickerDialog(
    playerName: String,
    currentIcon: String?,
    onIconSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentNormalized = currentIcon?.replace(":", "-")?.lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playerName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(iconOptions) { option ->
                    val isCurrentIcon = currentNormalized == option.mdiName
                    val containerColor = if (isCurrentIcon)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                    val contentColor = if (isCurrentIcon)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant

                    Surface(
                        onClick = { onIconSelected(option.mdiName) },
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = option.label,
                                modifier = Modifier.size(32.dp),
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PlayerSettingsDialog(
    player: Player,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit
) {
    var config by remember { mutableStateOf<PlayerConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(player.displayName) }
    var crossfadeMode by remember { mutableStateOf(CrossfadeMode.DISABLED) }
    var volumeNormalization by remember { mutableStateOf(false) }
    var dontStopTheMusic by remember { mutableStateOf(false) }
    val initialDstm = remember { viewModel.queueState.value?.dontStopTheMusicEnabled ?: false }

    LaunchedEffect(player.playerId) {
        dontStopTheMusic = initialDstm
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

                    // Crossfade
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

                    // Volume normalization
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

                    // Don't stop the music
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Don't stop the music")
                            Text(
                                "Auto-fill queue when it runs out",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dontStopTheMusic,
                            onCheckedChange = { dontStopTheMusic = it }
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
                    if (dontStopTheMusic != initialDstm) {
                        viewModel.setDontStopTheMusic(player.playerId, dontStopTheMusic)
                    }
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
private fun ConnectingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EqualizerBars(
                modifier = Modifier.height(48.dp),
                barWidth = 8.dp,
                spacing = 6.dp,
                bpm = 130
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connecting...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// EqualizerBars moved to net.asksakis.massdroidv2.ui.components.EqualizerBars

@Composable
private fun ConnectionPrompt(
    state: ConnectionState,
    onSettings: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (state) {
                is ConnectionState.Error -> "Connection error: ${state.message}"
                else -> "Not connected to Music Assistant"
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSettings) {
            Text("Configure Server")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
internal fun PlayerIcon(
    player: Player,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val iconValue = player.icon
    if (iconValue != null && (iconValue.startsWith("http://") || iconValue.startsWith("https://"))) {
        SubcomposeAsyncImage(
            model = iconValue,
            contentDescription = null,
            modifier = modifier.clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Fit,
            error = {
                val fallback = typeBasedIcon(player.type)
                Icon(imageVector = fallback, contentDescription = null, modifier = modifier, tint = tint)
            }
        )
    } else {
        val imageVector = mapMdiIcon(iconValue) ?: typeBasedIcon(player.type)
        Icon(imageVector = imageVector, contentDescription = null, modifier = modifier, tint = tint)
    }
}

private fun typeBasedIcon(type: PlayerType): ImageVector = when (type) {
    PlayerType.GROUP -> Icons.Default.SpeakerGroup
    PlayerType.STEREO_PAIR -> Icons.Default.Speaker
    PlayerType.PLAYER -> Icons.Default.Speaker
}

private fun mapMdiIcon(mdiName: String?): ImageVector? {
    if (mdiName == null) return null
    val normalized = mdiName.replace(":", "-").lowercase()
    return when {
        normalized == "mdi-speaker-group" || normalized == "mdi-speaker-multiple" -> Icons.Default.SpeakerGroup
        normalized == "mdi-speaker" -> Icons.Default.Speaker
        normalized == "mdi-cast" -> Icons.Default.Cast
        normalized == "mdi-cast-connected" -> Icons.Default.CastConnected
        normalized == "mdi-television" || normalized == "mdi-tv" -> Icons.Default.Tv
        normalized == "mdi-cellphone" || normalized == "mdi-phone" || normalized == "mdi-cellphone-sound" -> Icons.Default.PhoneAndroid
        normalized == "mdi-laptop" -> Icons.Default.Laptop
        normalized == "mdi-radio" -> Icons.Default.Radio
        normalized == "mdi-headphones" -> Icons.Default.Headphones
        normalized == "mdi-bluetooth" || normalized == "mdi-bluetooth-audio" -> Icons.Default.Bluetooth
        normalized == "mdi-music" || normalized == "mdi-music-note" -> Icons.Default.MusicNote
        normalized == "mdi-monitor" -> Icons.Default.Monitor
        else -> null
    }
}
