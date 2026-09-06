package net.asksakis.massdroidv2.data.proximity

import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import net.asksakis.massdroidv2.util.LogRedaction

private const val TAG = "RoomDetector"
/** A Wi-Fi association is a hard match; reported as such where a confidence is expected. */
private const val WIFI_OVERRIDE_CONFIDENCE = 1.0
private const val TOP_SCORE_ROOMS = 5
private const val COVERAGE_ANCHORS = 8
private const val STAY_BIAS_FACTOR = 1.08
private const val NO_MATCH_CLEAR_THRESHOLD = 10
private const val SCORE_MARGIN_SCALE = 10.0

/**
 * Whether an empty read should be excused as scanner warmup instead of counted as "not
 * near any known room".
 *
 * ONE-SHOT per scanner gap: [lastWarmupGraceAtMs] > [lastScanActivityMs] means this gap
 * was already excused, so the next empty read must count. Without that condition the
 * grace re-armed itself every [graceMs] (the old code stamped lastScanActivityMs on each
 * excuse), which wiped the no-match streak every few cycles and meant leaving the house
 * never cleared the room.
 */
@VisibleForTesting
internal fun shouldExcuseAsWarmup(
    nowMs: Long,
    lastScanActivityMs: Long,
    lastWarmupGraceAtMs: Long,
    graceMs: Long
): Boolean = lastScanActivityMs > 0 &&
    lastWarmupGraceAtMs <= lastScanActivityMs &&
    nowMs - lastScanActivityMs > graceMs

sealed interface DetectResult {
    data class Confirmed(val room: DetectedRoom) : DetectResult
    data class Borderline(
        val winner: DetectedRoom,
        val confidence: Double,
        val margin: Double,
        val reason: String
    ) : DetectResult
    data object NoCoverage : DetectResult
    data object NoDecision : DetectResult
}

/** Room classifier using weighted Gaussian likelihood scoring over anchor fingerprints. */
@Singleton
class RoomDetector @Inject constructor() {
    data class WifiMatchContext(
        val bssid: String? = null,
        val ssid: String? = null
    )

    private data class WifiOverrideMatch(
        val room: RoomConfig,
        val detected: DetectedRoom,
        val changed: Boolean
    )

    data class DetectionStatus(val roomId: String?, val confidence: Double, val margin: Double, val matched: Int, val expected: Int)

    private val _currentRoom = MutableStateFlow<DetectedRoom?>(null)
    val currentRoom: StateFlow<DetectedRoom?> = _currentRoom.asStateFlow()
    private val _lastDetection = MutableStateFlow<DetectionStatus?>(null)
    val lastDetection: StateFlow<DetectionStatus?> = _lastDetection.asStateFlow()

    /**
     * When [lastDetection] was last written. Empty and no-coverage reads do not write it, so
     * a caller judging "has the detector settled since X" must check this, not just that the
     * last winner matches: otherwise a stale agreement from before a walk counts as settled.
     */
    var lastDetectionAtMs = 0L
        private set

    private fun recordDetection(status: DetectionStatus) {
        _lastDetection.value = status
        lastDetectionAtMs = System.currentTimeMillis()
    }

    private var consecutiveWinnerId: String? = null
    private var consecutiveWinCount = 0
    var noMatchStreak = 0
        private set
    /** Public read (the controller's away-mode timer uses it); the setter is opened only for tests. */
    var lastConfirmedAtMs = 0L
        @androidx.annotation.VisibleForTesting internal set
    /** Last scan that actually returned devices. Only a real read updates this. */
    private var lastScanActivityMs = 0L

    /**
     * When the warmup grace was last granted. Compared against [lastScanActivityMs] to
     * keep the grace ONE-SHOT per scanner gap (see [handleNoMatch]).
     */
    private var lastWarmupGraceAtMs = 0L
    private val CONFIRM_GRACE_MS = 15_000L
    private val SCANNER_WARMUP_GRACE_MS = 30_000L
    @Volatile private var suppressed = false

