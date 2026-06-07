package net.asksakis.massdroidv2.data.sendspin

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AcousticCoord"

/**
 * Progress event emitted by [AcousticCalibrationCoordinator.runBtCalibration]
 * while a calibration is in flight. UI surfaces translate it into a
 * "Phase X / Y" + tone progress bar.
 */
sealed class CalibrationProgress {
    /** Top-level phase transition: e.g. (1, 2, "Measuring mic path"). */
    data class Phase(val current: Int, val total: Int, val label: String) : CalibrationProgress()
    /** Per-tone progress within the current phase. */
    data class Tone(val current: Int, val total: Int) : CalibrationProgress()
}

/** Result of a full BT calibration run. */
sealed class CalibrationOutcome {
    /**
     * BT route compensation derived from a fully measured mic_path.
     * [compensationUs] is the one-way output delay that gets stored as the
     * acoustic correction. [micPathFreshlyMeasured] is true when this run
     * had to re-measure the phone-speaker reference (and re-cache it);
     * false when a cached value was reused.
     */
    data class Success(
        val compensationUs: Long,
        val quality: String,
        val micPathUs: Long,
        val btRoundTripUs: Long,
        val micPathFreshlyMeasured: Boolean,
    ) : CalibrationOutcome()

    /**
     * Calibration could not be completed (routing override failed, measurement
     * came back FAILED, or numbers were out of sane range). [fallbackAvailable]
     * tells the dialog whether to fall back to the single-pass legacy mode
     * (compensation = roundTrip minus Oboe input latency) on the next attempt.
     */
    data class Failure(val reason: String, val fallbackAvailable: Boolean = false) : CalibrationOutcome()
}

/**
 * Shared facade between the UI layer and the acoustic calibration plumbing.
 *
 * The same surface is consumed by both NowPlayingViewModel (Now Playing's
 * player-settings sheet) and HomeViewModel (Players screen's per-player
 * settings dialog). Centralising it here keeps route detection, DataStore
 * write paths and the SendspinManager push in one place so both screens
 * always see consistent calibration state.
 *
 * **Algorithm.** A BT calibration is a two-pass measurement:
 * 1. Force-route the chirp output to the phone built-in speaker via Oboe's
 *    `setDeviceId(builtInSpeakerId)`. Measure roundTrip plus the Oboe
 *    output timestamp (which is accurate for in-phone DACs). Compute
 *    `mic_path = roundTrip - outputHAL` and cache it in DataStore.
 * 2. Run the chirp on the default output (BT). Measure bt_roundTrip.
 *    Compute the stored compensation as `bt_roundTrip - mic_path`.
 *
 * Pass 1 is skipped when a previously cached `mic_path` is available (the
 * mic chain is a property of the phone, not the BT speaker). The cache is
 * cleared via [resetMicPath]; users can also force a re-measurement by
 * resetting the cached value before recalibrating a BT route.
 *
 * Public-app device-compat: if the framework silently ignores the routing
 * request (Oboe `routedOutputDeviceId` != requested), or the resulting
 * measurement falls outside the sane mic_path range, the run is reported
 * as a failure with `fallbackAvailable = true` so the dialog can revert
 * to the single-pass legacy algorithm.
 *
 * Saves/resets run on an internal IO scope so dismissing the dialog
 * mid-save does not cancel the DataStore write.
 */
