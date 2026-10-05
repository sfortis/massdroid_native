package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.components.LabeledSlider
import net.asksakis.massdroidv2.ui.components.MdSlider
import net.asksakis.massdroidv2.ui.components.SETTINGS_ROW_INSET
import net.asksakis.massdroidv2.ui.components.SETTINGS_TEXT_INSET
import net.asksakis.massdroidv2.ui.components.SettingsBadge
import net.asksakis.massdroidv2.ui.components.SETTINGS_SCREEN_BOTTOM_PADDING
import net.asksakis.massdroidv2.ui.components.SettingsChoiceRow
import net.asksakis.massdroidv2.ui.components.SettingsConfirmDialog
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionDivider
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsSwitchRow
import net.asksakis.massdroidv2.ui.components.SettingsTone

import net.asksakis.massdroidv2.data.proximity.WifiMatchMode
import net.asksakis.massdroidv2.data.proximity.SENSITIVITY_MAX
import net.asksakis.massdroidv2.data.proximity.SENSITIVITY_MIN
import net.asksakis.massdroidv2.data.proximity.effectiveSensitivity
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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import net.asksakis.massdroidv2.data.proximity.CalibrationQuality
import net.asksakis.massdroidv2.data.proximity.AnchorType
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.DetectionPolicy
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speaker

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
    // rememberSaveable so the in-progress room setup (typed name, picked player) survives an Activity
    // recreation such as a screen rotation. Player is not Parcelable, so the player is tracked by its
    // stable id and the Player object is derived from the live list.
    var roomName by rememberSaveable { mutableStateOf("") }
    var selectedPlayerId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialized by rememberSaveable { mutableStateOf(roomId == null) }
    val selectedPlayer = players.find { it.playerId == selectedPlayerId }
    val missingStoredPlayerName = remember(existingRoom, selectedPlayer, players) {
        existingRoom?.takeIf {
            selectedPlayer == null && players.none { player -> player.playerId == it.playerId }
        }?.playerName
    }

    // Saved so the delete confirmation survives rotation.
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(existingRoom, players) {
        if (!initialized && existingRoom != null) {
            roomName = existingRoom.name
            selectedPlayerId = existingRoom.playerId
            if (players.any { it.playerId == existingRoom.playerId } || players.isEmpty()) initialized = true
        }
    }

    LaunchedEffect(existingRoom) {
        if (selectedPlayerId == null && existingRoom != null) {
            selectedPlayerId = existingRoom.playerId
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (existingRoom != null) "Edit room" else "New room") },
                navigationIcon = {
                    MdIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    MdIconButton(
                        onClick = {
                            val player = selectedPlayer
                            if (roomName.isBlank() || player == null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Couldn't save the room. Give it a name and choose a speaker.")
                                }
                                return@MdIconButton
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
            // The name and the speaker are saved by the check mark in the top bar. The
            // sections below write each change at once.
            SettingsSectionHeader("Room", caption = "Saved with the check mark")

            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                label = { Text("Room name") },
                placeholder = { Text("Living room") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SETTINGS_ROW_INSET, vertical = 4.dp)
            )

            SpeakerChoiceRow(
                players = players,
                selected = selectedPlayer,
                missingSelectedLabel = missingStoredPlayerName,
                onSelect = { selectedPlayerId = it }
            )

            if (existingRoom != null) {
                SettingsSectionDivider()
                DetectionSection(room = existingRoom, viewModel = viewModel)

                SettingsSectionDivider()
                PlaybackConfigSection(room = existingRoom, viewModel = viewModel)
            }

            SettingsSectionDivider()
            SettingsSectionHeader("Calibration")

            val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
            val isCalibrating = autoProgress != null
            val bleInspectionInProgress by viewModel.bleInspectionInProgress.collectAsStateWithLifecycle()
            val bleInspectionReport by viewModel.bleInspectionReport.collectAsStateWithLifecycle()
            val bleInspectionError by viewModel.bleInspectionError.collectAsStateWithLifecycle()

            if (existingRoom != null) {
                val lastDetection by viewModel.lastDetection.collectAsStateWithLifecycle()
                CalibrationInfo(existingRoom, lastDetection)
            }

            val actionsEnabled = !isCalibrating && !bleInspectionInProgress && existingRoom != null
            SettingsRow(
                title = if (existingRoom?.fingerprints?.isNotEmpty() == true) "Recalibrate" else "Calibrate",
                icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                // A new room has no id to calibrate against until it is saved.
                supporting = if (existingRoom == null) "Save the room first, then calibrate." else null,
                onClick = { existingRoom?.id?.let { id -> viewModel.calibrateRoom(id) {} } },
                enabled = actionsEnabled
            )
            SettingsRow(
                title = "Inspect BLE",
                icon = Icons.Default.Bluetooth,
                supporting = "Which nearby devices Follow Me uses as room anchors.",
                onClick = { existingRoom?.id?.let { id -> viewModel.inspectRoomBle(id) } },
                enabled = actionsEnabled
            )

            if (existingRoom != null && existingRoom.fingerprints.isNotEmpty() && existingRoom.beaconProfiles.isNotEmpty()) {
                SettingsSectionDivider()
                TopAnchorsSection(existingRoom)
            }

            if (existingRoom != null) {
                SettingsSectionDivider()
                SettingsRow(
                    title = "Delete room",
                    icon = Icons.Default.Delete,
                    onClick = { showDeleteConfirm = true },
                    tone = SettingsTone.DESTRUCTIVE
                )
            }
            Spacer(modifier = Modifier.height(SETTINGS_SCREEN_BOTTOM_PADDING))

            // The ViewModel has no way to stop a scan once it runs, so these dialogs have no
            // button and cannot be dismissed; they close on their own when the scan ends.
            if (isCalibrating) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Calibrating") },
                    text = {
                        Text(
                            "Scanning $autoProgress of ${ProximityScanner.AUTO_FINGERPRINT_CYCLES}. " +
                                "Walk to 2 or 3 spots in the room with the phone in hand, away from doorways."
                        )
                    },
                    confirmButton = {}
                )
            }

            if (bleInspectionInProgress) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Inspecting BLE") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Scanning nearby BLE devices the same way Follow Me does.")
                            // The scan reports no progress, so an indeterminate bar shows it is running.
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = {}
                )
            }

            bleInspectionError?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissBleInspectionError() },
                    title = { Text("Couldn't inspect BLE") },
                    text = { Text(error) },
                    // Informational, so a single Close like every other dialog that only reports.
                    confirmButton = {
                        MdTextButton(onClick = { viewModel.dismissBleInspectionError() }) { Text("Close") }
                    }
                )
            }

            bleInspectionReport?.let { report ->
                BleInspectionDialog(
                    report = report,
                    onDismiss = { viewModel.dismissBleInspection() }
                )
            }
        }
    }

    if (showDeleteConfirm && existingRoom != null) {
        // The shared confirm, so deleting a room reads like every other destructive action.
        SettingsConfirmDialog(
            title = "Delete room?",
            text = "\"${existingRoom.name}\" and its calibration will be deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteRoom(existingRoom.id)
                onBack()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    val calibrationError by viewModel.calibrationError.collectAsStateWithLifecycle()
    calibrationError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationError() },
            title = { Text("Couldn't calibrate") },
            text = { Text(error) },
            confirmButton = {
                MdTextButton(onClick = { viewModel.dismissCalibrationError() }) { Text("Close") }
            }
        )
    }

    val calibrationSummary by viewModel.calibrationSummary.collectAsStateWithLifecycle()
    calibrationSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationSummary() },
            title = { Text("Calibration result") },
            text = { Text(summary) },
            confirmButton = {
                MdTextButton(onClick = { viewModel.dismissCalibrationSummary() }) { Text("Close") }
            }
        )
    }
}

