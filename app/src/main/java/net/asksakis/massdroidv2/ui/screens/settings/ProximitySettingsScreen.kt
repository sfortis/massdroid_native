package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.components.SETTINGS_ROW_INSET
import net.asksakis.massdroidv2.ui.components.SETTINGS_SCREEN_BOTTOM_PADDING
import net.asksakis.massdroidv2.ui.components.SETTINGS_TEXT_INSET
import net.asksakis.massdroidv2.ui.components.SettingsBadge
import net.asksakis.massdroidv2.ui.components.SettingsChoiceRow
import net.asksakis.massdroidv2.ui.components.SettingsNavigationRow
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionDivider
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsSwitchRow
import net.asksakis.massdroidv2.ui.components.SettingsTone

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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import net.asksakis.massdroidv2.data.proximity.CalibrationQuality
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximitySchedule
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.ProximityTransferMode
import net.asksakis.massdroidv2.data.proximity.effectiveTransferMode
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.proximity.formatMinuteOfDay
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import net.asksakis.massdroidv2.service.FollowMeService
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
    val players by viewModel.players.collectAsStateWithLifecycle()
    val canEvaluateMissingSpeakers = players.isNotEmpty()
    val missingSpeakerRooms = remember(config.rooms, players, canEvaluateMissingSpeakers) {
        if (!canEvaluateMissingSpeakers) emptyList()
        else config.rooms.filter { room -> players.none { player -> player.playerId == room.playerId } }
    }
    // rememberSaveable so the "Calibrate rooms" wizard stays open across an Activity recreation
    // (e.g. screen rotation). The scan/step state itself lives in the ViewModel and already survives;
    // only this visibility flag was being reset to false, which closed the dialog mid-flow.
    var showTuningWizard by rememberSaveable { mutableStateOf(false) }
    val tuningSnapshots by viewModel.tuningSnapshots.collectAsStateWithLifecycle()
    val tuningStep by viewModel.tuningStep.collectAsStateWithLifecycle()
    val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
    val tuningResult by viewModel.tuningResult.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionRefreshTick by remember { mutableStateOf(0) }
    var showFollowMePermissionDialog by rememberSaveable { mutableStateOf(false) }
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
                android.content.Intent(context, FollowMeService::class.java)
                    .setAction(FollowMeService.PROXIMITY_REEVALUATE_ACTION)
            )
        }
    }

    if (!viewModel.isAvailable) {
        UnavailableScreen(onBack)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        val btEnabled by viewModel.bluetoothEnabled.collectAsStateWithLifecycle()
        GeneralSection(
            config = config,
            btEnabled = btEnabled,
            hasAllPermissions = hasAllFollowMePermissions,
            onEnabledChange = { enabled ->
                if (!enabled) {
                    viewModel.setEnabled(false)
                } else if (btEnabled) {
                    if (hasAllFollowMePermissions) viewModel.setEnabled(true)
                    else showFollowMePermissionDialog = true
                }
            },
            viewModel = viewModel
        )

        if (config.enabled) {
            SettingsSectionDivider()
            RoomsSection(
                rooms = config.rooms,
                players = players,
                canEvaluateMissingSpeakers = canEvaluateMissingSpeakers,
                missingSpeakerRooms = missingSpeakerRooms,
                currentRoomId = currentRoom?.roomId,
                onEditRoom = { onSetupRoom(it) },
                onAddRoom = { onSetupRoom(null) },
                onCalibrateRooms = {
                    viewModel.clearTuning()
                    showTuningWizard = true
                }
            )
        }
        // Outside the condition, so the screen ends with the same space whether or not
        // Follow Me is on.
        Spacer(modifier = Modifier.height(SETTINGS_SCREEN_BOTTOM_PADDING))
    }

    if (showTuningWizard) {
        val scannedRoomIds = tuningSnapshots.map { it.roomId }.toSet()
        val nextRoom = config.rooms.firstOrNull { it.id !in scannedRoomIds }
        val isScanning = tuningStep != null
        val closeWizard = {
            showTuningWizard = false
            viewModel.clearTuning()
        }

        AlertDialog(
            // A scan in progress cannot be stopped, so the dialog stays until it ends.
            onDismissRequest = { if (!isScanning) closeWizard() },
            title = { Text("Calibrate rooms") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    config.rooms.forEach { room ->
                        TuningRoomRow(
                            name = room.name,
                            done = room.id in scannedRoomIds,
                            scanning = isScanning && nextRoom?.id == room.id
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(tuningInstruction(autoProgress, nextRoom?.name))
                }
            },
            confirmButton = {
                if (nextRoom != null) {
                    MdTextButton(
                        onClick = { viewModel.collectRoomSnapshot(nextRoom.id, nextRoom.name) {} },
                        enabled = !isScanning
                    ) { Text("Scan") }
                } else {
                    MdTextButton(onClick = {
                        viewModel.applyTuning()
                        showTuningWizard = false
                    }) { Text("Apply") }
                }
            },
            dismissButton = {
                MdTextButton(onClick = closeWizard, enabled = !isScanning) { Text("Cancel") }
            }
        )
    }

    tuningResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTuningResult() },
            title = { Text("Calibration results") },
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
                            Text(room.name)
                            // Only a weak room is coloured: it is the one to act on.
                            val (label, color) = when (quality) {
                                CalibrationQuality.GOOD -> "Good" to MaterialTheme.colorScheme.onSurfaceVariant
                                CalibrationQuality.WEAK -> "Weak" to MaterialTheme.colorScheme.error
                                CalibrationQuality.UNCALIBRATED -> "N/A" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(label, color = color)
                        }
                    }
                    if (result.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        result.warnings.forEach { warning ->
                            Text(
                                warning,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // The results only report, so a single Close, as every informational dialog has.
                MdTextButton(onClick = { viewModel.dismissTuningResult() }) { Text("Close") }
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
                        android.content.Intent(context, FollowMeService::class.java)
                            .setAction(FollowMeService.PROXIMITY_REEVALUATE_ACTION)
                    )
                }
            },
            onDismiss = { showFollowMePermissionDialog = false }
        )
    }
}

