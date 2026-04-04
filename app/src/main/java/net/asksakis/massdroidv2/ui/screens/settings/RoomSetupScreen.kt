package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.data.proximity.WifiMatchMode
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val missingStoredPlayerName = remember(existingRoom, selectedPlayer, players) {
        existingRoom?.takeIf {
            selectedPlayer == null && players.none { player -> player.playerId == it.playerId }
        }?.playerName
    }

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
                players = players,
                selected = selectedPlayer,
                missingSelectedLabel = missingStoredPlayerName,
                onSelect = { selectedPlayer = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (existingRoom != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                SectionHeader("Detection")

                val currentPolicy = existingRoom.detectionPolicy
                val wifiMode = existingRoom.wifiMatchMode
                val canUseWifi = existingRoom.connectedBssid != null || existingRoom.connectedSsid != null
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Wi-Fi Override",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = wifiMode == null,
                                onClick = { viewModel.updateRoomWifiMatchMode(existingRoom.id, null) },
                                label = { Text("Off") }
                            )
                            FilterChip(
                                selected = wifiMode == WifiMatchMode.BSSID,
                                onClick = { viewModel.updateRoomWifiMatchMode(existingRoom.id, WifiMatchMode.BSSID) },
                                label = { Text("BSSID") },
                                enabled = canUseWifi
                            )
                            FilterChip(
                                selected = wifiMode == WifiMatchMode.SSID,
                                onClick = { viewModel.updateRoomWifiMatchMode(existingRoom.id, WifiMatchMode.SSID) },
                                label = { Text("SSID") },
                                enabled = canUseWifi
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            when {
                                !canUseWifi -> "Calibrate once while connected to the room's Wi-Fi to enable."
                                wifiMode == WifiMatchMode.BSSID -> "Match exact access point (${existingRoom.connectedBssid}). Best for single-AP locations."
                                wifiMode == WifiMatchMode.SSID -> "Match network name (${existingRoom.connectedSsid}). Best for mesh/enterprise with multiple APs."
                                else -> "Use Wi-Fi instead of BLE beacons. Best for separate locations (home, office)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Detection Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (wifiMode != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
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
                                    onClick = { if (wifiMode == null) viewModel.updateRoomPolicy(existingRoom.id, policy) },
                                    enabled = wifiMode == null,
                                    label = { Text(policy.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) } } else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            if (wifiMode != null) {
                                "Detection mode is disabled while Wi-Fi AP override is enabled."
                            } else {
                                when (currentPolicy) {
                                    net.asksakis.massdroidv2.data.proximity.DetectionPolicy.STRICT ->
                                        "Prefer this for nearby rooms that need cleaner BLE separation."
                                    net.asksakis.massdroidv2.data.proximity.DetectionPolicy.NORMAL ->
                                        "Use this when BLE coverage is weaker and the room is harder to fingerprint."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                SectionHeader("Playback")

                PlaybackConfigSection(
                    roomId = existingRoom.id,
                    playbackConfig = existingRoom.playbackConfig,
                    viewModel = viewModel
                )

                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Stop on leave",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Pause playback after 10 minutes when you leave this room.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = existingRoom.stopOnLeave,
                            onCheckedChange = { viewModel.updateRoomStopOnLeave(existingRoom.id, it) }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (existingRoom != null) {
                val lastDetection by viewModel.lastDetection.collectAsStateWithLifecycle()
                SectionHeader("Calibration")
                CalibrationInfo(existingRoom, lastDetection)
                Spacer(modifier = Modifier.height(12.dp))
            }

            val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
            val isCalibrating = autoProgress != null
            val bleInspectionInProgress by viewModel.bleInspectionInProgress.collectAsStateWithLifecycle()
            val bleInspectionReport by viewModel.bleInspectionReport.collectAsStateWithLifecycle()
            val bleInspectionError by viewModel.bleInspectionError.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val id = existingRoom?.id ?: return@OutlinedButton
                        viewModel.calibrateRoom(id) {}
                    },
                    enabled = !isCalibrating && !bleInspectionInProgress && existingRoom != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (existingRoom?.fingerprints?.isNotEmpty() == true) "Recalibrate" else "Calibrate")
                }

                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val id = existingRoom?.id ?: return@OutlinedButton
                        viewModel.inspectRoomBle(id)
                    },
                    enabled = !isCalibrating && !bleInspectionInProgress && existingRoom != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Inspect BLE")
                }
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

            if (bleInspectionInProgress) {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Inspecting BLE")
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
                                "Scanning nearby BLE devices the same way Follow Me does.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {}
                )
            }

            bleInspectionError?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissBleInspectionError() },
                    title = { Text("BLE Inspection Failed") },
                    text = { Text(error) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissBleInspectionError() }) { Text("OK") }
                    }
                )
            }

            bleInspectionReport?.let { report ->
                BleInspectionDialog(
                    report = report,
                    onDismiss = { viewModel.dismissBleInspection() }
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

    val calibrationSummary by viewModel.calibrationSummary.collectAsStateWithLifecycle()
    calibrationSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationSummary() },
            title = { Text("Calibration Result") },
            text = { Text(summary) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCalibrationSummary() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun BleInspectionDialog(
    report: BleInspectionReport,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.985f)
                .padding(horizontal = 12.dp)
                .heightIn(max = 720.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "BLE Inspection",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Shows which BLE devices Follow Me would use as room anchors right now, and which ones it ignores.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${report.roomName}: ${report.totalDevices} devices in current scan",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        report.connectedBssid?.let { bssid ->
                            Text(
                                "Connected Wi-Fi AP: $bssid",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Used as room anchors now: ${report.usefulAnchors.size + report.stableCandidates.size}  ·  Ignored as room anchors now: ${report.privateAddressDevices.size + report.mobileDevices.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    BleInspectionSection(
                        title = "Used as room anchors now: room-matched",
                        supportingText = "These devices match this room's saved BLE anchors, so Follow Me can use them for detection right now.",
                        emptyMessage = "No current scan matches the room's top BLE anchors.",
                        items = report.usefulAnchors
                    )
                    BleInspectionSection(
                        title = "Used as room anchors now: stable candidates",
                        supportingText = "These look stable enough to be useful anchors, but they are not part of this room profile yet. Recalibrating can add them.",
                        emptyMessage = "No extra stable non-mobile beacons in this scan.",
                        items = report.stableCandidates
                    )
                    BleInspectionSection(
                        title = "Ignored as room anchors now: private-address class",
                        supportingText = "Follow Me currently ignores these as room anchors under the active calibration policy.",
                        emptyMessage = "No private-address BLE devices in this scan.",
                        items = report.privateAddressDevices
                    )
                    BleInspectionSection(
                        title = "Ignored as room anchors now: mobile devices",
                        supportingText = "Phones, watches, and similar personal devices are ignored because they move with people.",
                        emptyMessage = "No mobile devices classified in this scan.",
                        items = report.mobileDevices
                    )
                }
            }
        }
    }
}