/**
 * What the inspection scan saw, grouped by what Follow Me does with each device. A
 * standard dialog: the groups are sections divided the way settings sections are, and the
 * body scrolls on its own so Close stays on screen.
 */
@Composable
private fun BleInspectionDialog(
    report: BleInspectionReport,
    onDismiss: () -> Unit
) {
    val usedCount = report.usefulAnchors.size + report.stableCandidates.size
    val ignoredCount = report.privateAddressDevices.size + report.mobileDevices.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BLE inspection") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "${report.roomName}: ${report.totalDevices} devices in this scan, " +
                        "$usedCount usable as room anchors and $ignoredCount ignored."
                )
                report.connectedBssid?.let { bssid ->
                    Text(
                        "Connected to Wi-Fi access point $bssid.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsSectionDivider()
                BleInspectionSection(
                    title = "Matched to this room",
                    supportingText = "These match the room's saved anchors, so detection uses them now.",
                    emptyMessage = "Nothing in this scan matches the room's top anchors.",
                    items = report.usefulAnchors
                )
                SettingsSectionDivider()
                BleInspectionSection(
                    title = "Stable candidates",
                    supportingText = "Recalibrating can add these stable devices to the room.",
                    emptyMessage = "No other stable devices in this scan.",
                    items = report.stableCandidates
                )
                SettingsSectionDivider()
                BleInspectionSection(
                    title = "Ignored: private addresses",
                    supportingText = "The current calibration rules do not use these as room anchors.",
                    emptyMessage = "No private-address devices in this scan.",
                    items = report.privateAddressDevices
                )
                SettingsSectionDivider()
                BleInspectionSection(
                    title = "Ignored: mobile devices",
                    supportingText = "Phones, watches and other devices that move with people are not used.",
                    emptyMessage = "No mobile devices in this scan.",
                    items = report.mobileDevices
                )
            }
        },
        confirmButton = {
            MdTextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * One group of the inspection report. Plain, like the settings sections: the dialog's
 * surface is the only container, and a divider in the dialog separates one group from the
 * next.
 */
@Composable
private fun BleInspectionSection(
    title: String,
    supportingText: String,
    emptyMessage: String,
    items: List<BleInspectionItem>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
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
            // Spaced rather than divided, as rows inside a settings section are.
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item -> BleInspectionRow(item = item) }
            }
        }
    }
}

