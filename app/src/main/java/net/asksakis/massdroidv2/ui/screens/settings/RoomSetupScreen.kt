package net.asksakis.massdroidv2.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSetupScreen(
    roomId: String?,
    onBack: () -> Unit,
    viewModel: ProximityViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val existingRoom = config.rooms.find { it.id == roomId }
    var roomName by remember { mutableStateOf("") }
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    var initialized by remember { mutableStateOf(roomId == null) }

    LaunchedEffect(existingRoom, players) {
        if (!initialized && existingRoom != null) {
            roomName = existingRoom.name
            selectedPlayer = players.find { it.playerId == existingRoom.playerId }
            if (selectedPlayer != null || players.isEmpty()) initialized = true
        }
    }

    LaunchedEffect(players, existingRoom) {
        if (selectedPlayer == null && existingRoom != null) {
            selectedPlayer = players.find { it.playerId == existingRoom.playerId }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (existingRoom != null) "Edit Room" else "New Room") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val player = selectedPlayer
                            if (roomName.isBlank() || player == null) {
                                scope.launch { snackbarHostState.showSnackbar("Fill in room name and select a player") }
                                return@IconButton
                            }
                            viewModel.saveRoom(
                                roomId = existingRoom?.id,
                                name = roomName.trim(),
                                playerId = player.playerId,
                                playerName = player.displayName
                            )
                            onBack()
                        }
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                label = { Text("Room name") },
                placeholder = { Text("Living Room") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            PlayerDropdown(
                players = players.filter { it.available },
                selected = selectedPlayer,
                onSelect = { selectedPlayer = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (existingRoom != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Detection policy
                val currentPolicy = existingRoom.detectionPolicy
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Detection Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            net.asksakis.massdroidv2.data.proximity.DetectionPolicy.entries.forEach { policy ->
                                val selected = currentPolicy == policy
                                androidx.compose.material3.FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.updateRoomPolicy(existingRoom.id, policy) },
                                    label = { Text(policy.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) } } else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            when (currentPolicy) {
                                net.asksakis.massdroidv2.data.proximity.DetectionPolicy.STRICT ->
                                    "Best for multi-room homes with good BLE separation"
                                net.asksakis.massdroidv2.data.proximity.DetectionPolicy.NORMAL ->
                                    "Good for simpler spaces with weaker BLE coverage"
                                net.asksakis.massdroidv2.data.proximity.DetectionPolicy.RELAXED ->
                                    "Wi-Fi-first detection for spaces with poor BLE coverage"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentPolicy == net.asksakis.massdroidv2.data.proximity.DetectionPolicy.RELAXED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (currentPolicy == net.asksakis.massdroidv2.data.proximity.DetectionPolicy.RELAXED) {
                            Text(
                                "May increase false positives in multi-room environments",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                PlaybackConfigSection(
                    roomId = existingRoom.id,
                    playbackConfig = existingRoom.playbackConfig,
                    viewModel = viewModel
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (existingRoom != null) {
                CalibrationInfo(existingRoom)
                Spacer(modifier = Modifier.height(12.dp))
            }

            val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
            val isCalibrating = autoProgress != null

            androidx.compose.material3.OutlinedButton(
                onClick = {
                    val id = existingRoom?.id ?: return@OutlinedButton
                    viewModel.calibrateRoom(id) {}
                },
                enabled = !isCalibrating && existingRoom != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (existingRoom?.fingerprints?.isNotEmpty() == true) "Recalibrate" else "Calibrate")
            }

            if (isCalibrating) {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Sensors, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Calibrating")
                        }
                    },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Scanning $autoProgress/${net.asksakis.massdroidv2.data.proximity.ProximityScanner.AUTO_FINGERPRINT_CYCLES}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Walk to 2\u20133 spots in the room",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Keep phone in hand, avoid doorways",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {}
                )
            }

            if (existingRoom == null && !isCalibrating) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Save the room first, then calibrate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

        }
    }

    val calibrationError by viewModel.calibrationError.collectAsStateWithLifecycle()
    calibrationError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationError() },
            title = { Text("Calibration Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCalibrationError() }) { Text("OK") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackConfigSection(
    roomId: String,
    playbackConfig: net.asksakis.massdroidv2.data.proximity.RoomPlaybackConfig,
    viewModel: ProximityViewModel
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showPlaylistPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadPlaylists() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (playbackConfig.playlistUri != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Auto-Play",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Play automatically when entering this room",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Playlist selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPlaylistPicker = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Playlist", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        playbackConfig.playlistName ?: "None (resume queue)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (playbackConfig.playlistUri != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (playbackConfig.playlistUri != null) {
                    IconButton(onClick = {
                        viewModel.updateRoomPlayback(roomId, playbackConfig.copy(playlistUri = null, playlistName = null))
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val hasPlaylist = playbackConfig.playlistUri != null
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Shuffle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasPlaylist) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = hasPlaylist && playbackConfig.shuffle,
                    onCheckedChange = { viewModel.updateRoomPlayback(roomId, playbackConfig.copy(shuffle = it)) },
                    enabled = hasPlaylist
                )
            }
        }
    }

    // Volume section
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (playbackConfig.volumeEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Set Volume",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Switch(
                    checked = playbackConfig.volumeEnabled,
                    onCheckedChange = { viewModel.updateRoomPlayback(roomId, playbackConfig.copy(volumeEnabled = it)) }
                )
            }

            if (playbackConfig.volumeEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.VolumeDown, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = playbackConfig.volumeLevel.toFloat(),
                        onValueChange = {
                            viewModel.updateRoomPlayback(roomId, playbackConfig.copy(volumeLevel = it.toInt()))
                        },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.VolumeUp, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${playbackConfig.volumeLevel * 10}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Use current player volume",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Playlist picker dialog
    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = {
                Text("Select Playlist", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        // "None" option
                        item {
                            val noneSelected = playbackConfig.playlistUri == null
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateRoomPlayback(roomId, playbackConfig.copy(playlistUri = null, playlistName = null))
                                        showPlaylistPicker = false
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = noneSelected, onClick = null)
                                Text(
                                    "None (resume queue)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                        items(playlists.sortedBy { it.name.lowercase() }, key = { it.uri }) { playlist ->
                            val isSelected = playbackConfig.playlistUri == playlist.uri
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateRoomPlayback(roomId, playbackConfig.copy(
                                            playlistUri = playlist.uri,
                                            playlistName = playlist.name
                                        ))
                                        showPlaylistPicker = false
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistPicker = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CalibrationInfo(room: net.asksakis.massdroidv2.data.proximity.RoomConfig) {
    val hasFp = room.fingerprints.isNotEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Sensors,
                    contentDescription = null,
                    tint = if (hasFp) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Calibration Data",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!hasFp) {
                Text(
                    "Not calibrated. Tap Calibrate below to scan this room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${room.beaconProfiles.size} beacons, ${room.fingerprints.size} samples",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val (qLabel, qColor) = when (room.calibrationQuality) {
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.GOOD ->
                            "Good" to MaterialTheme.colorScheme.primary
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.WEAK ->
                            "Weak" to MaterialTheme.colorScheme.error
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.UNCALIBRATED ->
                            "N/A" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(qLabel, style = MaterialTheme.typography.labelSmall, color = qColor,
                        fontWeight = FontWeight.Bold)
                }

                if (room.calibrationQuality == net.asksakis.massdroidv2.data.proximity.CalibrationQuality.WEAK) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Try recalibrating, use Relaxed mode, or check for more stable devices nearby.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Top beacons by weight
                val topProfiles = room.beaconProfiles.sortedByDescending { it.weight }.take(5)
                topProfiles.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        Text(
                            "w=${String.format("%.2f", p.weight)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val capturedAt = room.fingerprints.maxOf { it.capturedAtMs }
                val ago = (System.currentTimeMillis() - capturedAt) / 60_000
                val timeText = when {
                    ago < 1 -> "just now"
                    ago < 60 -> "${ago}m ago"
                    ago < 1440 -> "${ago / 60}h ago"
                    else -> "${ago / 1440}d ago"
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Trained $timeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerDropdown(
    players: List<Player>,
    selected: Player?,
    onSelect: (Player) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.displayName ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Speaker") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            players.sortedBy { it.displayName.lowercase() }.forEach { player ->
                DropdownMenuItem(
                    text = { Text(player.displayName) },
                    onClick = {
                        onSelect(player)
                        expanded = false
                    }
                )
            }
        }
    }
}
