package net.asksakis.massdroidv2.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.proximity.CalibrationQuality
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximitySettingsScreen(
    onBack: () -> Unit,
    onSetupRoom: (roomId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProximityViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val currentRoom by viewModel.currentRoom.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<RoomConfig?>(null) }
    var showTuningWizard by remember { mutableStateOf(false) }
    val tuningSnapshots by viewModel.tuningSnapshots.collectAsStateWithLifecycle()
    val tuningStep by viewModel.tuningStep.collectAsStateWithLifecycle()
    val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
    val tuningResult by viewModel.tuningResult.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Request BLE permissions when enabling proximity
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) viewModel.setEnabled(true)
    }

    if (!viewModel.isAvailable) {
        UnavailableScreen(onBack)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Enable Proximity Detection") },
                supportingContent = { Text("Detect rooms via BLE and suggest speaker transfers") },
                trailingContent = {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val perms = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    perms += android.Manifest.permission.BLUETOOTH_SCAN
                                    perms += android.Manifest.permission.BLUETOOTH_CONNECT
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    perms += android.Manifest.permission.ACTIVITY_RECOGNITION
                                }
                                val allGranted = perms.all {
                                    context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                                if (allGranted) viewModel.setEnabled(true)
                                else permissionLauncher.launch(perms.toTypedArray())
                            } else {
                                viewModel.setEnabled(false)
                            }
                        }
                    )
                }
            )

            if (config.enabled) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Auto-transfer") },
                    supportingContent = { Text("Transfer queue automatically without notification") },
                    trailingContent = {
                        Switch(
                            checked = config.autoTransfer,
                            onCheckedChange = { viewModel.setAutoTransfer(it) }
                        )
                    }
                )

                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Rooms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (config.rooms.isEmpty()) {
                    Text(
                        "No rooms configured. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                } else {
                    config.rooms.forEach { room ->
                        RoomCard(
                            room = room,
                            isCurrentRoom = currentRoom?.roomId == room.id,
                            onEdit = { onSetupRoom(room.id) },
                            onDelete = { deleteTarget = room }
                        )
                    }
                    if (config.rooms.size >= 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.clearTuning()
                                showTuningWizard = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Calibrate Rooms")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
        if (config.enabled) {
            FloatingActionButton(
                onClick = { onSetupRoom(null) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Room")
            }
        }
    }

    deleteTarget?.let { room ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Room") },
            text = { Text("Delete \"${room.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRoom(room.id)
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showTuningWizard) {
        val scannedRoomIds = tuningSnapshots.map { it.roomId }.toSet()
        val nextRoom = config.rooms.firstOrNull { it.id !in scannedRoomIds }
        val isScanning = tuningStep != null

        AlertDialog(
            onDismissRequest = { if (!isScanning) { showTuningWizard = false; viewModel.clearTuning() } },
            title = {
                Text(
                    "Auto-Tune Rooms",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Progress
                    config.rooms.forEach { room ->
                        val done = room.id in scannedRoomIds
                        val current = nextRoom?.id == room.id && isScanning
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    done -> Icons.Default.LocationOn
                                    current -> Icons.Default.BluetoothSearching
                                    else -> Icons.Default.LocationOn
                                },
                                contentDescription = null,
                                tint = when {
                                    done -> MaterialTheme.colorScheme.primary
                                    current -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                room.name,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    done -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (done) {
                                Spacer(modifier = Modifier.weight(1f))
                                Text("Done", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    if (autoProgress != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Scanning ($autoProgress/${ProximityScanner.AUTO_FINGERPRINT_CYCLES})...",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Walk around the room slowly",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (nextRoom != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Go to ${nextRoom.name} and tap Scan",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("All rooms scanned. Tap Apply to build fingerprints.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                if (nextRoom != null) {
                    TextButton(
                        onClick = {
                            viewModel.collectRoomSnapshot(nextRoom.id, nextRoom.name) {}
                        },
                        enabled = !isScanning
                    ) { Text("Scan ${nextRoom.name}") }
                } else {
                    TextButton(onClick = {
                        viewModel.applyTuning()
                        showTuningWizard = false
                    }) { Text("Apply") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTuningWizard = false; viewModel.clearTuning() },
                    enabled = !isScanning
                ) { Text("Cancel") }
            }
        )
    }

    tuningResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTuningResult() },
            title = { Text("Calibration Results") },
            text = {
                Column {
                    config.rooms.forEach { room ->
                        val quality = result.roomResults[room.id] ?: return@forEach
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(room.name, style = MaterialTheme.typography.bodyMedium)
                            val (label, color) = when (quality) {
                                CalibrationQuality.GOOD -> "Good" to MaterialTheme.colorScheme.primary
                                CalibrationQuality.WEAK -> "Weak" to MaterialTheme.colorScheme.error
                                CalibrationQuality.UNCALIBRATED -> "N/A" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    if (result.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        result.warnings.forEach { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissTuningResult() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun RoomCard(
    room: RoomConfig,
    isCurrentRoom: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (isCurrentRoom) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(room.name, style = MaterialTheme.typography.titleSmall)
                    if (isCurrentRoom) {
                        Text(
                            "HERE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.extraSmall
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Speaker, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(room.playerName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${room.beaconProfiles.size} beacons", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val (qualityLabel, qualityColor) = when (room.calibrationQuality) {
                        CalibrationQuality.GOOD -> "Calibrated" to MaterialTheme.colorScheme.primary
                        CalibrationQuality.WEAK -> "Weak" to MaterialTheme.colorScheme.error
                        CalibrationQuality.UNCALIBRATED -> "Not calibrated" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        qualityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = qualityColor
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnavailableScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Proximity Playback requires Android 12 or later with Bluetooth support.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
    }
}