/** One device: its name and signal, then its address and what Follow Me knows about it. */
@Composable
private fun BleInspectionRow(item: BleInspectionItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(item.name ?: item.address, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "RSSI ${item.rssi}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The address on its own line, then the labels two to a line: up to six labels
        // chained on one line wrapped mid-fact and could not be scanned.
        Text(
            (listOf(listOf(item.address)) + item.detailLabels().chunked(2))
                .joinToString("\n") { it.joinToString(" · ") },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The classification and profile figures of a device in words rather than enum names. */
private fun BleInspectionItem.detailLabels(): List<String> = buildList {
    add(
        when (anchorType) {
            AnchorType.MAC -> "Known by address"
            AnchorType.NAME -> "Known by name"
        }
    )
    add(
        when (addressType) {
            ProximityScanner.AddressType.PUBLIC -> "Public address"
            ProximityScanner.AddressType.RANDOM_STATIC -> "Static random address"
            ProximityScanner.AddressType.RPA -> "Private address (resolvable)"
            ProximityScanner.AddressType.NRPA -> "Private address (non-resolvable)"
            ProximityScanner.AddressType.AMBIGUOUS -> "Address type unclear"
        }
    )
    when (category) {
        ProximityScanner.DeviceCategory.STATIONARY -> add("Stationary")
        ProximityScanner.DeviceCategory.MOBILE -> add("Mobile")
        ProximityScanner.DeviceCategory.UNKNOWN -> Unit
    }
    profileWeight?.let { add("Weight ${formatWeight(it)}") }
    profileDiscrimination?.let { add("Discrimination ${String.format("%.1f", it)}") }
    profileMeanRssi?.let { add("Mean RSSI $it") }
}

/**
 * A count with its noun in the right number, as in "1 beacon" and "3 beacons". Shared with
 * the room list in the Follow Me screen.
 */
internal fun countLabel(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"

/** A beacon's weight in the room's fingerprint, as shown in the inspection and the top anchors. */
private fun formatWeight(weight: Double): String = String.format("%.2f", weight)

/** The value of the Wi-Fi override choice that turns the override off. */
private const val WIFI_OVERRIDE_OFF = "off"

/**
 * How Follow Me recognises this room: the Wi-Fi override, the BLE detection mode and the
 * sensitivity. The override replaces BLE detection entirely, so the other two are disabled
 * while it is on.
 */
@Composable
private fun DetectionSection(
    room: RoomConfig,
    viewModel: ProximityViewModel
) {
    val bleEnabled = room.wifiMatchMode == null
    SettingsSectionHeader("Detection", caption = "Changes apply immediately")

    WifiOverrideRow(
        room = room,
        onSelect = { mode -> viewModel.updateRoomWifiMatchMode(room.id, mode) }
    )

    SettingsChoiceRow(
        title = "Detection mode",
        icon = Icons.Default.Radar,
        options = DetectionPolicy.entries.map { policy ->
            QueueConfigOption(
                value = policy.name,
                title = policy.name.lowercase().replaceFirstChar { it.uppercase() },
                description = when (policy) {
                    DetectionPolicy.STRICT ->
                        "Prefer this for nearby rooms that need cleaner BLE separation."
                    DetectionPolicy.NORMAL ->
                        "Use this when BLE coverage is weaker and the room is harder to fingerprint."
                }
            )
        },
        selectedValue = room.detectionPolicy.name,
        onSelect = { value ->
            DetectionPolicy.entries.firstOrNull { it.name == value }
                ?.let { viewModel.updateRoomPolicy(room.id, it) }
        },
        supporting = "Disabled while the Wi-Fi override is on.".takeIf { !bleEnabled },
        enabled = bleEnabled
    )

    // A slider has no row shape, so it sits under the rows aligned with their text.
    LabeledSlider(
        title = "Sensitivity",
        value = room.effectiveSensitivity(),
        onValueChangeFinished = { viewModel.updateRoomSensitivity(room.id, it) },
        description = "Lower is stricter with fewer false matches, higher switches to the room sooner.",
        valueRange = SENSITIVITY_MIN..SENSITIVITY_MAX,
        steps = 9,
        enabled = bleEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * Detect this room by its Wi-Fi network instead of BLE beacons. Matching needs the network
 * the room was calibrated on, so both matching options stay disabled until then.
 */
@Composable
private fun WifiOverrideRow(
    room: RoomConfig,
    onSelect: (WifiMatchMode?) -> Unit
) {
    val wifiMode = room.wifiMatchMode
    val canUseWifi = room.connectedBssid != null || room.connectedSsid != null
    val needsCalibration = "Calibrate once while connected to the room's Wi-Fi to enable."
    SettingsChoiceRow(
        title = "Wi-Fi override",
        icon = Icons.Default.Wifi,
        options = listOf(
            QueueConfigOption(value = WIFI_OVERRIDE_OFF, title = "Off"),
            QueueConfigOption(
                value = WifiMatchMode.BSSID.name,
                title = "BSSID",
                disabled = !canUseWifi,
                disabledReason = needsCalibration,
                description = "Match exact access point (${room.connectedBssid}). Best for single-AP locations."
            ),
            QueueConfigOption(
                value = WifiMatchMode.SSID.name,
                title = "SSID",
                disabled = !canUseWifi,
                disabledReason = needsCalibration,
                description = "Match network name (${room.connectedSsid}). Best for mesh/enterprise with multiple APs."
            )
        ),
        selectedValue = wifiMode?.name ?: WIFI_OVERRIDE_OFF,
        onSelect = { value -> onSelect(WifiMatchMode.entries.firstOrNull { it.name == value }) },
        supporting = when {
            !canUseWifi -> needsCalibration
            wifiMode == WifiMatchMode.BSSID -> "Exact access point (${room.connectedBssid})"
            wifiMode == WifiMatchMode.SSID -> "Network name (${room.connectedSsid})"
            else -> "Off, so BLE beacons detect this room."
        }
    )
}

/**
 * What happens when you enter this room (a playlist, its shuffle, a volume) and when you
 * leave it.
 */
@Composable
private fun PlaybackConfigSection(
    room: RoomConfig,
    viewModel: ProximityViewModel
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playbackConfig = room.playbackConfig
    val hasPlaylist = playbackConfig.playlistUri != null
    val sortedPlaylists = remember(playlists) { playlists.sortedBy { it.name.lowercase() } }

    LaunchedEffect(Unit) { viewModel.loadPlaylists() }

    SettingsSectionHeader("Playback", caption = "When you enter or leave this room")

    // Without a playlist the current queue moves here instead, which the empty value says.
    // Named "Play on arrival" rather than Auto-Play so it is not confused with Music
    // Assistant's own Autoplay, which continues a queue after its last track.
    // The shared choice row and its bounded lazy dialog, so this is not a second radio
    // dialog of its own; the dialog scrolls through a library of hundreds of playlists.
    SettingsChoiceRow(
        title = "Play on arrival",
        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
        options = listOf(QueueConfigOption(value = PLAYLIST_NONE, title = PLAYLIST_NONE_TITLE)) +
            sortedPlaylists.map { QueueConfigOption(value = it.uri, title = it.name) },
        selectedValue = playbackConfig.playlistUri ?: PLAYLIST_NONE,
        onSelect = { value ->
            val playlist = sortedPlaylists.firstOrNull { it.uri == value }
            viewModel.updateRoomPlayback(
                room.id,
                playbackConfig.copy(playlistUri = playlist?.uri, playlistName = playlist?.name)
            )
        },
        // An empty library leaves nothing to pick, so the row is disabled and says why. A
        // playlist that is already set is still named, because it is still what plays here.
        supporting = playbackConfig.playlistName
            ?: if (sortedPlaylists.isEmpty()) "No playlists in your library" else PLAYLIST_NONE_TITLE,
        enabled = sortedPlaylists.isNotEmpty()
    )
    // A row of its own rather than a button inside the row above, so each row has one target.
    if (hasPlaylist) {
        SettingsRow(
            title = "Remove playlist",
            icon = Icons.Default.RemoveCircleOutline,
            supporting = "Move the current queue here instead.",
            onClick = {
                viewModel.updateRoomPlayback(room.id, playbackConfig.copy(playlistUri = null, playlistName = null))
            }
        )
    }

    SettingsSwitchRow(
        title = "Shuffle",
        icon = Icons.Default.Shuffle,
        checked = hasPlaylist && playbackConfig.shuffle,
        onCheckedChange = { viewModel.updateRoomPlayback(room.id, playbackConfig.copy(shuffle = it)) },
        supporting = "Available once a playlist plays on arrival.".takeIf { !hasPlaylist },
        enabled = hasPlaylist
    )

    SettingsSwitchRow(
        title = "Set volume",
        icon = Icons.AutoMirrored.Filled.VolumeUp,
        checked = playbackConfig.volumeEnabled,
        onCheckedChange = { viewModel.updateRoomPlayback(room.id, playbackConfig.copy(volumeEnabled = it)) },
        supporting = if (playbackConfig.volumeEnabled) {
            "Apply ${playbackConfig.volumeLevel * 10}% when the room is confirmed."
        } else {
            "Use current player volume"
        }
    )
    if (playbackConfig.volumeEnabled) {
        RoomVolumeSlider(
            level = playbackConfig.volumeLevel,
            onLevelChange = { level ->
                viewModel.updateRoomPlayback(room.id, playbackConfig.copy(volumeLevel = level))
            }
        )
    }

    SettingsSwitchRow(
        title = "Stop on leave",
        icon = Icons.Default.PauseCircleOutline,
        checked = room.stopOnLeave,
        onCheckedChange = { viewModel.updateRoomStopOnLeave(room.id, it) },
        supporting = "Pause playback after 10 minutes when you leave this room."
    )
}

/** The "Play on arrival" value that plays no playlist and moves the current queue instead. */
private const val PLAYLIST_NONE = ""
private const val PLAYLIST_NONE_TITLE = "None (move current queue)"

/** The room volume in tenths, under the Set volume row and aligned with its text. */
@Composable
private fun RoomVolumeSlider(
    level: Int,
    onLevelChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MdSlider(
            value = level.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What the room's calibration holds and how it is doing right now, as rows: the summary
 * with its quality, the Wi-Fi network it saw and the live detection.
 */
@Composable
private fun CalibrationInfo(
    room: RoomConfig,
    lastDetection: RoomDetector.DetectionStatus? = null
) {
    if (room.fingerprints.isEmpty()) {
        SettingsRow(
            title = "Calibration data",
            icon = Icons.Default.Fingerprint,
            supporting = "This room is not calibrated yet."
        )
        return
    }

    val capturedAt = room.fingerprints.maxOf { it.capturedAtMs }
    val ago = (System.currentTimeMillis() - capturedAt) / 60_000
    val timeText = when {
        ago < 1 -> "just now"
        ago < 60 -> "${ago}m ago"
        ago < 1440 -> "${ago / 60}h ago"
        else -> "${ago / 1440}d ago"
    }
    val qualityBadge = when (room.calibrationQuality) {
        CalibrationQuality.GOOD -> SettingsBadge("Good")
        CalibrationQuality.WEAK -> SettingsBadge("Weak", SettingsTone.ERROR)
        CalibrationQuality.UNCALIBRATED -> SettingsBadge("N/A")
    }
    SettingsRow(
        title = "Calibration data",
        icon = Icons.Default.Fingerprint,
        // Two short facts on the first line and the age on its own, rather than three facts
        // chained on one line.
        supporting = countLabel(room.beaconProfiles.size, "beacon") + " · " +
            countLabel(room.fingerprints.size, "sample") + "\nTrained $timeText",
        badge = qualityBadge
    )
    // The Weak badge carries the state, so the advice under it is plain text.
    if (room.calibrationQuality == CalibrationQuality.WEAK) {
        Text(
            "Recalibrate closer to stable devices and away from doorways.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, bottom = 8.dp)
        )
    }

    if (room.connectedBssid != null) {
        SettingsRow(
            title = "Wi-Fi",
            icon = Icons.Default.Wifi,
            supporting = if (room.wifiMatchMode == WifiMatchMode.SSID) {
                room.connectedSsid ?: room.connectedBssid
            } else {
                room.connectedBssid
            }
        )
    }

    if (lastDetection != null) {
        LiveDetectionRow(roomId = room.id, detection = lastDetection)
    }
}

/** How strongly the scan running on this screen matches this room. */
@Composable
private fun LiveDetectionRow(
    roomId: String,
    detection: RoomDetector.DetectionStatus
) {
    val isThisRoom = detection.roomId == roomId
    val confPercent = (detection.confidence * 100).toInt()
    SettingsRow(
        title = "Live detection",
        icon = Icons.Default.MyLocation,
        supporting = if (isThisRoom) "${detection.matched}/${detection.expected} anchors" else "Not detected",
        trailing = if (isThisRoom) {
            {
                // Not coloured: a low match is not a fault, and colour is kept for danger.
                Text(
                    "$confPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            null
        }
    )
}

/** The five beacons that weigh most in this room's fingerprint, one row each. */
@Composable
private fun TopAnchorsSection(room: RoomConfig) {
    SettingsSectionHeader("Top anchors", caption = "The beacons that weigh most in detection")
    room.beaconProfiles.sortedByDescending { it.weight }.take(5).forEach { p ->
        SettingsRow(
            title = p.name,
            icon = Icons.Default.Bluetooth,
            trailing = {
                Text(
                    "Weight ${formatWeight(p.weight)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

/**
 * The speaker this room plays on. A stored speaker that has disappeared from the server
 * has no option to select, so the row names it and asks for a new one.
 */
@Composable
private fun SpeakerChoiceRow(
    players: List<Player>,
    selected: Player?,
    missingSelectedLabel: String?,
    onSelect: (playerId: String) -> Unit
) {
    SettingsChoiceRow(
        title = "Speaker",
        icon = Icons.Default.Speaker,
        options = players.sortedBy { it.displayName.lowercase() }.map { player ->
            QueueConfigOption(
                value = player.playerId,
                title = if (player.available) player.displayName else "${player.displayName} (offline)"
            )
        },
        selectedValue = selected?.playerId,
        onSelect = onSelect,
        supporting = when {
            selected != null -> null
            !missingSelectedLabel.isNullOrBlank() ->
                "$missingSelectedLabel no longer exists, so choose a new speaker."
            else -> "Not selected"
        }
    )
}