@Composable
private fun BleInspectionSection(
    title: String,
    supportingText: String,
    emptyMessage: String,
    items: List<BleInspectionItem>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (items.isEmpty()) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEachIndexed { index, item ->
                    BleInspectionRow(item = item)
                    if (index != items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BleInspectionRow(item: BleInspectionItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                item.name ?: item.address,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "RSSI ${item.rssi}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            item.address,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val details = buildList {
            add(item.anchorType.name)
            add(item.addressType.name)
            if (item.category != net.asksakis.massdroidv2.data.proximity.ProximityScanner.DeviceCategory.UNKNOWN) {
                add(item.category.name)
            }
            item.profileWeight?.let { add("w=${String.format("%.2f", it)}") }
            item.profileDiscrimination?.let { add("disc=${String.format("%.1f", it)}") }
            item.profileMeanRssi?.let { add("mean=${it}") }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            details.forEach { detail ->
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                "Choose what starts when this room is confirmed. If no playlist is selected, the current queue moves here instead.",
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
                        playbackConfig.playlistName ?: "None (move current queue)",
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
                        Icons.AutoMirrored.Filled.VolumeUp,
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

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Apply this room volume when the room is confirmed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (playbackConfig.volumeEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null,
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
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null,
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
                Spacer(modifier = Modifier.height(8.dp))
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
                                    "None (move current queue)",
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
private fun CalibrationInfo(
    room: net.asksakis.massdroidv2.data.proximity.RoomConfig,
    lastDetection: net.asksakis.massdroidv2.data.proximity.RoomDetector.DetectionStatus? = null
) {
    val hasFp = room.fingerprints.isNotEmpty()

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
                    val (qLabel, qBg) = when (room.calibrationQuality) {
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.GOOD ->
                            "Good" to MaterialTheme.colorScheme.primary
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.WEAK ->
                            "Weak" to MaterialTheme.colorScheme.error
                        net.asksakis.massdroidv2.data.proximity.CalibrationQuality.UNCALIBRATED ->
                            "N/A" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        qLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(qBg, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (room.calibrationQuality == net.asksakis.massdroidv2.data.proximity.CalibrationQuality.WEAK) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This room may be harder to detect consistently. Recalibrate closer to stable devices and away from doorways.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (room.calibrationQuality == net.asksakis.massdroidv2.data.proximity.CalibrationQuality.GOOD) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This room has enough stable anchors for reliable detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // WiFi context
                if (room.connectedBssid != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "WiFi: ${room.connectedBssid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live detection status
                if (lastDetection != null && lastDetection.roomId == room.id) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val confPercent = (lastDetection.confidence * 100).toInt()
                    val confColor = when {
                        confPercent >= 80 -> MaterialTheme.colorScheme.primary
                        confPercent >= 50 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Live: $confPercent%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = confColor
                        )
                        Text(
                            "${lastDetection.matched}/${lastDetection.expected} anchors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (lastDetection != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Not detected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                Icons.AutoMirrored.Filled.BluetoothSearching,
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
    missingSelectedLabel: String? = null,
    onSelect: (Player) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when {
        selected != null && selected.available -> selected.displayName
        selected != null -> "${selected.displayName} (offline)"
        !missingSelectedLabel.isNullOrBlank() -> "$missingSelectedLabel (missing)"
        else -> ""
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Speaker") },
            supportingText = {
                if (!missingSelectedLabel.isNullOrBlank() && selected == null) {
                    Text("Stored speaker no longer exists. Select a new speaker.")
                }
            },
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
                    text = {
                        Text(
                            if (player.available) player.displayName else "${player.displayName} (offline)"
                        )
                    },
                    onClick = {
                        onSelect(player)
                        expanded = false
                    }
                )
            }
        }
    }
}
