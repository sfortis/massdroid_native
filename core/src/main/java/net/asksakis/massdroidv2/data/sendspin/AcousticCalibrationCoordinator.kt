package net.asksakis.massdroidv2.data.sendspin

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "AcousticCoord"

/**
 * A connected Bluetooth output. [name] is the product name, or null when
 * several Bluetooth sinks are connected and none carries the Sendspin output.
 */
private data class BluetoothOutput(val name: String?) {
    /** Storage key of this output's calibration, the same `bt:NAME` form the engine resolves. */
    val routeKey: String? get() = name?.let { OutputRouteKeys.BLUETOOTH_PREFIX + it }
}

/**
 * Where a running calibration is. [lastLatencyUs] is the previous round's
 * result, and [settling] says that round found the delay still changing.
 */
data class CalibrationProgress(
    val round: Int,
    val elapsedS: Int,
    val lastLatencyUs: Long?,
    val settling: Boolean,
)

/** Result of one output calibration. */
sealed class CalibrationOutcome {
    /** [latencyUs] is the stored correction; [clarity] how clearly the recording matched. */
    data class Success(val latencyUs: Long, val clarity: Double) : CalibrationOutcome()
    data class Failure(val reason: String) : CalibrationOutcome()
}

/**
 * Measures and stores each output route's latency beyond what the output
 * stream's getTimestamp covers, which the Sendspin engine applies as the
 * acoustic correction (`routeAcousticExtraUs`).
 *
 * **Method.** A chirp sequence ([OutputLatencyMeasurement.signal]) plays
 * through a native output stream opened like the Sendspin output, so it takes
 * the music's mixer and effect path. Its getTimestamp says when the first frame
 * was presented. The microphone records at the same time, and AudioRecord's
 * input timestamp places the recording on the same clock. The lag at which the
 * recording matches the signal is the latency nothing reports: on the S25
 * speaker about 20 ms of Samsung's effect stages and amplifier, on Bluetooth
 * whatever the A2DP path adds beyond its timestamp.
 *
 * It replaced a round trip (speaker to microphone) on 2026-10-03, which had to
 * subtract the microphone's latency and took Oboe's input latency for it. That
 * value misses the microphone's DSP, about 40 ms on the S25, so every result
 * was that much too long; the speaker variant then compared the result with
 * getOutputLatency, which the engine no longer uses on the speaker, and stored 0.
 *
 * The result depends on the input timestamp being honest. On the S25 it agreed
 * with the sync probe and with a Sonos by ear; other devices are unverified.
 */
