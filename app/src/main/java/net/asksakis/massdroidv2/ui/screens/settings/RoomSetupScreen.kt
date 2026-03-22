package net.asksakis.massdroidv2.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sensors
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
import androidx.compose.material3.Scaffold
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (existingRoom != null) {
                CalibrationInfo(existingRoom)
                Spacer(modifier = Modifier.height(12.dp))
            }

            val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
            val isCalibrating = autoProgress != null

            androidx.compose.material3.OutlinedButton(
                onClick = {
                    val id = existingRoom?.id
                    if (id != null) {
                        viewModel.calibrateRoom(id) {}
                    } else {
                        // Save first, then calibrate
                        val player = selectedPlayer ?: return@OutlinedButton
                        if (roomName.isBlank()) return@OutlinedButton
                        val newId = java.util.UUID.randomUUID().toString()
                        viewModel.saveRoom(null, roomName.trim(), player.playerId, player.displayName)
                        // Need to get the saved room ID - use the latest config
                    }
                },
                enabled = !isCalibrating && (existingRoom != null || (roomName.isNotBlank() && selectedPlayer != null)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                if (isCalibrating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Calibrating ($autoProgress/${net.asksakis.massdroidv2.data.proximity.ProximityScanner.AUTO_FINGERPRINT_CYCLES})...")
                } else {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (existingRoom?.fingerprints?.isNotEmpty() == true) "Recalibrate" else "Calibrate")
                }
            }

            if (isCalibrating) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Walk around the room slowly",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp)
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