    fun suppress() { suppressed = true }
    fun resume() { suppressed = false }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RoomDetector must run on the main thread" }
    }

    /**
     * Update the stored copy of the room we hold (speaker or name changed in the config)
     * without touching detection state. No-op unless [room] is the held room and differs.
     */
    fun refreshHeldRoom(room: DetectedRoom) {
        assertMainThread()
        val held = _currentRoom.value ?: return
        if (held.roomId != room.roomId || held == room) return
        _currentRoom.value = room
        Log.d(TAG, "Held room re-bound: ${room.roomName} -> ${room.playerName}")
    }

    /** Set current room directly (e.g., after calibration) without going through confidence logic */
    fun seedRoom(room: DetectedRoom) {
        assertMainThread()
        _currentRoom.value = room
        consecutiveWinnerId = room.roomId
        consecutiveWinCount = 10
        Log.d(TAG, "Seeded room: ${room.roomName}")
    }

    fun detect(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        wifi: WifiMatchContext = WifiMatchContext()
    ): DetectedRoom? = when (val result = detectDetailed(scanResults, config, motionActive, wifi)) {
        is DetectResult.Confirmed -> result.room
        else -> null
    }

    /**
     * Only the Wi-Fi override, for a read whose BLE side carries no evidence (a cold buffer
     * that heard nothing). A Wi-Fi-only room must still be confirmed on such a read; skipping
     * it left those rooms unconfirmed on every screen-off path.
     */
    fun detectWifiOnly(config: ProximityConfig, wifi: WifiMatchContext): DetectResult {
        assertMainThread()
        if (suppressed || config.rooms.isEmpty()) return DetectResult.NoDecision
        return applyWifiOverride(config, wifi) ?: DetectResult.NoDecision
    }

    /**
     * Connected-Wi-Fi match for a Wi-Fi-only room. Commits on its own evidence: the BLE commit
     * gate ([detectDetailed]'s `commitRoomChange`) exists because a half-filled BLE buffer is
     * not yet a picture, and the access point the phone is connected to does not depend on it.
     * Null when no Wi-Fi room matches.
     */
    private fun applyWifiOverride(config: ProximityConfig, wifi: WifiMatchContext): DetectResult? {
        val match = resolveWifiOverride(config.rooms, wifi) ?: return null
        consecutiveWinnerId = match.room.id
        consecutiveWinCount = maxOf(consecutiveWinCount, 1)
        recordDetection(DetectionStatus(match.room.id, WIFI_OVERRIDE_CONFIDENCE, 0.0, 0, 0))
        Log.d(TAG, "Wi-Fi AP override: ${match.room.name} via ${LogRedaction.networkId(wifi.bssid ?: wifi.ssid)}")
        lastConfirmedAtMs = System.currentTimeMillis()
        if (!match.changed) return DetectResult.NoDecision
        _currentRoom.value = match.detected
        return DetectResult.Confirmed(match.detected)
    }

    /**
     * @param commitRoomChange whether a passing BLE decision may be STORED. False while the
     * caller's BLE picture is not trustworthy yet (buffer still filling, batch too small);
     * the decision then comes back as `Borderline("commit-withheld")`. `Confirmed` always
     * means stored. The Wi-Fi override is not gated by this, see [applyWifiOverride].
     */
    fun detectDetailed(
        scanResults: Map<String, Int>,
        config: ProximityConfig,
        motionActive: Boolean = false,
        wifi: WifiMatchContext = WifiMatchContext(),
        commitRoomChange: Boolean = true
    ): DetectResult {
        assertMainThread()
        if (suppressed || config.rooms.isEmpty()) return DetectResult.NoDecision
        applyWifiOverride(config, wifi)?.let { return it }

        if (scanResults.isEmpty()) {
            handleNoMatch("empty scan")
            return DetectResult.NoCoverage
        }
        lastScanActivityMs = System.currentTimeMillis()

        val eligibleRooms = eligibleBleRooms(config.rooms)
        if (eligibleRooms.isEmpty()) return DetectResult.NoDecision

        val roomPrimaryBleAnchors = buildPrimaryAnchorSets(eligibleRooms)
        val roomFits = buildRoomFits(
            eligibleRooms = eligibleRooms,
            scanResults = scanResults,
            roomPrimaryBleAnchors = roomPrimaryBleAnchors,
            wifi = wifi
        )

        if (roomFits.isEmpty()) {
            val liveBle = scanResults.keys.filter { !it.startsWith("wifi:") }.sorted()
            val coverageDetails = eligibleRooms.joinToString(" | ") { room ->
                val topBle = roomPrimaryBleAnchors[room.id].orEmpty().sorted()
                val matched = liveBle.filter { it in topBle }
                "${room.name}: matched=${matched.size}/${topBle.size}, live=$matched, top=$topBle"
            }
            handleNoMatch("devices=${scanResults.size}, no coverage, liveBle=$liveBle, rooms=[$coverageDetails]")
            return DetectResult.NoCoverage
        }
        noMatchStreak = 0

        val rankedRooms = roomFits.sortedByDescending { it.score }
        val winner = rankedRooms.first()
        val runnerUp = rankedRooms.getOrNull(1)
        val winnerId = winner.roomId
        val winnerScore = winner.score
        val runnerUpScore = runnerUp?.score ?: (winnerScore - 0.5)
        val margin = (winnerScore - runnerUpScore) * SCORE_MARGIN_SCALE
        val confidence = VectorRoomScorer.topRoomProbability(rankedRooms)

        val winnerRoom = config.rooms.first { it.id == winnerId }
        val topRoomFits = rankedRooms
            .take(TOP_SCORE_ROOMS)
            .joinToString { fit -> formatRoomFitSummary(config, fit) }

        Log.d(
            TAG,
            "Fit: winner=${winnerRoom.name}, conf=${String.format("%.2f", confidence)}, " +
                "margin=${String.format("%.1f", margin)}, matched=${winner.matchedAnchors}/${winner.expectedAnchors}, " +
                "avgDelta=${formatAvgDelta(winner.avgDelta)}, primaryLocal=${winner.matchedPrimaryLocalAnchors}/${winner.expectedPrimaryLocalAnchors}, devices=${scanResults.size}, " +
                "policy=${winnerRoom.detectionPolicy}, top$TOP_SCORE_ROOMS=$topRoomFits"
        )

        recordDetection(DetectionStatus(winnerId, confidence, margin, winner.matchedAnchors, winner.expectedAnchors))

        val winnerDetected = winnerRoom.toDetectedRoom()
        val candidateWinCount = if (winnerId == consecutiveWinnerId) consecutiveWinCount + 1 else 1
        val strongFit = RoomDecisionPolicy.isStrongFit(winner)

        if (strongFit) {
            Log.d(
                TAG,
                "Fit: strong-match fast confirm for ${winnerRoom.name} " +
                    "(${formatStrongFitSummary(winner)})"
            )
        }
        val decision = RoomDecisionPolicy.evaluate(
            winnerRoom = winnerRoom,
            winner = winner,
            runnerUpPresent = runnerUp != null,
            winnerDetected = winnerDetected,
            confidence = confidence,
            margin = margin,
            startupDetection = _currentRoom.value == null,
            motionActive = motionActive,
            candidateWinCount = candidateWinCount,
            thresholds = winnerRoom.confirmThresholds()
        )
        if (decision !is DetectResult.Confirmed) {
            consecutiveWinnerId = winnerId
            consecutiveWinCount = candidateWinCount
            return decision
        }

        consecutiveWinnerId = winnerId
        consecutiveWinCount = candidateWinCount
        val changed = _currentRoom.value?.roomId != winnerRoom.id
        if (!changed) {
            // Re-confirming the room we hold is still a confirmation: this stamp is the grace
            // that keeps a passing no-coverage read from clearing a room you are sitting in.
            if (commitRoomChange) lastConfirmedAtMs = System.currentTimeMillis()
            return DetectResult.NoDecision
        }
        if (!commitRoomChange) {
            // The evidence passed but the caller asked us not to act on it (buffer still
            // filling, batch too small). Say so, rather than returning Confirmed for a
            // room we did not store: callers switched the player on that answer, and
            // because nothing was stored every later read looked like a change again.
            return DetectResult.Borderline(
                winner = winnerDetected,
                confidence = confidence,
                margin = margin,
                reason = "commit-withheld"
            )
        }
        _currentRoom.value = winnerDetected
        lastConfirmedAtMs = System.currentTimeMillis()
        return DetectResult.Confirmed(winnerDetected)
    }

    fun reset() {
        assertMainThread()
        resetConfidence()
        noMatchStreak = 0
        _currentRoom.value = null
    }

    fun resetNoMatchStreak() {
        noMatchStreak = 0
    }

    private fun handleNoMatch(reason: String) {
        val now = System.currentTimeMillis()
        // Grace period: ignore empty scans shortly after confirming a room
        if (_currentRoom.value != null && now - lastConfirmedAtMs < CONFIRM_GRACE_MS) {
            Log.d(TAG, "Fit: skip ($reason, confirm grace)")
            return
        }
        // Scanner warmup grace: after a doze/long gap the buffer can be empty because the
        // scanner has not refilled yet, not because we left. Grant that benefit ONCE per
        // gap, and do not touch the streak.
        //
        // It used to set lastScanActivityMs = now and zero the streak, which made the
        // grace re-arm itself every SCANNER_WARMUP_GRACE_MS: with ~12 s detection cycles
        // the streak was wiped every third cycle and never reached
        // NO_MATCH_CLEAR_THRESHOLD, so leaving the house never cleared the room (observed:
        // max streak 3, zero clears in a day). That stayed hidden while an unfiltered scan
        // kept returning foreign devices everywhere, which refreshed lastScanActivityMs
        // each cycle and meant this branch never ran.
        if (_currentRoom.value != null &&
            shouldExcuseAsWarmup(now, lastScanActivityMs, lastWarmupGraceAtMs, SCANNER_WARMUP_GRACE_MS)
        ) {
            Log.d(TAG, "Fit: skip ($reason, scanner warmup after ${(now - lastScanActivityMs) / 1000}s gap)")
            lastWarmupGraceAtMs = now
            return
        }
        noMatchStreak++
        if (noMatchStreak >= NO_MATCH_CLEAR_THRESHOLD && _currentRoom.value != null) {
            Log.d(TAG, "Fit: left all rooms ($reason x$noMatchStreak)")
            resetConfidence()
            _currentRoom.value = null
        } else {
            Log.d(TAG, "Fit: skip ($reason, streak=$noMatchStreak)")
        }
    }

    private fun resolveWifiOverride(
        rooms: List<RoomConfig>,
        wifi: WifiMatchContext
    ): WifiOverrideMatch? {
        val wifiOnlyMatches = rooms.filter { room -> room.matchesWifi(wifi) }
        if (wifiOnlyMatches.isEmpty()) return null
        if (wifiOnlyMatches.size > 1) {
            val currentRoomMatch = wifiOnlyMatches.find { it.id == _currentRoom.value?.roomId }
            if (currentRoomMatch == null) {
                Log.w(
                    TAG,
                    "Multiple rooms matched connected Wi-Fi ${LogRedaction.networkId(wifi.bssid ?: wifi.ssid)}: " +
                        wifiOnlyMatches.joinToString { it.name }
                )
                return null
            }
        }
        val winnerRoom = wifiOnlyMatches.find { it.id == _currentRoom.value?.roomId } ?: wifiOnlyMatches.first()
        return WifiOverrideMatch(
            room = winnerRoom,
            detected = winnerRoom.toDetectedRoom(),
            changed = _currentRoom.value?.roomId != winnerRoom.id
        )
    }

    private fun eligibleBleRooms(rooms: List<RoomConfig>): List<RoomConfig> =
        rooms.filter { room ->
            if (room.wifiMatchMode != null) return@filter false
            if (room.beaconProfiles.isEmpty()) return@filter false
            // WEAK rooms are no longer excluded as a hard capability gate. Calibration quality is a
            // separability hint (see RoomSeparability / confusion matrix); a weakly-separable room
            // still participates in scoring but faces a stiffer confirm bar (see confirmThresholds)
            // plus the existing confidence/margin/consecutive-win hysteresis. This is what lets a
            // room with one strong nearby anchor (e.g. JBL at 2m) win even when its shared anchors
            // overlap a neighbour. Only an uncalibrated room (no usable fingerprints) is dropped.
            if (room.fingerprints.isEmpty()) return@filter false
            if (room.calibrationQuality == CalibrationQuality.UNCALIBRATED) return@filter false
            true
        }

    private fun buildPrimaryAnchorSets(rooms: List<RoomConfig>): Map<String, Set<String>> =
        rooms.associate { room ->
            val rankedProfiles = rankBeaconProfilesForDetection(
                room.beaconProfiles.filter { !it.anchorKey.startsWith("wifi:") }
            )
            room.id to rankedProfiles.take(COVERAGE_ANCHORS).map { it.anchorKey }.toSet()
        }

    private fun buildRoomFits(
        eligibleRooms: List<RoomConfig>,
        scanResults: Map<String, Int>,
        roomPrimaryBleAnchors: Map<String, Set<String>>,
        wifi: WifiMatchContext
    ): List<RoomFit> {
        val roomFits = mutableListOf<RoomFit>()
        for (room in eligibleRooms) {
            val primaryAnchors = roomPrimaryBleAnchors[room.id] ?: emptySet()
            val bleMatched = scanResults.keys.count { it in primaryAnchors }
            if (bleMatched < room.detectionPolicy.rules().minBleCoverage) continue

            val fit = VectorRoomScorer.score(room.id, scanResults, room.fingerprints, room.beaconProfiles, primaryAnchors)
            roomFits.add(fit.copy(score = adjustedRoomScore(room, fit.score, wifi)))
        }
        return roomFits
    }

    private fun adjustedRoomScore(
        room: RoomConfig,
        rawScore: Double,
        wifi: WifiMatchContext
    ): Double {
        val stayAdjustedScore = if (_currentRoom.value?.roomId == room.id) rawScore * STAY_BIAS_FACTOR else rawScore
        return when {
            room.matchesWifi(wifi) -> stayAdjustedScore + 0.05
            wifi.bssid == null && wifi.ssid == null -> stayAdjustedScore
            else -> stayAdjustedScore - 0.05
        }
    }

    private fun RoomConfig.matchesWifi(wifi: WifiMatchContext): Boolean {
        val mode = wifiMatchMode ?: return false
        return when (mode) {
            WifiMatchMode.BSSID ->
                !connectedBssid.isNullOrBlank() && !wifi.bssid.isNullOrBlank() &&
                    connectedBssid.equals(wifi.bssid, ignoreCase = true)
            WifiMatchMode.SSID ->
                !connectedSsid.isNullOrBlank() && !wifi.ssid.isNullOrBlank() &&
                    connectedSsid.equals(wifi.ssid, ignoreCase = true)
        }
    }

    private fun RoomConfig.toDetectedRoom(): DetectedRoom =
        DetectedRoom(id, name, playerId, playerName)

    private fun formatRoomFitSummary(config: ProximityConfig, fit: RoomFit): String {
        val roomName = config.rooms.first { it.id == fit.roomId }.name
        return "$roomName(m=${fit.matchedAnchors}/${fit.expectedAnchors},d=${formatAvgDelta(fit.avgDelta)},s=${String.format("%.2f", fit.score)})"
    }

    private fun formatStrongFitSummary(fit: RoomFit): String =
        "matched=${fit.matchedAnchors}/${fit.expectedAnchors}, " +
            "primaryLocal=${fit.matchedPrimaryLocalAnchors}/${fit.expectedPrimaryLocalAnchors}, " +
            "avgDelta=${formatAvgDelta(fit.avgDelta)}"

    private fun formatAvgDelta(value: Double): String =
        if (value.isFinite()) String.format("%.1f", value) else "n/a"

    private fun resetConfidence() {
        consecutiveWinnerId = null
        consecutiveWinCount = 0
    }

}