@Singleton
class AcousticCalibrationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val calibrator: NativeAcousticCalibrator,
    private val groupHold: GroupPlaybackHold,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val acousticRouteCalibrations: Flow<Map<String, AcousticRouteCalibration>> =
        settingsRepository.acousticRouteCalibrations

    /** The fine-tune per output; see [SettingsRepository.outputFineTuneMs]. */
    val outputFineTuneMs: Flow<Map<String, Int>> = settingsRepository.outputFineTuneMs

    // Prompt once per output and app session: when this device joins a group and
    // the output it plays on has no stored calibration, suggest running one (the
    // UI shows a confirm-to-calibrate dialog; the user opts in, never silent).
    private val suggestedThisSession = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _calibrationSuggested = MutableSharedFlow<OutputInfo>(extraBufferCapacity = 1)
    val calibrationSuggested: SharedFlow<OutputInfo> = _calibrationSuggested.asSharedFlow()

    init {
        scope.launch {
            sendspinManager.groupJoined.collect {
                val output = resolveCurrentOutput()
                val routeKey = output.routeKey ?: return@collect
                if (!output.canCalibrate) return@collect
                if (settingsRepository.acousticRouteCalibrations.first().containsKey(routeKey)) return@collect
                if (suggestedThisSession.add(routeKey)) _calibrationSuggested.tryEmit(output)
            }
        }
    }

    /**
     * True when the Sendspin output is routed to a Bluetooth sink, from the most
     * recent routed device snapshot; the device callback in
     * SendspinAudioController is the canonical change signal. False while the
     * output stream is closed, so it does not say whether Bluetooth can be
     * calibrated; [currentOutput] does.
     */
    fun isBtRoute(): Boolean =
        sendspinManager.getRoutedDeviceType()?.let { isBluetoothSink(it) } == true

    /**
     * The output the phone plays on, updated on every device change. Valid
     * while the Sendspin output stream is closed, which the routed device alone
     * is not: the idle release closes that stream 15 s after the music stops.
     *
     * Bluetooth wins when a sink is connected, then wired, then USB, then the
     * built-in speaker, the order Android routes media in. With both a wired
     * and a Bluetooth output connected this can name a different output than
     * the stream plays on; SendspinAudioController follows the routed device.
     */
    val currentOutput: Flow<OutputInfo> = callbackFlow {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(resolveCurrentOutput())
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(resolveCurrentOutput())
            }
        }
        // Registering reports the devices already present through onAudioDevicesAdded.
        am.registerAudioDeviceCallback(callback, null)
        awaitClose { am.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun resolveCurrentOutput(): OutputInfo {
        currentBluetoothOutput()?.let { bt ->
            return OutputInfo(bt.routeKey, bt.name ?: "Bluetooth device", OutputKind.BLUETOOTH, canCalibrate = true)
        }
        val outputs = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        if (outputs.any { it.type in WIRED_TYPES }) {
            return OutputInfo(OutputRouteKeys.WIRED, "Wired headphones", OutputKind.WIRED, canCalibrate = false)
        }
        outputs.firstOrNull { it.type in USB_TYPES }?.let { usb ->
            val name = usb.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB audio"
            return OutputInfo(OutputRouteKeys.USB, name, OutputKind.USB, canCalibrate = false)
        }
        // A TV box has no built-in speaker and plays on HDMI, which the engine
        // treats as the speaker route, so it shares that route key.
        val hasBuiltInSpeaker = findBuiltInSpeakerId() != null
        val name = if (!hasBuiltInSpeaker && outputs.any { it.type in HDMI_TYPES }) "HDMI" else "This phone's speaker"
        return OutputInfo(SPEAKER_ROUTE_KEY, name, OutputKind.SPEAKER, canCalibrate = hasBuiltInSpeaker)
    }

    /**
     * The routed device when the Sendspin output plays on Bluetooth, otherwise
     * the connected Bluetooth sink. With several sinks connected and none routed
     * the name stays unknown; the calibration then names the route after the
     * device its test sound actually played on.
     */
    private fun currentBluetoothOutput(): BluetoothOutput? {
        if (isBtRoute()) return BluetoothOutput(sendspinManager.getRoutedDeviceProductName())
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val names = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isBluetoothSink(it.type) }
            .map { it.productName?.toString() }
        if (names.isEmpty()) return null
        return BluetoothOutput(names.distinct().singleOrNull())
    }

    /** Calibrates the built-in speaker, routing the test sound to it explicitly. */
    suspend fun calibrateSpeaker(onProgress: (CalibrationProgress) -> Unit = {}): CalibrationOutcome {
        val builtInId = findBuiltInSpeakerId()
            ?: return CalibrationOutcome.Failure("This device has no built-in speaker.")
        return calibrate(
            outputDeviceId = builtInId,
            wrongRouteReason = "Couldn't measure. The test sound did not play on the phone's speaker.",
            onProgress = onProgress,
        ) { routedId -> SPEAKER_ROUTE_KEY.takeIf { routedId == builtInId } }
    }

    /**
     * Calibrates the Bluetooth device media plays on. The test sound goes to the
     * default route, and the result is stored for the device it was routed to,
     * so the Sendspin output does not have to be open.
     */
    suspend fun calibrateBluetooth(onProgress: (CalibrationProgress) -> Unit = {}): CalibrationOutcome {
        if (currentBluetoothOutput() == null) {
            return CalibrationOutcome.Failure("Couldn't measure. No Bluetooth device is connected.")
        }
        return calibrate(
            outputDeviceId = 0,
            wrongRouteReason = "Couldn't measure. The test sound did not play on Bluetooth.",
            onProgress = onProgress,
        ) { routedId -> bluetoothRouteKey(routedId) }
    }

    /**
     * Measures and stores the result under the key [routeKeyFor] gives the
     * device the test sound was routed to, or fails with [wrongRouteReason]
     * when it gives none. SendspinAudioController applies a stored value to the
     * route it plays on.
     */
    private suspend fun calibrate(
        outputDeviceId: Int,
        wrongRouteReason: String,
        onProgress: (CalibrationProgress) -> Unit,
        routeKeyFor: (routedDeviceId: Int) -> String?,
    ): CalibrationOutcome {
        val (outcome, routedId) = groupHold.pausedWhile { measureUntilSettled(outputDeviceId, onProgress) }
        if (outcome !is CalibrationOutcome.Success) {
            Log.d(TAG, "Output calibration on device $routedId: $outcome")
            return outcome
        }
        val routeKey = routeKeyFor(routedId)
        Log.d(TAG, "Output calibration ${routeKey ?: "on unexpected device $routedId"}: $outcome")
        if (routeKey == null) return CalibrationOutcome.Failure(wrongRouteReason)
        settingsRepository.setAcousticRouteCalibration(
            routeKey,
            AcousticRouteCalibration(
                correctionUs = outcome.latencyUs,
                quality = "GOOD",
                updatedAt = System.currentTimeMillis(),
                method = AcousticRouteCalibration.METHOD_ABSOLUTE,
            )
        )
        // A fine-tune was set against the old measurement; the new one replaces both.
        settingsRepository.setOutputFineTuneMs(routeKey, 0)
        return outcome
    }

    /**
     * Measures in rounds on one output stream that stays open on
     * [outputDeviceId] (0 = current route) and plays silence between rounds,
     * until two settled rounds agree. A path that is still settling, such as a
     * Bluetooth speaker growing its own buffer, keeps the rounds going for up
     * to [MAX_DURATION_MS]. The Sendspin output is stopped meanwhile, so the
     * music neither covers the signal nor shares the output with it. Returns
     * the outcome and the device the stream was routed to.
     */
    private suspend fun measureUntilSettled(
        outputDeviceId: Int,
        onProgress: (CalibrationProgress) -> Unit,
    ): Pair<CalibrationOutcome, Int> {
        val signal = OutputLatencyMeasurement.signal()
        var routedId = 0
        sendspinManager.pauseOutputForCalibration()
        try {
            val outcome = calibrator.session(outputDeviceId) { session ->
                runRounds(session, signal, onProgress) { routedId = it }
            } ?: CalibrationOutcome.Failure("Couldn't play the test sound. The output did not start.")
            return outcome to routedId
        } finally {
            sendspinManager.resumeOutputAfterCalibration()
        }
    }

    private suspend fun runRounds(
        session: NativeAcousticCalibrator.Session,
        signal: ShortArray,
        onProgress: (CalibrationProgress) -> Unit,
        onRouted: (Int) -> Unit,
    ): CalibrationOutcome {
        val startMs = SystemClock.elapsedRealtime()
        var previous: Attempt.Measured? = null
        var rejectionsInRow = 0
        var round = 0
        while (true) {
            round++
            onProgress(
                CalibrationProgress(
                    round = round,
                    elapsedS = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt(),
                    lastLatencyUs = previous?.latencyUs,
                    settling = previous?.settled == false,
                )
            )
            val attempt = measureOnce(session, signal, onRouted)
            Log.d(TAG, "Round $round: $attempt")
            when (attempt) {
                is Attempt.Fatal -> return CalibrationOutcome.Failure(attempt.reason)
                is Attempt.Rejected -> {
                    // Without a single measurement, repeated rejections mean the
                    // microphone does not hear the output; with one, keep going.
                    if (++rejectionsInRow >= MAX_REJECTIONS_IN_ROW && previous == null) {
                        return CalibrationOutcome.Failure(attempt.reason)
                    }
                }
                is Attempt.Measured -> {
                    rejectionsInRow = 0
                    val last = previous
                    if (attempt.settled && last != null && last.settled &&
                        abs(attempt.latencyUs - last.latencyUs) <= AGREEMENT_US
                    ) {
                        return CalibrationOutcome.Success(attempt.latencyUs, attempt.clarity)
                    }
                    previous = attempt
                }
            }
            if (SystemClock.elapsedRealtime() - startMs > MAX_DURATION_MS) {
                return CalibrationOutcome.Failure(
                    "Couldn't measure. The speaker's delay was still changing after three minutes."
                )
            }
            delay(ROUND_GAP_MS)
        }
    }

    /** One round: the signal plays while the microphone records, then the analysis. */
    private suspend fun measureOnce(
        session: NativeAcousticCalibrator.Session,
        signal: ShortArray,
        onRouted: (Int) -> Unit,
    ): Attempt {
        val recordMs = signal.size * 1000L / OutputLatencyMeasurement.RATE + RECORD_MARGIN_MS
        val (played, mic) = coroutineScope {
            // The signal opens with silence, which covers the recorder's start.
            val recording = async { TimestampedMicRecorder.record(recordMs) }
            val play = session.play(signal)
            play to recording.await()
        }
        played?.let { onRouted(it.routedOutputDeviceId) }
        val attempt = when {
            mic == null -> Attempt.Fatal(
                "Couldn't record. The microphone did not open; check the microphone permission."
            )
            played == null -> Attempt.Fatal("Couldn't play the test sound. The output did not start.")
            played.xRuns > 0 -> Attempt.Rejected("Couldn't measure. The test sound stuttered; try again.")
            else -> analyze(signal, played, mic)
        }
        if (played != null && mic != null) {
            withContext(Dispatchers.IO) { CalibrationRecordingDump.save(context, signal, played, mic, attempt) }
        }
        return attempt
    }

    private suspend fun analyze(
        signal: ShortArray,
        played: NativeAcousticCalibrator.PlayResult,
        mic: TimestampedRecording,
    ): Attempt {
        val result = withContext(Dispatchers.Default) {
            OutputLatencyMeasurement.analyze(signal, played.frame0Us, mic.samples, mic.originUs)
        }
        Log.d(
            TAG,
            "Output latency: $result outSpread=${played.spreadUs}us outSamples=${played.timestampSamples} " +
                "offset=${played.frameOffset} rate=${played.sampleRate} mic=${mic.source} " +
                "micSpread=${mic.timestampSpreadUs}us"
        )
        return when (result) {
            is OutputLatencyResult.Rejected -> Attempt.Rejected(result.reason)
            is OutputLatencyResult.Measured -> Attempt.Measured(
                latencyUs = result.latencyUs.coerceIn(0L, MAX_CORRECTION_US),
                clarity = result.analysis.clarity,
                settled = result.settled,
            )
        }
    }

    /** What one round found. */
    internal sealed interface Attempt {
        data class Measured(val latencyUs: Long, val clarity: Double, val settled: Boolean) : Attempt
        /** The round could not be used; the next one may be. */
        data class Rejected(val reason: String) : Attempt
        /** Nothing further can be measured. */
        data class Fatal(val reason: String) : Attempt
    }

    /** Removes the calibration stored for output [routeKey]. */
    fun resetCalibration(routeKey: String) {
        scope.launch { settingsRepository.removeAcousticRouteCalibration(routeKey) }
    }

    /** Stores the fine-tune of output [routeKey]; 0 clears it. */
    fun setOutputFineTuneMs(routeKey: String, ms: Int) {
        scope.launch { settingsRepository.setOutputFineTuneMs(routeKey, ms) }
    }

    /** The `bt:NAME` route key of output device [deviceId], or null when it is not a Bluetooth sink. */
    private fun bluetoothRouteKey(deviceId: Int): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            ?: return null
        if (!isBluetoothSink(device.type)) return null
        return OutputRouteKeys.BLUETOOTH_PREFIX + device.productName
    }

    /**
     * The AAudio device id of the built-in speaker, or null when the device has
     * none (some tablets, DeX desktop mode).
     */
    private fun findBuiltInSpeakerId(): Int? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.id
    }

    companion object {
        /** Storage key for the built-in speaker's calibration. */
        const val SPEAKER_ROUTE_KEY = OutputRouteKeys.SPEAKER

        private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        private val HDMI_TYPES = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC)

        // Recording beyond the signal: the output's start-up and the latency
        // being measured, up to the analysis window's end.
        private const val RECORD_MARGIN_MS = 1_500L
        private const val MAX_CORRECTION_US = 800_000L
        // Two settled rounds this close are the answer.
        private const val AGREEMENT_US = 2_000L
        // Silence between rounds; the stream keeps running through it.
        private const val ROUND_GAP_MS = 3_000L
        // The CREATIVE MUVO 2 needed about two minutes to settle.
        private const val MAX_DURATION_MS = 180_000L
        private const val MAX_REJECTIONS_IN_ROW = 3
    }
}
