package net.asksakis.massdroidv2.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.BeaconConfig
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.ProximityScanner.Companion.AUTO_FINGERPRINT_CYCLES
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import java.util.UUID
import javax.inject.Inject

private const val TAG = "ProximityVM"
private const val MIN_TUNED_BEACONS = 3

@HiltViewModel
class ProximityViewModel @Inject constructor(
    private val configStore: ProximityConfigStore,
    private val scanner: ProximityScanner,
    private val playerRepository: PlayerRepository,
    private val roomDetector: RoomDetector
) : ViewModel() {

    val config: StateFlow<ProximityConfig> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProximityConfig())

    val players: StateFlow<List<Player>> = playerRepository.players
    val currentRoom: StateFlow<DetectedRoom?> = roomDetector.currentRoom
    val isAvailable: Boolean = scanner.isAvailable()

    private val _scanResults = MutableStateFlow<List<ProximityScanner.ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ProximityScanner.ScannedDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var liveScanJob: Job? = null

    init {
        viewModelScope.launch { configStore.load() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(enabled = enabled) } }
    }

    fun setAutoTransfer(auto: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(autoTransfer = auto) } }
    }

    fun setScanDuringIdle(enabled: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(scanDuringIdlePlayback = enabled) } }
    }

    fun deleteRoom(roomId: String) {
        viewModelScope.launch {
            configStore.update { c -> c.copy(rooms = c.rooms.filter { it.id != roomId }) }
            roomDetector.reset()
        }
    }

    fun saveRoom(
        roomId: String?,
        name: String,
        playerId: String,
        playerName: String,
        selectedBeacons: List<ProximityScanner.ScannedDevice>
    ) {
        viewModelScope.launch {
            val beacons = selectedBeacons.map {
                BeaconConfig(it.address, it.name ?: it.address, it.rssi)
            }
            val room = RoomConfig(
                id = roomId ?: UUID.randomUUID().toString(),
                name = name, playerId = playerId, playerName = playerName,
                beacons = beacons
            )
            configStore.update { config ->
                val updated = config.rooms.toMutableList()
                val index = updated.indexOfFirst { it.id == room.id }
                if (index >= 0) updated[index] = room else updated.add(room)
                config.copy(rooms = updated)
            }
            roomDetector.reset()
            Log.d(TAG, "Room saved: ${room.name}, ${beacons.size} beacons")
        }
    }

    private val _autoFingerprintProgress = MutableStateFlow<Int?>(null)
    val autoFingerprintProgress: StateFlow<Int?> = _autoFingerprintProgress.asStateFlow()

    fun autoFingerprint(onResult: (List<ProximityScanner.ScannedDevice>) -> Unit) {
        viewModelScope.launch {
            val rssiAccum = mutableMapOf<String, MutableList<Int>>()
            val nameMap = mutableMapOf<String, String?>()
            val categoryMap = mutableMapOf<String, ProximityScanner.DeviceCategory>()
            _autoFingerprintProgress.value = 0
            for (i in 1..AUTO_FINGERPRINT_CYCLES) {
                val devices = scanner.scanOnce(lowPower = false)
                for (d in devices) {
                    rssiAccum.getOrPut(d.address) { mutableListOf() }.add(d.rssi)
                    if (d.name != null) nameMap[d.address] = d.name
                    if (d.category != ProximityScanner.DeviceCategory.UNKNOWN) categoryMap[d.address] = d.category
                }
                _autoFingerprintProgress.value = i
            }
            _autoFingerprintProgress.value = null

            // Pick named, non-mobile devices seen in 2+ cycles
            val candidates = rssiAccum
                .filter { it.value.size >= 2 }
                .filter { categoryMap[it.key] != ProximityScanner.DeviceCategory.MOBILE }
                .filter { nameMap[it.key] != null }
                .map { (addr, rssiList) -> ProximityScanner.ScannedDevice(addr, nameMap[addr], rssiList.average().toInt()) }
                .sortedByDescending { it.rssi }

            Log.d(TAG, "Auto-fingerprint: ${candidates.size} candidates from ${rssiAccum.size} devices")
            onResult(candidates)
        }
    }

    // --- Auto Room Tuning ---

    data class RoomSnapshot(
        val roomId: String,
        val roomName: String,
        val devices: Map<String, Int>, // address -> avg RSSI
        val names: Map<String, String?> = emptyMap()
    )

    private val _tuningSnapshots = MutableStateFlow<List<RoomSnapshot>>(emptyList())
    val tuningSnapshots: StateFlow<List<RoomSnapshot>> = _tuningSnapshots.asStateFlow()

    private val _tuningStep = MutableStateFlow<String?>(null)
    val tuningStep: StateFlow<String?> = _tuningStep.asStateFlow()

    /** Collect BLE snapshot for a room during tuning wizard */
    fun collectRoomSnapshot(roomId: String, roomName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _tuningStep.value = "Scanning $roomName..."
            val rssiAccum = mutableMapOf<String, MutableList<Int>>()
            val nameMap = mutableMapOf<String, String?>()
            val categoryMap = mutableMapOf<String, ProximityScanner.DeviceCategory>()
            _autoFingerprintProgress.value = 0
            for (i in 1..AUTO_FINGERPRINT_CYCLES) {
                val devices = scanner.scanOnce(lowPower = false)
                for (d in devices) {
                    rssiAccum.getOrPut(d.address) { mutableListOf() }.add(d.rssi)
                    if (d.name != null) nameMap[d.address] = d.name
                    if (d.category != ProximityScanner.DeviceCategory.UNKNOWN) categoryMap[d.address] = d.category
                }
                _autoFingerprintProgress.value = i
            }
            _autoFingerprintProgress.value = null

            // Filter: seen in 2+ cycles, not mobile, named only (stable MAC)
            val avgDevices = rssiAccum
                .filter { it.value.size >= 2 }
                .filter { categoryMap[it.key] != ProximityScanner.DeviceCategory.MOBILE }
                .filter { nameMap[it.key] != null }
                .mapValues { (_, rssiList) -> rssiList.average().toInt() }

            val snapshot = RoomSnapshot(roomId, roomName, avgDevices, nameMap)
            _tuningSnapshots.value = _tuningSnapshots.value + snapshot
            _tuningStep.value = null
            Log.d(TAG, "Tuning snapshot for $roomName: ${avgDevices.size} devices")
            onDone()
        }
    }

    /** After all snapshots collected, compute optimal beacons per room */
    fun applyTuning() {
        viewModelScope.launch {
            val snapshots = _tuningSnapshots.value
            if (snapshots.size < 2) return@launch

            _tuningStep.value = "Computing optimal beacons..."

            // For each device, compute variance across rooms (high = good discriminator)
            val allAddrs = snapshots.flatMap { it.devices.keys }.toSet()
            val deviceVariance = mutableMapOf<String, Double>()
            for (addr in allAddrs) {
                val values = snapshots.mapNotNull { it.devices[addr] }
                if (values.size < 2) continue
                val mean = values.average()
                deviceVariance[addr] = values.map { (it - mean) * (it - mean) }.average()
            }

            // For each room, pick devices where THIS room has the strongest signal
            // AND the device has high cross-room variance (good discriminator)
            configStore.update { config ->
                val updatedRooms = config.rooms.map { room ->
                    val snapshot = snapshots.find { it.roomId == room.id } ?: return@map room
                    val otherSnapshots = snapshots.filter { it.roomId != room.id }

                    // Score each device: how much stronger is it here vs other rooms
                    val allNames = snapshots.flatMap { it.names.entries }.associate { it.key to it.value }

                    // Separate unique (only in this room or >15dBm advantage) from shared
                    val unique = mutableListOf<Triple<String, Int, Double>>()
                    val shared = mutableListOf<Triple<String, Int, Double>>()

                    for ((addr, rssi) in snapshot.devices) {
                        val otherMax = otherSnapshots.mapNotNull { it.devices[addr] }.maxOrNull() ?: -100
                        val advantage = rssi - otherMax
                        val variance = deviceVariance[addr] ?: 0.0
                        val score = advantage.toDouble() + kotlin.math.sqrt(variance) + (rssi + 100).coerceAtLeast(0)
                        val entry = Triple(addr, rssi, score)
                        if (otherMax <= -100 || advantage > 15) unique.add(entry) else shared.add(entry)
                    }

                    // Priority: unique devices first, then shared sorted by score
                    val ranked = (unique.sortedByDescending { it.third } + shared.sortedByDescending { it.third })

                    val bestBeacons = ranked.take(4.coerceAtLeast(MIN_TUNED_BEACONS)).map { (addr, rssi, _) ->
                        val name = allNames[addr] ?: room.beacons.find { it.address == addr }?.name ?: addr
                        BeaconConfig(addr, name, rssi)
                    }

                    Log.d(TAG, "Tuned ${room.name}: ${bestBeacons.size} beacons from ${snapshot.devices.size} devices")
                    bestBeacons.forEach { b -> Log.d(TAG, "  ${b.name} ref=${b.referenceRssi}dBm") }

                    room.copy(beacons = bestBeacons)
                }
                config.copy(rooms = updatedRooms)
            }

            roomDetector.reset()
            _tuningSnapshots.value = emptyList()
            _tuningStep.value = null
            Log.d(TAG, "Auto-tuning complete")
        }
    }

    fun clearTuning() {
        _tuningSnapshots.value = emptyList()
        _tuningStep.value = null
    }

    fun startLiveScan() {
        if (liveScanJob?.isActive == true) return
        _isScanning.value = true
        liveScanJob = viewModelScope.launch {
            try {
                scanner.liveScan().collect { devices ->
                    _scanResults.value = devices
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun scanOnce() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            _scanResults.value = scanner.scanOnce(lowPower = false).sortedByDescending { it.rssi }
            _isScanning.value = false
        }
    }
}
