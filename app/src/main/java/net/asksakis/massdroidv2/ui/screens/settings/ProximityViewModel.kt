package net.asksakis.massdroidv2.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.BeaconProfile
import net.asksakis.massdroidv2.data.proximity.CalibrationQuality
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.ProximityScanner.Companion.AUTO_FINGERPRINT_CYCLES
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.proximity.RoomFingerprint
import net.asksakis.massdroidv2.data.proximity.ProximitySchedule
import net.asksakis.massdroidv2.data.proximity.RoomPlaybackConfig
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import java.util.UUID
import javax.inject.Inject
import kotlin.math.sqrt

private const val TAG = "ProximityVM"
private const val MIN_SIGHTINGS = 2
private const val FINGERPRINTS_PER_ROOM = 8
private const val MIN_WEIGHT = 0.15
private const val MAX_WEIGHT = 2.5

// Quality gate thresholds
private const val MIN_USABLE_PROFILES = 3
private const val MIN_AVG_VISIBILITY = 0.3
private const val MIN_DISCRIMINATIVE_BEACONS = 1
private const val DISCRIMINATIVE_THRESHOLD = 5.0
private const val MAX_FLOOR_RATIO = 0.8

@HiltViewModel
class ProximityViewModel @Inject constructor(
    private val configStore: ProximityConfigStore,
    private val scanner: ProximityScanner,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val roomDetector: RoomDetector
) : ViewModel() {

    val config: StateFlow<ProximityConfig> = configStore.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProximityConfig())

    val players: StateFlow<List<Player>> = playerRepository.players
    val currentRoom: StateFlow<DetectedRoom?> = roomDetector.currentRoom
    val isAvailable: Boolean = scanner.isAvailable()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        viewModelScope.launch { configStore.load() }
    }

    private val _calibrationError = MutableStateFlow<String?>(null)
    val calibrationError: StateFlow<String?> = _calibrationError.asStateFlow()
    fun dismissCalibrationError() { _calibrationError.value = null }

    fun loadPlaylists() {
        viewModelScope.launch {
            try { _playlists.value = musicRepository.getPlaylists() } catch (_: Exception) { }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(enabled = enabled) } }
    }

    fun setAutoTransfer(auto: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(autoTransfer = auto) } }
    }

    fun updateSchedule(transform: (ProximitySchedule) -> ProximitySchedule) {
        viewModelScope.launch { configStore.update { it.copy(schedule = transform(it.schedule)) } }
    }

    fun updateRoomPlayback(roomId: String, playback: RoomPlaybackConfig) {
        viewModelScope.launch {
            configStore.update { config ->
                config.copy(rooms = config.rooms.map { r ->
                    if (r.id == roomId) r.copy(playbackConfig = playback) else r
                })
            }
        }
    }

    fun updateRoomPolicy(roomId: String, policy: net.asksakis.massdroidv2.data.proximity.DetectionPolicy) {
        viewModelScope.launch {
            configStore.update { config ->
                config.copy(rooms = config.rooms.map { r ->
                    if (r.id == roomId) r.copy(detectionPolicy = policy) else r
                })
            }
        }
    }

    fun deleteRoom(roomId: String) {
        viewModelScope.launch {
            configStore.update { c -> c.copy(rooms = c.rooms.filter { it.id != roomId }) }
            roomDetector.reset()
        }
    }

    fun saveRoom(roomId: String?, name: String, playerId: String, playerName: String) {
        viewModelScope.launch {
            val id = roomId ?: UUID.randomUUID().toString()
            val existing = configStore.config.value.rooms.find { it.id == id }
            val room = existing?.copy(name = name, playerId = playerId, playerName = playerName)
                ?: RoomConfig(id = id, name = name, playerId = playerId, playerName = playerName)
            configStore.update { config ->
                val updated = config.rooms.toMutableList()
                val index = updated.indexOfFirst { it.id == room.id }
                if (index >= 0) updated[index] = room else updated.add(room)
                config.copy(rooms = updated)
            }
            roomDetector.reset()
            Log.d(TAG, "Room saved: ${room.name}")
        }
    }

    /** Calibrate a single room in-place (scan + compute fingerprints/profiles) */
    fun calibrateRoom(roomId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            roomDetector.suppress()
            try {
                _autoFingerprintProgress.value = 0

                val rawScans = mutableListOf<Map<String, Int>>()
                val nameMap = mutableMapOf<String, String?>()
                val categoryMap = mutableMapOf<String, ProximityScanner.DeviceCategory>()
                val addressTypes = mutableMapOf<String, ProximityScanner.AddressType>()

                for (i in 1..AUTO_FINGERPRINT_CYCLES) {
                    val devices = scanner.scanOnce(lowPower = false)
                    val scanMap = mutableMapOf<String, Int>()
                    for (d in devices) {
                        scanMap[d.address] = d.rssi
                        if (d.name != null) nameMap[d.address] = d.name
                        if (d.category != ProximityScanner.DeviceCategory.UNKNOWN) categoryMap[d.address] = d.category
                        addressTypes[d.address] = d.addressType
                    }
                    rawScans.add(scanMap)
                    _autoFingerprintProgress.value = i
                }
                _autoFingerprintProgress.value = null

                val counts = mutableMapOf<String, Int>()
                for (scan in rawScans) for (addr in scan.keys) counts[addr] = (counts[addr] ?: 0) + 1
                Log.d(TAG, "Calibration scan: ${counts.size} unique devices in ${rawScans.size} scans")
                counts.entries.sortedByDescending { it.value }.take(20).forEach { (addr, seen) ->
                    val name = nameMap[addr]
                    val cat = categoryMap[addr] ?: ProximityScanner.DeviceCategory.UNKNOWN
                    val addrType = addressTypes[addr] ?: ProximityScanner.AddressType.PUBLIC
                    Log.d(TAG, "  $addr seen=$seen name=${name ?: "(unnamed)"} cat=$cat addr=$addrType")
                }

                // Use stable-address, non-mobile devices (Public + Random Static are trackable)
                val validAddresses = counts
                    .filter { it.value >= MIN_SIGHTINGS }
                    .filter { categoryMap[it.key] != ProximityScanner.DeviceCategory.MOBILE }
                    .filter { scanner.isStableAddress(addressTypes[it.key] ?: ProximityScanner.AddressType.PUBLIC) }
                    .keys
                Log.d(TAG, "Valid addresses: ${validAddresses.size} (stable, non-mobile, seen 2+)")

                val config = configStore.config.value
                val otherRoomMeans = config.rooms
                    .filter { it.id != roomId && it.beaconProfiles.isNotEmpty() }
                    .associate { room -> room.id to room.beaconProfiles.associate { it.address to it.meanRssi.toDouble() } }

                if (validAddresses.isEmpty()) {
                    Log.w(TAG, "Calibration failed: no named BLE devices found")
                    val totalDevices = counts.size
                    val mobileCount = counts.keys.count { categoryMap[it] == ProximityScanner.DeviceCategory.MOBILE }
                    val rpaCount = counts.keys.count { addressTypes[it] == ProximityScanner.AddressType.RPA }
                    _calibrationError.value = "No usable BLE devices found ($totalDevices seen, $mobileCount mobile, $rpaCount rotating). Need stationary devices with stable addresses (TVs, speakers, routers, access points)."
                } else {
                    val fingerprints = buildFingerprints(rawScans, validAddresses)
                    val allNames = nameMap.mapValues { it.value }
                    val profiles = computeBeaconProfiles(rawScans, validAddresses, allNames, otherRoomMeans)
                    val warnings = mutableListOf<String>()
                    val room = config.rooms.find { it.id == roomId }
                    val quality = assessQuality(room?.name ?: roomId, profiles, warnings)

                    configStore.update { cfg ->
                        val updated = cfg.rooms.map { r ->
                            if (r.id != roomId) return@map r
                            r.copy(fingerprints = fingerprints, beaconProfiles = profiles, calibrationQuality = quality)
                        }
                        cfg.copy(rooms = updated)
                    }

                    roomDetector.reset()
                    // Seed room if quality meets the room's detection policy
                    val updatedRoom = configStore.config.value.rooms.find { it.id == roomId }
                    if (updatedRoom != null && quality != CalibrationQuality.UNCALIBRATED) {
                        val allowWeak = updatedRoom.detectionPolicy == net.asksakis.massdroidv2.data.proximity.DetectionPolicy.RELAXED
                        if (quality == CalibrationQuality.GOOD || allowWeak) {
                            roomDetector.seedRoom(DetectedRoom(updatedRoom.id, updatedRoom.name, updatedRoom.playerId, updatedRoom.playerName))
                        }
                    }

                    Log.d(TAG, "Single-room calibration: ${room?.name}, ${fingerprints.size} fp, ${profiles.size} profiles, quality=$quality")
                    if (warnings.isNotEmpty()) warnings.forEach { Log.w(TAG, "  $it") }
                }
                onDone()
            } finally {
                _autoFingerprintProgress.value = null
                roomDetector.resume()
            }
        }
    }

    // region Auto Room Tuning

    data class RoomTrainingData(
        val roomId: String,
        val roomName: String,
        val rawScans: List<Map<String, Int>>,
        val names: Map<String, String?>,
        val categories: Map<String, ProximityScanner.DeviceCategory>,
        val addressTypes: Map<String, ProximityScanner.AddressType> = emptyMap()
    )

    data class TuningResult(
        val roomResults: Map<String, CalibrationQuality>,
        val warnings: List<String>
    )

    private val _autoFingerprintProgress = MutableStateFlow<Int?>(null)
    val autoFingerprintProgress: StateFlow<Int?> = _autoFingerprintProgress.asStateFlow()

    private val _tuningSnapshots = MutableStateFlow<List<RoomTrainingData>>(emptyList())
    val tuningSnapshots: StateFlow<List<RoomTrainingData>> = _tuningSnapshots.asStateFlow()

    private val _tuningStep = MutableStateFlow<String?>(null)
    val tuningStep: StateFlow<String?> = _tuningStep.asStateFlow()

    private val _tuningResult = MutableStateFlow<TuningResult?>(null)
    val tuningResult: StateFlow<TuningResult?> = _tuningResult.asStateFlow()

    fun collectRoomSnapshot(roomId: String, roomName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            roomDetector.suppress()
            try {
                _tuningStep.value = "Scanning $roomName..."
                val rawScans = mutableListOf<Map<String, Int>>()
                val nameMap = mutableMapOf<String, String?>()
                val categoryMap = mutableMapOf<String, ProximityScanner.DeviceCategory>()
                val addrTypeMap = mutableMapOf<String, ProximityScanner.AddressType>()
                _autoFingerprintProgress.value = 0

                for (i in 1..AUTO_FINGERPRINT_CYCLES) {
                    val devices = scanner.scanOnce(lowPower = false)
                    val scanMap = mutableMapOf<String, Int>()
                    for (d in devices) {
                        scanMap[d.address] = d.rssi
                        if (d.name != null) nameMap[d.address] = d.name
                        if (d.category != ProximityScanner.DeviceCategory.UNKNOWN) categoryMap[d.address] = d.category
                        addrTypeMap[d.address] = d.addressType
                    }
                    rawScans.add(scanMap)
                    _autoFingerprintProgress.value = i
                }

                val training = RoomTrainingData(roomId, roomName, rawScans, nameMap, categoryMap, addrTypeMap)
                _tuningSnapshots.value = _tuningSnapshots.value + training
                Log.d(TAG, "Training data for $roomName: ${rawScans.size} scans, ${nameMap.size} devices seen")
                onDone()
            } finally {
                _autoFingerprintProgress.value = null
                _tuningStep.value = null
                roomDetector.resume()
            }
        }
    }

    @Suppress("CyclomaticComplexity")
    fun applyTuning() {
        viewModelScope.launch {
            val allTraining = _tuningSnapshots.value
            if (allTraining.size < 2) return@launch

            val roomResults = mutableMapOf<String, CalibrationQuality>()
            val warnings = mutableListOf<String>()

            try {
                _tuningStep.value = "Computing fingerprints..."

                val allNames = allTraining.flatMap { it.names.entries }.associate { it.key to it.value }
                val allCategories = allTraining.flatMap { it.categories.entries }.associate { it.key to it.value }

                val allAddressTypes = allTraining.flatMap { it.addressTypes.entries }
                    .associate { it.key to it.value }
                val validAddresses = allTraining.flatMap { training ->
                    val counts = mutableMapOf<String, Int>()
                    for (scan in training.rawScans) {
                        for (addr in scan.keys) counts[addr] = (counts[addr] ?: 0) + 1
                    }
                    counts.filter { it.value >= MIN_SIGHTINGS }
                        .filter { allCategories[it.key] != ProximityScanner.DeviceCategory.MOBILE }
                        .filter { scanner.isStableAddress(allAddressTypes[it.key] ?: ProximityScanner.AddressType.PUBLIC) }
                        .keys
                }.toSet()

                configStore.update { config ->
                    val roomMeans = allTraining.associate { training ->
                        training.roomId to computeBeaconMeans(training.rawScans, validAddresses)
                    }

                    val updatedRooms = config.rooms.map { room ->
                        val training = allTraining.find { it.roomId == room.id } ?: return@map room

                        val fingerprints = buildFingerprints(training.rawScans, validAddresses)
                        val otherRoomMeans = roomMeans.filter { it.key != room.id }
                        val profiles = computeBeaconProfiles(
                            training.rawScans, validAddresses, allNames, otherRoomMeans
                        )

                        val quality = assessQuality(room.name, profiles, warnings)
                        roomResults[room.id] = quality

                        Log.d(TAG, "Tuned ${room.name}: ${fingerprints.size} fp, ${profiles.size} profiles, quality=$quality")
                        profiles.sortedByDescending { it.weight }.take(6).forEach { p ->
                            Log.d(TAG, "  ${p.name}: mean=${p.meanRssi}, var=${String.format("%.1f", p.variance)}, " +
                                "vis=${String.format("%.0f%%", p.visibilityRate * 100)}, " +
                                "disc=${String.format("%.1f", p.discriminationScore)}, " +
                                "w=${String.format("%.2f", p.weight)}")
                        }

                        room.copy(fingerprints = fingerprints, beaconProfiles = profiles, calibrationQuality = quality)
                    }
                    config.copy(rooms = updatedRooms)
                }

                roomDetector.reset()
                val lastTraining = allTraining.lastOrNull()
                if (lastTraining != null) {
                    val lastRoom = configStore.config.value.rooms.find { it.id == lastTraining.roomId }
                    if (lastRoom != null) {
                        roomDetector.seedRoom(DetectedRoom(lastRoom.id, lastRoom.name, lastRoom.playerId, lastRoom.playerName))
                    }
                }

                Log.d(TAG, "Calibration complete: ${roomResults.values.groupBy { it }.mapValues { it.value.size }}")
            } finally {
                _tuningSnapshots.value = emptyList()
                _tuningStep.value = null
                _tuningResult.value = TuningResult(roomResults, warnings)
                roomDetector.resume()
            }
        }
    }

    private fun assessQuality(
        roomName: String,
        profiles: List<BeaconProfile>,
        warnings: MutableList<String>
    ): CalibrationQuality {
        if (profiles.isEmpty()) {
            warnings.add("$roomName: no usable beacons found")
            return CalibrationQuality.WEAK
        }

        val usableCount = profiles.count { it.weight > MIN_WEIGHT }
        val avgVisibility = profiles.map { it.visibilityRate }.average()
        val discriminativeCount = profiles.count { it.discriminationScore > DISCRIMINATIVE_THRESHOLD }
        val floorRatio = profiles.count { it.weight <= MIN_WEIGHT + 0.01 }.toDouble() / profiles.size

        var isGood = true

        if (usableCount < MIN_USABLE_PROFILES) {
            warnings.add("$roomName: only $usableCount usable beacons (need $MIN_USABLE_PROFILES)")
            isGood = false
        }
        if (avgVisibility < MIN_AVG_VISIBILITY) {
            warnings.add("$roomName: low beacon visibility (${String.format("%.0f%%", avgVisibility * 100)})")
            isGood = false
        }
        if (discriminativeCount < MIN_DISCRIMINATIVE_BEACONS) {
            warnings.add("$roomName: no strongly discriminative beacons")
            isGood = false
        }
        if (floorRatio > MAX_FLOOR_RATIO) {
            warnings.add("$roomName: most beacon weights at minimum")
            isGood = false
        }

        return if (isGood) CalibrationQuality.GOOD else CalibrationQuality.WEAK
    }

    fun clearTuning() {
        _tuningSnapshots.value = emptyList()
        _tuningStep.value = null
        _tuningResult.value = null
        roomDetector.resume()
    }

    fun dismissTuningResult() {
        _tuningResult.value = null
    }

    // endregion

    // region Fingerprint building

    private fun buildFingerprints(
        rawScans: List<Map<String, Int>>,
        validAddresses: Set<String>
    ): List<RoomFingerprint> {
        val filtered = rawScans.map { scan -> scan.filterKeys { it in validAddresses } }
            .filter { it.isNotEmpty() }
        if (filtered.isEmpty()) return emptyList()

        val groupSize = (filtered.size.toFloat() / FINGERPRINTS_PER_ROOM).coerceAtLeast(1f).toInt()
        val now = System.currentTimeMillis()

        return filtered.chunked(groupSize).mapIndexed { idx, group ->
            val merged = mutableMapOf<String, MutableList<Int>>()
            for (scan in group) {
                for ((addr, rssi) in scan) {
                    merged.getOrPut(addr) { mutableListOf() }.add(rssi)
                }
            }
            RoomFingerprint(
                id = UUID.randomUUID().toString(),
                label = "scan-${idx + 1}",
                samples = merged.mapValues { (_, values) -> values.average().toInt() },
                capturedAtMs = now
            )
        }
    }

    private fun computeBeaconMeans(
        rawScans: List<Map<String, Int>>,
        validAddresses: Set<String>
    ): Map<String, Double> {
        val accum = mutableMapOf<String, MutableList<Int>>()
        for (scan in rawScans) {
            for ((addr, rssi) in scan) {
                if (addr in validAddresses) accum.getOrPut(addr) { mutableListOf() }.add(rssi)
            }
        }
        return accum.mapValues { (_, values) -> values.average() }
    }

    private fun computeBeaconProfiles(
        rawScans: List<Map<String, Int>>,
        validAddresses: Set<String>,
        allNames: Map<String, String?>,
        otherRoomMeans: Map<String, Map<String, Double>>
    ): List<BeaconProfile> {
        val totalScans = rawScans.size
        if (totalScans == 0) return emptyList()

        val accum = mutableMapOf<String, MutableList<Int>>()
        for (scan in rawScans) {
            for ((addr, rssi) in scan) {
                if (addr in validAddresses) accum.getOrPut(addr) { mutableListOf() }.add(rssi)
            }
        }

        return accum.map { (addr, rssiValues) ->
            val mean = rssiValues.average()
            val variance = if (rssiValues.size > 1) {
                rssiValues.map { (it - mean) * (it - mean) }.average()
            } else 0.0
            val visibilityRate = rssiValues.size.toDouble() / totalScans
            val bestOtherMean = otherRoomMeans.values.mapNotNull { it[addr] }.maxOrNull() ?: -100.0
            val discriminationScore = mean - bestOtherMean

            val stability = 1.0 / (1.0 + sqrt(variance) / 4.0)
            val discriminationBoost = 1.0 + discriminationScore.coerceAtLeast(0.0) / 20.0
            val weight = (visibilityRate * stability * discriminationBoost).coerceIn(MIN_WEIGHT, MAX_WEIGHT)

            BeaconProfile(addr, allNames[addr] ?: addr, mean.toInt(), variance, visibilityRate, discriminationScore, weight)
        }
    }

    // endregion
}