/**
 * One room in the calibration wizard: scanned, being scanned, or still to do. The icon
 * carries the state, so the text keeps the dialog's own type and colour.
 */
@Composable
private fun TuningRoomRow(name: String, done: Boolean, scanning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                done -> Icons.Default.Check
                scanning -> Icons.AutoMirrored.Filled.BluetoothSearching
                else -> Icons.Default.LocationOn
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(name, modifier = Modifier.weight(1f))
        when {
            done -> Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant)
            scanning -> Text("Scanning", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the wizard asks for next: walking through the room being scanned, the next room, or Apply. */
private fun tuningInstruction(progress: Int?, nextRoomName: String?): String = when {
    progress != null ->
        "Scanning $progress of ${ProximityScanner.AUTO_FINGERPRINT_CYCLES}. " +
            "Walk to 2 or 3 spots in the room with the phone in hand, away from doorways."
    nextRoomName != null -> "Go to $nextRoomName, then tap Scan."
    else -> "All rooms are scanned. Tap Apply to build the fingerprints."
}

/**
 * The switch that turns Follow Me on, and once it is on, what a room change does and when
 * detection runs.
 */
@Composable
private fun GeneralSection(
    config: ProximityConfig,
    btEnabled: Boolean,
    hasAllPermissions: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    viewModel: ProximityViewModel
) {
    SettingsSectionHeader("General")
    SettingsSwitchRow(
        title = "Enable Follow Me",
        icon = Icons.Default.Sensors,
        checked = config.enabled,
        onCheckedChange = onEnabledChange,
        supporting = when {
            !btEnabled && !config.enabled -> "Turn on Bluetooth to use Follow Me."
            !btEnabled -> "Bluetooth is off, so room changes are not detected."
            config.enabled && !hasAllPermissions -> "Some permissions Follow Me needs are missing."
            else -> "Detect room changes and control speaker hand-offs."
        },
        // Turning it off always works; turning it on needs Bluetooth.
        enabled = btEnabled || config.enabled,
        tone = if (config.enabled && btEnabled && !hasAllPermissions) SettingsTone.ERROR else SettingsTone.NORMAL
    )
    if (!config.enabled) return

    val transferModes = transferModeOptions()
    SettingsChoiceRow(
        title = "On room change",
        icon = Icons.Default.SwapHoriz,
        options = transferModes.map { it.second },
        selectedValue = config.effectiveTransferMode().name,
        onSelect = { value ->
            transferModes.firstOrNull { it.second.value == value }
                ?.let { viewModel.setTransferMode(it.first) }
        }
    )

    SettingsSwitchRow(
        title = "Schedule",
        icon = Icons.Default.Schedule,
        checked = config.schedule.enabled,
        onCheckedChange = { viewModel.updateSchedule { s -> s.copy(enabled = it) } },
        supporting = scheduleSummary(config.schedule)
    )
    if (config.schedule.enabled) {
        ScheduleConfig(config.schedule, viewModel)
    }
}

/**
 * The room-change modes as choices. What each one does is the option's description, so it
 * is read in the dialog where the choice is made.
 */
private fun transferModeOptions(): List<Pair<ProximityTransferMode, QueueConfigOption>> = listOf(
    ProximityTransferMode.ASK to QueueConfigOption(
        value = ProximityTransferMode.ASK.name,
        title = "Ask",
        description = "Show a notification to move or play in the detected room."
    ),
    ProximityTransferMode.AUTO_TRANSFER to QueueConfigOption(
        value = ProximityTransferMode.AUTO_TRANSFER.name,
        title = "Move here",
        description = "Move current playback to the room automatically."
    ),
    ProximityTransferMode.SELECT_ONLY to QueueConfigOption(
        value = ProximityTransferMode.SELECT_ONLY.name,
        title = "Select only",
        description = "Just select the room's player so its controls (mini player, " +
            "volume keys) are ready. No transfer, no prompt."
    )
)

/** The hours of an enabled schedule. Its days are the chips under the row, so they are not repeated here. */
private fun scheduleSummary(schedule: ProximitySchedule): String {
    if (!schedule.enabled) return "Always active"
    return "Active ${formatMinuteOfDay(schedule.effectiveStartMinuteOfDay)}-" +
        formatMinuteOfDay(schedule.effectiveEndMinuteOfDay)
}

/**
 * The days and hours of an enabled schedule, under the Schedule row and indented to its
 * text so they read as part of that setting.
 */
@Composable
private fun ScheduleConfig(
    schedule: ProximitySchedule,
    viewModel: ProximityViewModel
) {
    val dayLabels = listOf("M" to 1, "T" to 2, "W" to 3, "T" to 4, "F" to 5, "S" to 6, "S" to 7)

    // Day chips. Seven independent toggles have no single-choice row to map onto, so they
    // stay chips. They start at the row inset rather than the rows' text: indented to the
    // text and 4dp apart, seven chips were about 35dp wide on a 360dp phone. From the inset
    // with a 2dp gap each is about 45dp. Seven cannot reach 48dp wide on that phone (that
    // needs 336dp of the 328dp left), so the chip keeps the 48dp touch height and stays
    // just under 48dp wide.
    // The default selected fill is secondaryContainer, one step from the light background, so
    // a selected day looked like an unselected one. Selected days are filled with primary
    // instead; unselected ones keep the default outline.
    val dayChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SETTINGS_ROW_INSET),
        horizontalArrangement = Arrangement.spacedBy(DAY_CHIP_GAP)
    ) {
        dayLabels.forEach { (label, day) ->
            val active = day in schedule.days
            // No fixed height: the chip keeps Material's 48dp touch target around its 32dp
            // body. The equal weights keep all seven on one line on a narrow phone.
            FilterChip(
                selected = active,
                onClick = {
                    val newDays = if (active) schedule.days - day else schedule.days + day
                    if (newDays.isNotEmpty()) viewModel.updateSchedule { it.copy(days = newDays) }
                },
                label = {
                    Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                },
                colors = dayChipColors,
                modifier = Modifier.weight(1f)
            )
        }
    }

    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }

    // The rows carry no icon of their own, so they are indented to the Schedule row's text.
    val timeRowIndent = Modifier.padding(start = SETTINGS_TEXT_INSET - SETTINGS_ROW_INSET)
    SettingsRow(
        title = "Start",
        modifier = timeRowIndent,
        supporting = formatMinuteOfDay(schedule.effectiveStartMinuteOfDay),
        onClick = { showStartPicker = true }
    )
    SettingsRow(
        title = "End",
        modifier = timeRowIndent,
        supporting = formatMinuteOfDay(schedule.effectiveEndMinuteOfDay),
        onClick = { showEndPicker = true }
    )

    if (showStartPicker) {
        TimePickerDialog(
            currentMinuteOfDay = schedule.effectiveStartMinuteOfDay,
            onSelect = { minute ->
                viewModel.updateSchedule { s -> s.copy(startMinuteOfDay = minute, startHour = null) }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            currentMinuteOfDay = schedule.effectiveEndMinuteOfDay,
            onSelect = { minute ->
                viewModel.updateSchedule { s -> s.copy(endMinuteOfDay = minute, endHour = null) }
                showEndPicker = false
            },
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
        title = { Text("Select time") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            MdTextButton(onClick = { onSelect(pickerState.hour * 60 + pickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = { MdTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The configured rooms as rows, with a warning above them when a room's speaker is gone,
 * the calibration wizard once there are two rooms to tell apart, and the row that adds one.
 */
@Composable
private fun RoomsSection(
    rooms: List<RoomConfig>,
    players: List<Player>,
    canEvaluateMissingSpeakers: Boolean,
    missingSpeakerRooms: List<RoomConfig>,
    currentRoomId: String?,
    onEditRoom: (roomId: String) -> Unit,
    onAddRoom: () -> Unit,
    onCalibrateRooms: () -> Unit
) {
    SettingsSectionHeader("Rooms")

    if (canEvaluateMissingSpeakers && missingSpeakerRooms.isNotEmpty()) {
        MissingSpeakerWarning(missingSpeakerRooms.size)
    }

    rooms.forEach { room ->
        RoomRow(
            room = room,
            players = players,
            canEvaluateMissingPlayer = canEvaluateMissingSpeakers,
            isCurrentRoom = currentRoomId == room.id,
            onEdit = { onEditRoom(room.id) }
        )
    }
    if (rooms.size >= 2) {
        SettingsRow(
            title = "Calibrate rooms",
            icon = Icons.AutoMirrored.Filled.BluetoothSearching,
            supporting = "Scan each room in turn so they are easier to tell apart.",
            onClick = onCalibrateRooms
        )
    }
    // It opens the room setup screen, so it carries the chevron of a row that navigates.
    SettingsNavigationRow(
        title = "Add room",
        onClick = onAddRoom,
        icon = Icons.Default.Add,
        supporting = "Add the rooms Follow Me should detect.".takeIf { rooms.isEmpty() }
    )
}

/**
 * The count of rooms whose speaker no longer exists. Each of them carries its own badge, so
 * this row only says how many and what to do.
 */
@Composable
private fun MissingSpeakerWarning(count: Int) {
    SettingsRow(
        title = if (count == 1) "1 room has lost its speaker" else "$count rooms have lost their speaker",
        icon = Icons.Default.ErrorOutline,
        supporting = "Open each marked room and choose a new speaker.",
        tone = SettingsTone.ERROR
    )
}

/**
 * One room as a row: its name with a badge for where you are or a missing speaker, then its
 * speaker, and under that its beacons and how well it is calibrated. Tapping it opens the room, where it can
 * also be deleted.
 */
@Composable
private fun RoomRow(
    room: RoomConfig,
    players: List<Player>,
    canEvaluateMissingPlayer: Boolean,
    isCurrentRoom: Boolean,
    onEdit: () -> Unit
) {
    val isMissingPlayer = canEvaluateMissingPlayer && players.none { it.playerId == room.playerId }
    val qualityLabel = when (room.calibrationQuality) {
        CalibrationQuality.GOOD -> "Calibrated"
        CalibrationQuality.WEAK -> "Weak calibration"
        CalibrationQuality.UNCALIBRATED -> "Not calibrated"
    }
    // A room without beacons has nothing to count, so its second line is the state alone.
    val calibrationLine = if (room.beaconProfiles.isEmpty()) {
        qualityLabel
    } else {
        "${countLabel(room.beaconProfiles.size, "beacon")} · $qualityLabel"
    }
    // A row carries one badge. A missing speaker wins over "Here" because it is the one the
    // user has to act on, and the current room still has its own icon.
    val badge = when {
        isMissingPlayer -> SettingsBadge("Missing speaker", SettingsTone.ERROR)
        isCurrentRoom -> SettingsBadge("Here")
        else -> null
    }

    SettingsNavigationRow(
        title = room.name,
        onClick = onEdit,
        // The room you are in gets its own icon, since a row's icon has no accent colour.
        icon = if (isCurrentRoom) Icons.Default.MyLocation else Icons.Default.LocationOn,
        // The speaker on the first line and the calibration on the second, rather than three
        // facts chained on one line. The live name while the speaker exists, the stored one
        // once it is gone; the badge already says that it is missing.
        supporting = (players.firstOrNull { it.playerId == room.playerId }?.displayName ?: room.playerName) +
            "\n" + calibrationLine,
        badge = badge
    )
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
            MdTextButton(onClick = onBack) { Text("Go back") }
    }
}

/** Space between the day chips, kept small so seven fit a narrow phone at a usable width. */
private val DAY_CHIP_GAP = 2.dp