@Singleton
class AcousticCalibrationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    val calibrator: NativeAcousticCalibrator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val acousticRouteCalibrations: Flow<Map<String, AcousticRouteCalibration>> =
        settingsRepository.acousticRouteCalibrations

    val acousticMicPathUs: Flow<Long> = settingsRepository.acousticMicPathUs

    // One-time-per-session prompt: when this device joins a group and has no
    // stored built-in-speaker calibration, suggest running one (the UI shows a
    // confirm-to-calibrate dialog; the user opts in, never silent).
    @Volatile private var speakerCalSuggestedThisSession = false
    private val _speakerCalSuggested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val speakerCalSuggested: SharedFlow<Unit> = _speakerCalSuggested.asSharedFlow()

    init {
        scope.launch {
            sendspinManager.groupJoined.collect {
                if (!speakerCalSuggestedThisSession && !hasSpeakerCalibration()) {
                    speakerCalSuggestedThisSession = true
                    _speakerCalSuggested.tryEmit(Unit)
                }
            }
        }
    }

    /**
     * True when the current AudioTrack output is routed to a Bluetooth sink.
     * Reads the most recent routed device snapshot from [SendspinManager];
     * callers should not rely on this for live updates (the device-routed
     * callback in SendspinAudioController is the canonical change signal).
     */
    fun isBtRoute(): Boolean = when (sendspinManager.getRoutedDeviceType()) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    fun getBtRouteName(): String =
        sendspinManager.getRoutedDeviceProductName() ?: "Bluetooth"

    /**
     * Per-route storage key for the calibration map. Keyed by BT device
     * product name so different speakers (car HU, home BT speaker, headphones)
     * keep independent corrections without bleeding into each other.
     */
    fun getBtRouteKey(): String =
        "bt:${sendspinManager.getRoutedDeviceProductName() ?: "unknown"}"

    fun isPlaybackActive(): Boolean =
        playerRepository.selectedPlayer.value?.state == PlaybackState.PLAYING

    fun pauseForCalibration() {
        val playerId = playerRepository.selectedPlayer.value?.playerId ?: return
        scope.launch {
            runCatching { playerRepository.pause(playerId) }
        }
    }

    fun resumeAfterCalibration() {
        val playerId = playerRepository.selectedPlayer.value?.playerId ?: return
        scope.launch {
            runCatching { playerRepository.play(playerId) }
        }
    }

    /**
     * Drive a full BT calibration end-to-end. Emits [CalibrationProgress]
     * via [onProgress] (Main thread is safe to update Compose state from)
     * and returns [CalibrationOutcome.Success] with a persisted compensation
     * on success, or [CalibrationOutcome.Failure] when the algorithmic
     * preconditions are not met. The caller (calibration dialog) is
     * responsible for surfacing the outcome to the user and, on
     * `fallbackAvailable = true`, optionally retrying with the legacy
     * single-pass measurement.
     */
    suspend fun runBtCalibration(
        onProgress: (CalibrationProgress) -> Unit
    ): CalibrationOutcome {
        val cachedMicPath = settingsRepository.acousticMicPathUs.first()
        val needMicPathPass = cachedMicPath <= 0L

        val totalPhases = if (needMicPathPass) 2 else 1

        val micPathUs: Long
        val freshlyMeasured: Boolean
        if (needMicPathPass) {
            onProgress(CalibrationProgress.Phase(1, totalPhases, "Measuring mic path"))
            val builtInId = findBuiltInSpeakerId()
                ?: return CalibrationOutcome.Failure(
                    "No built-in speaker on this device (cannot measure mic path).",
                    fallbackAvailable = true,
                )

            calibrator.onProgress = { idx, total -> onProgress(CalibrationProgress.Tone(idx, total)) }
            val phoneResult = runCatching {
                calibrator.measureRoundTrip(maxDelayMs = 150, outputDeviceId = builtInId)
            }.getOrNull()
            calibrator.onProgress = null

            if (phoneResult == null
                || phoneResult.quality == NativeAcousticCalibrator.Quality.FAILED) {
                return CalibrationOutcome.Failure(
                    "Phone speaker reference pass failed.",
                    fallbackAvailable = true,
                )
            }
            if (phoneResult.routedOutputDeviceId != builtInId) {
                Log.w(TAG, "Routing override ignored: requested=$builtInId actual=${phoneResult.routedOutputDeviceId}")
                return CalibrationOutcome.Failure(
                    "Could not route to the built-in speaker (device blocked the override).",
                    fallbackAvailable = true,
                )
            }
            if (phoneResult.outputHALUs <= 0L) {
                return CalibrationOutcome.Failure(
                    "Oboe output timestamp unavailable on this device.",
                    fallbackAvailable = true,
                )
            }

            // mic_path = roundTrip - outputHAL. Air time mic-to-phone-speaker
            // (same device, ~5-15cm) is sub-millisecond and ignored.
            val rawMicPath = phoneResult.roundTripUs - phoneResult.outputHALUs
            if (rawMicPath !in MIN_MIC_PATH_US..MAX_MIC_PATH_US) {
                return CalibrationOutcome.Failure(
                    "Mic path measurement out of range (${rawMicPath / 1000}ms).",
                    fallbackAvailable = true,
                )
            }
            Log.d(TAG, "Mic path measured: ${rawMicPath / 1000}ms (roundTrip=${phoneResult.roundTripUs / 1000}ms outputHAL=${phoneResult.outputHALUs / 1000}ms)")
            settingsRepository.setAcousticMicPathUs(rawMicPath)
            micPathUs = rawMicPath
            freshlyMeasured = true
        } else {
            micPathUs = cachedMicPath
            freshlyMeasured = false
            Log.d(TAG, "Using cached mic_path: ${micPathUs / 1000}ms")
        }

        // Phase 2: BT route measurement using the default routing (which is
        // the active BT output when this method is called).
        onProgress(CalibrationProgress.Phase(totalPhases, totalPhases, "Measuring BT speaker"))
        calibrator.onProgress = { idx, total -> onProgress(CalibrationProgress.Tone(idx, total)) }
        val btResult = runCatching {
            calibrator.measureRoundTrip(maxDelayMs = 500, outputDeviceId = 0)
        }.getOrNull()
        calibrator.onProgress = null

        if (btResult == null
            || btResult.quality == NativeAcousticCalibrator.Quality.FAILED) {
            return CalibrationOutcome.Failure(
                "BT speaker measurement failed.",
                fallbackAvailable = false,
            )
        }

        val compensation = (btResult.roundTripUs - micPathUs).coerceIn(0L, MAX_COMPENSATION_US)
        if (btResult.roundTripUs < micPathUs) {
            return CalibrationOutcome.Failure(
                "BT round trip (${btResult.roundTripUs / 1000}ms) shorter than mic path " +
                    "(${micPathUs / 1000}ms); calibration not trustworthy.",
                fallbackAvailable = false,
            )
        }
        Log.d(TAG, "BT compensation = ${compensation / 1000}ms (btRoundTrip=${btResult.roundTripUs / 1000}ms - micPath=${micPathUs / 1000}ms)")

        // Persist + apply to engine.
        val routeKey = getBtRouteKey()
        settingsRepository.setAcousticRouteCalibration(
            routeKey,
            AcousticRouteCalibration(
                correctionUs = compensation,
                quality = btResult.quality.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
        sendspinManager.setRouteAcousticExtraUs(compensation)

        return CalibrationOutcome.Success(
            compensationUs = compensation,
            quality = btResult.quality.name,
            micPathUs = micPathUs,
            btRoundTripUs = btResult.roundTripUs,
            micPathFreshlyMeasured = freshlyMeasured,
        )
    }

    /**
     * Single-pass legacy fallback. Runs a chirp on whatever output is the
     * current default (BT, in practice), subtracts Oboe's reported input
     * latency, and stores the result. Used when the two-pass run reported
     * back `fallbackAvailable = true` (routing override blocked, Oboe
     * output timestamp unavailable, etc.). Less accurate — the Oboe input
     * latency under-counts mic DSP/processing time — but works on devices
     * that block the proper algorithm.
     */
    suspend fun runLegacyBtCalibration(
        onProgress: (toneIndex: Int, total: Int) -> Unit
    ): CalibrationOutcome {
        calibrator.onProgress = { idx, total -> onProgress(idx, total) }
        val result = runCatching {
            calibrator.measureRoundTrip(maxDelayMs = 500, outputDeviceId = 0)
        }.getOrNull()
        calibrator.onProgress = null

        if (result == null || result.quality == NativeAcousticCalibrator.Quality.FAILED) {
            return CalibrationOutcome.Failure("Calibration failed.", fallbackAvailable = false)
        }

        val compensation = if (result.inputLatencyUs > 0L) {
            (result.roundTripUs - result.inputLatencyUs).coerceIn(0L, MAX_COMPENSATION_US)
        } else {
            (result.roundTripUs / 2).coerceIn(0L, MAX_COMPENSATION_US)
        }
        val quality = if (result.inputLatencyUs > 0L) {
            result.quality.name
        } else {
            NativeAcousticCalibrator.Quality.MARGINAL.name
        }
        Log.d(TAG, "Legacy compensation = ${compensation / 1000}ms (roundTrip=${result.roundTripUs / 1000}ms inputLatency=${result.inputLatencyUs / 1000}ms)")

        val routeKey = getBtRouteKey()
        settingsRepository.setAcousticRouteCalibration(
            routeKey,
            AcousticRouteCalibration(
                correctionUs = compensation,
                quality = quality,
                updatedAt = System.currentTimeMillis(),
            )
        )
        sendspinManager.setRouteAcousticExtraUs(compensation)
        return CalibrationOutcome.Success(
            compensationUs = compensation,
            quality = quality,
            micPathUs = 0L,
            btRoundTripUs = result.roundTripUs,
            micPathFreshlyMeasured = false,
        )
    }

    /**
     * Run a short calibration for the phone's BUILT-IN SPEAKER and persist a
     * correction under [SPEAKER_ROUTE_KEY].
     *
     * Why this exists: the in-device sync model trusts the platform's
     * `AudioManager.getOutputLatency`, which on some HALs (e.g. Xiaomi/MIUI)
     * under-reports the true output latency by omitting the analog/speaker
     * stage. There is no software API that exposes that gap, so we measure it
     * acoustically: chirp out the built-in speaker, hear it back on the mic.
     *
     * Derivation. The acoustic round trip is `output_oneway + air + mic_path`.
     * Air (~5-15 cm, same device) is sub-millisecond. We estimate `mic_path`
     * with Oboe's reported input latency (HAL buffer); it under-counts mic DSP,
     * so this is approximate and recalibratable, like [runLegacyBtCalibration].
     * The sync model ALREADY compensates `getOutputLatency`, so we store only
     * the SHORTFALL it under-reports: `(roundTrip - inputLatency) -
     * getOutputLatency`. On an honest HAL (Samsung) that is ~0; on one that
     * omits the analog stage (Xiaomi) it is the missing tens of ms.
     *
     * The caller must ensure the speaker is free of music (pause first) and set
     * a usable media volume; the chirp is scaled by STREAM_MUSIC.
     */
    suspend fun runSpeakerCalibration(
        onProgress: (toneIndex: Int, total: Int) -> Unit
    ): CalibrationOutcome {
        val builtInId = findBuiltInSpeakerId()
            ?: return CalibrationOutcome.Failure("No built-in speaker on this device.")

        // Single pass. A multi-pass median made it WORSE: this HAL returns garbage
        // (~50 ms false onsets) on rapid consecutive measureRoundTrip calls, so
        // only the first measurement on a freshly-idle audio path is trustworthy.
        // The native already medians 6 internal tone bursts per pass.
        val platformLatencyUs = queryOutputLatencyUs()
        // Reject only implausibly-SHORT round trips (false early onsets, ~50ms,
        // from reverb/stream churn). A FIXED floor; do NOT gate on platformLatency
        // -> on devices that report a high getOutputLatency (e.g. Samsung 129ms) a
        // perfectly valid sub-getOutputLatency round trip was wrongly rejected.
        val floorUs = SPEAKER_CAL_MIN_ROUNDTRIP_US

        // The chirp needs a CLEAN audio path. Freezing the Sendspin output kept
        // its stream OPEN and the chirp shared the mixer -> the round trip
        // collapsed (~150ms -> ~62ms). pauseOutputForCalibration requestStops the
        // Sendspin Oboe stream (ring preserved) so the chirp owns the speaker;
        // resume restarts it and re-locks when grouped.
        calibrator.onProgress = { idx, total -> onProgress(idx, total) }
        sendspinManager.pauseOutputForCalibration()
        val r = try {
            runCatching {
                calibrator.measureRoundTrip(maxDelayMs = 200, outputDeviceId = builtInId)
            }.getOrNull()
        } finally {
            sendspinManager.resumeOutputAfterCalibration()
            calibrator.onProgress = null
        }

        if (r == null || r.quality == NativeAcousticCalibrator.Quality.FAILED) {
            return CalibrationOutcome.Failure("Speaker calibration failed.")
        }
        if (r.routedOutputDeviceId != builtInId) {
            Log.w(TAG, "Speaker routing override ignored: requested=$builtInId actual=${r.routedOutputDeviceId}")
            return CalibrationOutcome.Failure("Could not route the chirp to the built-in speaker.")
        }
        if (r.roundTripUs < floorUs) {
            return CalibrationOutcome.Failure(
                "Measurement looked implausible (${r.roundTripUs / 1000}ms); keep the room quiet and try again."
            )
        }

        val oneWayOutputUs = (r.roundTripUs - r.inputLatencyUs).coerceAtLeast(0L)
        val correction = (oneWayOutputUs - platformLatencyUs).coerceIn(0L, MAX_COMPENSATION_US)
        Log.d(
            TAG,
            "Speaker cal: correction=${correction / 1000}ms " +
                "(roundTrip=${r.roundTripUs / 1000}ms inputLat=${r.inputLatencyUs / 1000}ms " +
                "oneWayOut=${oneWayOutputUs / 1000}ms platformLat=${platformLatencyUs / 1000}ms snr=${r.snrDb}dB)"
        )

        settingsRepository.setAcousticRouteCalibration(
            SPEAKER_ROUTE_KEY,
            AcousticRouteCalibration(
                correctionUs = correction,
                quality = r.quality.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
        // Apply immediately if the speaker is the live output route.
        if (!isBtRoute()) sendspinManager.setRouteAcousticExtraUs(correction)

        return CalibrationOutcome.Success(
            compensationUs = correction,
            quality = r.quality.name,
            micPathUs = r.inputLatencyUs,
            btRoundTripUs = r.roundTripUs,
            micPathFreshlyMeasured = true,
        )
    }

    /** True when a built-in-speaker calibration has been stored. */
    suspend fun hasSpeakerCalibration(): Boolean =
        settingsRepository.acousticRouteCalibrations.first().containsKey(SPEAKER_ROUTE_KEY)

    /** Stored built-in-speaker correction (0 if none). */
    suspend fun speakerCalibration(): AcousticRouteCalibration? =
        settingsRepository.acousticRouteCalibrations.first()[SPEAKER_ROUTE_KEY]

    fun resetSpeakerCalibration() {
        scope.launch {
            settingsRepository.removeAcousticRouteCalibration(SPEAKER_ROUTE_KEY)
            if (!isBtRoute()) sendspinManager.setRouteAcousticExtraUs(0L)
        }
    }

    /**
     * Hidden `AudioManager.getOutputLatency(STREAM_MUSIC)` (full reported output
     * latency) in microseconds, or 0 when unavailable. Same value the engine
     * uses for the in-device latency compensation; mirrored here so the speaker
     * calibration stores only the under-reported shortfall above it.
     */
    private fun queryOutputLatencyUs(): Long = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            0L
        } else {
            val method = AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
            val ms = method.invoke(am, AudioManager.STREAM_MUSIC) as? Int ?: 0
            if (ms > 0) ms * 1000L else 0L
        }
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "getOutputLatency reflection failed: ${e.message}")
        0L
    }

    /**
     * Persist a one-way BT correction directly. Kept for backwards
     * compatibility with the previous dialog flow.
     */
    fun saveCalibration(correctionUs: Long, quality: String) {
        val routeKey = getBtRouteKey()
        scope.launch {
            settingsRepository.setAcousticRouteCalibration(
                routeKey,
                AcousticRouteCalibration(
                    correctionUs = correctionUs,
                    quality = quality,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            sendspinManager.setRouteAcousticExtraUs(correctionUs)
        }
    }

    fun resetCalibration() {
        val routeKey = getBtRouteKey()
        scope.launch {
            settingsRepository.removeAcousticRouteCalibration(routeKey)
            sendspinManager.setRouteAcousticExtraUs(0L)
        }
    }

    /**
     * Clear the cached mic_path so the next calibration re-measures it on
     * the phone speaker. Useful when the user upgrades Android (the audio
     * stack may shift latency) or suspects the cached value is stale.
     */
    fun resetMicPath() {
        scope.launch {
            settingsRepository.setAcousticMicPathUs(0L)
        }
    }

    /**
     * Enumerate available output devices and return the AAudio device id of
     * the built-in phone speaker, or null when the device has none (some
     * tablets, DeX desktop mode, or transient docked configurations).
     */
    private fun findBuiltInSpeakerId(): Int? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.id
    }

    companion object {
        /** Sanity bounds for mic_path measurements (microseconds). */
        private const val MIN_MIC_PATH_US = 5_000L      // 5 ms
        private const val MAX_MIC_PATH_US = 150_000L    // 150 ms
        /** Storage cap on a single route correction, matches dialog clamp. */
        private const val MAX_COMPENSATION_US = 500_000L // 500 ms

        /** Storage key for the built-in speaker acoustic correction (per phone). */
        const val SPEAKER_ROUTE_KEY = "speaker"

        /** Absolute floor for a plausible speaker->mic round trip (false-onset guard). */
        private const val SPEAKER_CAL_MIN_ROUNDTRIP_US = 60_000L // 60 ms
    }
}
