package net.asksakis.massdroidv2.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import net.asksakis.massdroidv2.data.proximity.ProximityScanner.DeviceCategory
import net.asksakis.massdroidv2.data.proximity.ProximityScanner.Companion.AUTO_FINGERPRINT_CYCLES
import net.asksakis.massdroidv2.data.proximity.ProximityScanner.Companion.AUTO_FINGERPRINT_CYCLES
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.domain.model.Player
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSetupScreen(
    roomId: String?,
    onBack: () -> Unit,
    viewModel: ProximityViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val existingRoom = config.rooms.find { it.id == roomId }
    var roomName by remember { mutableStateOf("") }
    var selectedPlayer by remember { mutableStateOf<Player?>(null) }
    val selectedBeacons = remember { mutableStateMapOf<String, ProximityScanner.ScannedDevice>() }
    var initialized by remember { mutableStateOf(roomId == null) }

    LaunchedEffect(existingRoom, players) {
        if (!initialized && existingRoom != null) {
            roomName = existingRoom.name
            selectedPlayer = players.find { it.playerId == existingRoom.playerId }
            existingRoom.beacons.forEach { beacon ->
                selectedBeacons[beacon.address] = ProximityScanner.ScannedDevice(
                    beacon.address, beacon.name, beacon.referenceRssi
                )
            }
            if (selectedPlayer != null || players.isEmpty()) initialized = true
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) viewModel.startLiveScan()
    }

    LaunchedEffect(Unit) {
        if (permissionsGranted) {
            viewModel.startLiveScan()
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
            permissionLauncher.launch(perms.toTypedArray())
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
                            if (selectedBeacons.size < 2) {
                                scope.launch { snackbarHostState.showSnackbar("Select at least 2 BLE devices") }
                                return@IconButton
                            }
                            viewModel.saveRoom(
                                roomId = existingRoom?.id,
                                name = roomName.trim(),
                                playerId = player.playerId,
                                playerName = player.displayName,
                                selectedBeacons = selectedBeacons.values.toList()
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "BLE Devices (${selectedBeacons.size} selected)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    TextButton(
                        onClick = {
                            viewModel.autoFingerprint { best ->
                                selectedBeacons.clear()
                                best.forEach { selectedBeacons[it.address] = it }
                            }
                        }
                    ) {
                        Text("Auto", style = MaterialTheme.typography.labelMedium)
                    }
                    if (selectedBeacons.isNotEmpty()) {
                        TextButton(onClick = { selectedBeacons.clear() }) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(scanResults, key = { it.address }) { device ->
                    val isSelected = device.address in selectedBeacons
                    ListItem(
                        headlineContent = {
                            Text(
                                device.name ?: "Unknown",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        supportingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "${device.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (device.category != DeviceCategory.UNKNOWN) {
                                    val (label, color) = when (device.category) {
                                        DeviceCategory.STATIONARY ->
                                            "Fixed" to MaterialTheme.colorScheme.primary
                                        DeviceCategory.MOBILE ->
                                            "Mobile" to MaterialTheme.colorScheme.error
                                        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .background(color, MaterialTheme.shapes.extraSmall)
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null
                            )
                        },
                        trailingContent = {
                            RssiBar(rssi = device.rssi)
                        },
                        modifier = Modifier.clickable {
                            if (isSelected) {
                                selectedBeacons.remove(device.address)
                            } else {
                                selectedBeacons[device.address] = device
                            }
                        }
                    )
                }
            }
        }
    }

    val autoProgress by viewModel.autoFingerprintProgress.collectAsStateWithLifecycle()
    if (autoProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    "Fingerprinting...",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text("Scanning nearby devices ($autoProgress/$AUTO_FINGERPRINT_CYCLES)...")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Stay in this room",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
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

@Composable
private fun RssiBar(rssi: Int) {
    val strength = ((rssi + 100).coerceIn(0, 60).toFloat() / 60f)
    val color = when {
        rssi > -50 -> MaterialTheme.colorScheme.primary
        rssi > -70 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.extraSmall
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(strength)
                .background(color, MaterialTheme.shapes.extraSmall)
        )
    }
}
