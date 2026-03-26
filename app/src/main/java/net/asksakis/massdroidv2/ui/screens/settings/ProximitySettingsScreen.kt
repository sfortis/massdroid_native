package net.asksakis.massdroidv2.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import net.asksakis.massdroidv2.data.proximity.CalibrationQuality
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.proximity.formatMinuteOfDay
import net.asksakis.massdroidv2.service.PlaybackService
import net.asksakis.massdroidv2.ui.permissions.AppPermissions
import net.asksakis.massdroidv2.ui.permissions.AppPermissionRationales
import net.asksakis.massdroidv2.ui.permissions.PermissionRationaleDialog

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
    var permissionRefreshTick by remember { mutableStateOf(0) }
    var showFollowMePermissionDialog by remember { mutableStateOf(false) }
    val requiredPermissions = remember { AppPermissions.followMeRequired() }
    val missingPermissions = remember(permissionRefreshTick, context) {
        AppPermissions.missing(context, requiredPermissions)
    }
    val hasAllFollowMePermissions = missingPermissions.isEmpty()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRefreshTick++
    }
    LaunchedEffect(config.enabled, hasAllFollowMePermissions) {
        if (config.enabled && !hasAllFollowMePermissions) {
            showFollowMePermissionDialog = true
        }
    }

    // High accuracy scanning while this screen is visible
    DisposableEffect(Unit) {
        viewModel.startLiveMonitoring()
        onDispose { viewModel.stopLiveMonitoring() }
    }

    // Request BLE permissions when enabling proximity
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionRefreshTick++
        if (results.values.all { it }) {
            if (!config.enabled) {
                viewModel.setEnabled(true)
            }
            context.startService(
                android.content.Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.PROXIMITY_REEVALUATE_ACTION)
            )
        }
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
            val btEnabled by viewModel.bluetoothEnabled.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text("Enable Follow Me") },
                supportingContent = {
                    Text(
                        if (!btEnabled) "Bluetooth is off. Turn it on to detect room changes."
                        else if (config.enabled && !hasAllFollowMePermissions) "Follow Me is enabled, but some required permissions are missing."
                        else "Detect room changes and control speaker hand-offs."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = config.enabled,
                        enabled = btEnabled || config.enabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                viewModel.setEnabled(false)
                            } else if (btEnabled) {
                                if (hasAllFollowMePermissions) viewModel.setEnabled(true)
                                else showFollowMePermissionDialog = true
                            }
                        }
                    )
                }
            )

            if (config.enabled) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Auto-transfer") },
                    supportingContent = { Text("Transfer playback automatically instead of asking first.") },
                    trailingContent = {
                        Switch(
                            checked = config.autoTransfer,
                            onCheckedChange = { viewModel.setAutoTransfer(it) }
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text("Stop when no room is detected") },
                    supportingContent = {
                        Text("After 10 minutes without a detected room, pause the last active speaker.")
                    },
                    trailingContent = {
                        Switch(
                            checked = config.stopWhenNoRoomActive,
                            onCheckedChange = { viewModel.setStopWhenNoRoomActive(it) }
                        )
                    }
                )

                // Schedule
                ListItem(
                    headlineContent = { Text("Schedule") },
                    supportingContent = {
                        if (config.schedule.enabled) {
                            val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            val activeDays = config.schedule.days.sorted().map { dayNames[it - 1] }.joinToString(", ")
                            Text(
                                "$activeDays, ${formatMinuteOfDay(config.schedule.effectiveStartMinuteOfDay)}\u2013" +
                                    formatMinuteOfDay(config.schedule.effectiveEndMinuteOfDay)
                            )
                        } else {
                            Text("Always active")
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = config.schedule.enabled,
                            onCheckedChange = { viewModel.updateSchedule { s -> s.copy(enabled = it) } }
                        )
                    }
                )

                if (config.schedule.enabled) {
                    ScheduleConfig(config.schedule, viewModel)
                }

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
                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null,
                        modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Calibrate Rooms")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    config.rooms.forEach { room ->
                        val done = room.id in scannedRoomIds
                        val current = nextRoom?.id == room.id && isScanning
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    done -> Icons.Default.Check
                                    current -> Icons.AutoMirrored.Filled.BluetoothSearching
                                    else -> Icons.Default.LocationOn
                                },
                                contentDescription = null,
                                tint = when {
                                    done -> MaterialTheme.colorScheme.primary
                                    current -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                room.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (current || done) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    done -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (done) {
                                Spacer(modifier = Modifier.weight(1f))
                                Text("Done", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (autoProgress != null) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Scanning ($autoProgress/${ProximityScanner.AUTO_FINGERPRINT_CYCLES})...",
                                style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Walk to 2\u20133 spots in the room",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Keep phone in hand, avoid doorways",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (nextRoom != null) {
                            Icon(Icons.Default.LocationOn, contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Go to ${nextRoom.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("then tap Scan",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("All rooms scanned",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tap Apply to build fingerprints",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { showTuningWizard = false; viewModel.clearTuning() },
                            enabled = !isScanning
                        ) { Text("Cancel") }
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
                    }
                }
            },
            confirmButton = {}
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

    if (showFollowMePermissionDialog) {
        PermissionRationaleDialog(
            spec = AppPermissionRationales.followMe,
            onConfirm = {
                showFollowMePermissionDialog = false
                if (missingPermissions.isNotEmpty()) {
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                } else {
                    if (!config.enabled) {
                        viewModel.setEnabled(true)
                    }
                    context.startService(
                        android.content.Intent(context, PlaybackService::class.java)
                            .setAction(PlaybackService.PROXIMITY_REEVALUATE_ACTION)
                    )
                }
            },
            onDismiss = { showFollowMePermissionDialog = false }
        )
    }
}

@Composable
private fun ScheduleConfig(
    schedule: net.asksakis.massdroidv2.data.proximity.ProximitySchedule,
    viewModel: ProximityViewModel
) {
    val dayLabels = listOf("M" to 1, "T" to 2, "W" to 3, "T" to 4, "F" to 5, "S" to 6, "S" to 7)

    // Day chips
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        dayLabels.forEach { (label, day) ->
            val active = day in schedule.days
            androidx.compose.material3.FilterChip(
                selected = active,
                onClick = {
                    val newDays = if (active) schedule.days - day else schedule.days + day
                    if (newDays.isNotEmpty()) viewModel.updateSchedule { it.copy(days = newDays) }
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp)
            )
        }
    }

    // Time range as clickable chips
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.AssistChip(
            onClick = { showStartPicker = true },
            label = { Text(formatMinuteOfDay(schedule.effectiveStartMinuteOfDay), style = MaterialTheme.typography.titleSmall) },
            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text("\u2014", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        androidx.compose.material3.AssistChip(
            onClick = { showEndPicker = true },
            label = { Text(formatMinuteOfDay(schedule.effectiveEndMinuteOfDay), style = MaterialTheme.typography.titleSmall) },
            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
    }

    if (showStartPicker) {
        TimePickerDialog(
            currentMinuteOfDay = schedule.effectiveStartMinuteOfDay,
            onSelect = { minute -> viewModel.updateSchedule { s -> s.copy(startMinuteOfDay = minute, startHour = null) }; showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            currentMinuteOfDay = schedule.effectiveEndMinuteOfDay,
            onSelect = { minute -> viewModel.updateSchedule { s -> s.copy(endMinuteOfDay = minute, endHour = null) }; showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    currentMinuteOfDay: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberTimePickerState(
        initialHour = currentMinuteOfDay / 60,
        initialMinute = currentMinuteOfDay % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(pickerState.hour * 60 + pickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(14.dp),
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
                Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Follow Me requires a device with Bluetooth support.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onBack) { Text("Go Back") }
    }
}
