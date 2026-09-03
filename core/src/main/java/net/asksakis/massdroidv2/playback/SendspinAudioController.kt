package net.asksakis.massdroidv2.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.isBluetoothSink
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.util.UUID

data class SendspinMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val art: Bitmap?,
    val artUrl: String?,
    val trackUri: String?
)

class SendspinAudioController(
    private val context: Context,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val volumeCoordinator: net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator,
    // Name this device registers under as a Sendspin player. Defaults to
    // "MassDroid" (phone); other front-ends (e.g. Android TV) override it so the
    // players are distinguishable in Music Assistant.
    private val clientName: String = "MassDroid",
    private val onMetadataChanged: (SendspinMetadata) -> Unit,
    private val onStateChanged: (ready: Boolean, streaming: Boolean, playing: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SendspinCtrl"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val GROUP_JOIN_RELOCK_COOLDOWN_MS = 5_000L
        private const val GROUP_SOLO_STARTUP_GRACE_MS = 5_000L
        private const val GROUPED_SENDSPIN_FORMAT = "flac:48000:16:2"
        // BT-connect auto-play waits for the route to actually settle on BT (no
        // route change across the quiet window) rather than a fixed delay, since
        // the A2DP connect handshake flaps speaker<->bt for a few seconds.
        private const val BT_STABILIZE_TIMEOUT_MS = 12_000L
        private const val BT_STABILIZE_QUIET_MS = 1_200L
        private const val BT_STABILIZE_POLL_MS = 250L
        // How long a reconnect waits for the server to answer the re-asserted
        // pause before refreshing the Sendspin transport anyway. Bounded because
        // an unrefreshed transport is a worse outcome than the short leak the
        // ordering is there to prevent, and because a missing answer means the
        // connection is broken again, in which case the refresh fails too.
        private const val PAUSE_REASSERT_TIMEOUT_MS = 5_000L
        // How long a duck waits for the interrupting sound to actually show up in
        // the platform's playback configurations. Nothing there means we cannot
        // see the interrupter (it may be playing as plain media, or it took focus
        // without playing at all, which is the SpeedCam Droid case that never
        // returned a GAIN), so the gain is restored on this deadline exactly as
        // the old blind timer did.
        private const val DUCK_INTERRUPTER_WAIT_MS = 10_000L
        // Safety cap once the interrupter IS visible. Reached only if its player
        // never leaves the configuration list, so it is generous: a real alarm or
        // a long voice message is a legitimate reason to stay quiet.
        private const val DUCK_MAX_MS = 5 * 60_000L
        // Usages that mean "another app is deliberately talking over us". Our own
        // output is USAGE_MEDIA, so it can never match.
        private val INTERRUPTER_USAGES = setOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Coroutine exception: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Audio focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var hasAudioFocus = false
    // True while a transient focus loss has FROZEN the solo (DIRECT) output
    // (buffer preserved) rather than flushing it. The next focus regain unfreezes
    // and resumes instantly from the intact buffer. Cleared on regain, permanent
    // loss, and stop().
    // Composable output freeze: focus interruptions (phone calls) and transient
    // route losses (BT connect/handshake flap) can both freeze the output. Each
    // owns a reason; the engine stays frozen until the LAST reason clears, so the
    // two never fight (e.g. a call ending mid BT-flap won't unfreeze prematurely).
    private val freezeReasons = mutableSetOf<String>()

    @Synchronized
    private fun freezeOutput(reason: String) {
        val wasEmpty = freezeReasons.isEmpty()
        if (freezeReasons.add(reason) && wasEmpty) {
            sendspinManager.freezeOutput()
        }
    }

    /** @return true if [reason] was actually holding a freeze. */
    @Synchronized
    private fun unfreezeOutput(reason: String): Boolean {
        if (!freezeReasons.remove(reason)) return false
        if (freezeReasons.isEmpty()) sendspinManager.unfreezeOutput()
        return true
    }

    @Synchronized
    private fun clearAllFreezes() {
        if (freezeReasons.isNotEmpty()) {
            freezeReasons.clear()
            sendspinManager.unfreezeOutput()
        }
    }

    // Noisy audio receiver: fires just before audio would re-route to the phone
    // speaker (BT/headset leaving). On a real disconnect we must not leak audio to
    // the speaker; on a transient connect/handshake flap (the car's A2DP link
    // settling) the route returns within seconds. Both funnel through the settle
    // gate, which silences instantly but only commits a real pause on durable loss.
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (sendspinPlayerId == null) return
                Log.d(TAG, "Audio becoming noisy -> clean pause")
                volumeCoordinator.onOutputRouteChanging()
                // Poke the unified car-route evaluation. If this is a real disconnect the
                // coordinator's settle timer will confirm (Oboe no longer routed to the car) and
                // restore the pre-car volume; a connect-handshake flap re-binds within the window
                // and the settle re-check aborts the exit. No connect/lost distinction here.
                volumeCoordinator.onRouteChanged()
                ++routeChangeGeneration  // supersede any in-flight relock
                pauseForRouteLoss()
            }
        }
    }

    // Audio route detection: uses the live stream's routed device as canonical source.
    @Volatile private var currentRoute = OutputRoute.UNKNOWN
    @Volatile private var currentBtRouteKey = ""
    @Volatile private var routeChangeGeneration = 0L
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) = checkRouteChange()
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) = checkRouteChange()
    }

    private fun classifyDeviceType(type: Int): OutputRoute = when {
        isBluetoothSink(type) -> OutputRoute.BT
        type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputRoute.WIRED
        type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> OutputRoute.USB
        else -> OutputRoute.SPEAKER
    }

    private fun resolveOutputRoute(): OutputRoute {
        // Primary: ask the actual stream where it's routing (canonical truth)
        sendspinManager.getRoutedDeviceType()?.let { return classifyDeviceType(it) }
        // Fallback: heuristic from connected devices
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothA2dpOn) return OutputRoute.BT
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET } -> OutputRoute.WIRED
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE } -> OutputRoute.USB
            else -> OutputRoute.SPEAKER
        }
    }

    private fun resolveBtRouteKey(): String =
        "bt:${sendspinManager.getRoutedDeviceProductName() ?: "unknown"}"

    private fun checkRouteChange() {
        val newRoute = resolveOutputRoute()
        if (newRoute != currentRoute) {
            val oldRoute = currentRoute
            currentRoute = newRoute
            // Tell the volume coordinator a route transition is underway so it stops
            // mirroring STREAM_MUSIC to MA during the bt->speaker->bt connect flap
            // (Android serves a different per-route STREAM_MUSIC level each flap).
            volumeCoordinator.onOutputRouteChanging()
            val gen = ++routeChangeGeneration
            Log.d(TAG, "Audio route changed: $oldRoute -> $newRoute (gen=$gen)")
            when {
                // Lost an external sink to the phone speaker: clean immediate pause
                // (no leak to the phone speaker). autoPlayOnBtConnect resumes if the
                // sink later reconnects.
                oldRoute.isExternal && newRoute == OutputRoute.SPEAKER ->
                    pauseForRouteLoss()
                // (Re)gained an external route: relock for the new route. Resume on a
                // BT reconnect is handled by autoPlayOnBtConnect (A2DP device-added).
                else -> relockForRoute(oldRoute, newRoute, gen)
            }
        } else if (newRoute == OutputRoute.BT) {
            // Same route type but maybe a different BT device (bt:A -> bt:B).
            val routeKey = resolveBtRouteKey()
            if (routeKey != currentBtRouteKey) {
                volumeCoordinator.onOutputRouteChanging()
                val gen = ++routeChangeGeneration
                Log.d(TAG, "BT device change: $currentBtRouteKey -> $routeKey (gen=$gen)")
                scope.launch {
                    if (gen != routeChangeGeneration) return@launch
                    val correctionUs = resolveAcousticCorrectionForRoute(newRoute)
                    if (gen != routeChangeGeneration) return@launch
                    sendspinManager.setRouteAcousticExtraUs(correctionUs)
                    sendspinManager.onOutputRouteChanged("bt:device-switch")
                    currentBtRouteKey = routeKey  // commit only after successful apply
                    volumeCoordinator.onRouteChanged()
                }
            }
        }
    }

    /** Apply the per-route acoustic correction and relock the engine (gen-guarded). */
    private fun relockForRoute(oldRoute: OutputRoute, newRoute: OutputRoute, gen: Long) {
        scope.launch {
            if (gen != routeChangeGeneration) return@launch  // superseded
            val correctionUs = resolveAcousticCorrectionForRoute(newRoute)
            if (gen != routeChangeGeneration) return@launch  // superseded during resolve
            sendspinManager.setRouteAcousticExtraUs(correctionUs)
            sendspinManager.onOutputRouteChanged("$oldRoute->$newRoute")
            currentBtRouteKey = if (newRoute == OutputRoute.BT) resolveBtRouteKey() else ""
            if (newRoute == OutputRoute.BT) volumeCoordinator.onRouteChanged()
        }
    }

    // ===== External-sink loss: clean immediate pause + auto-resume on return =====
    //
    // When an external sink (BT/wired/USB) drops to the phone speaker we pause
    // cleanly and IMMEDIATELY. We do not wait to see if it returns: an active BT
    // sink does not flap-disconnect mid-playback; a real disconnect just stops.
    // If the sink later reconnects, autoPlayOnBtConnect (driven by the A2DP
    // device-added callback) resumes playback. The connect-time A2DP handshake
    // flap (speaker<->bt while the link settles) is absorbed by the native-output
    // reopen settle in SendspinPlaybackEngine, not here — so dropping the old
    // disconnect-side settle does not reintroduce the "stuck paused on car connect"
    // bug (that lived on the connect path, and auto-resume self-heals any residual
    // transient anyway).
    private fun pauseForRouteLoss() {
        val id = sendspinPlayerId ?: return
        // Silence locally first (mute + freeze) so the brief window before the
        // pause lands cannot leak audio to the phone speaker. pauseAudio() stops
        // the native output immediately, so it is safe to clear the freeze/mute
        // right after, leaving a clean paused stream.
        sendspinManager.setMuted(true)
        freezeOutput("route")
        Log.d(TAG, "External sink lost -> clean pause (auto-resume on reconnect)")
        _userIntent.value = false
        sendspinManager.pauseAudio()
        unfreezeOutput("route")
        sendspinManager.setMuted(false)
        // Debounced release of any car-audio selection lock (a flap reconnect
        // cancels it, so transport stays on the phone through the gap).
        volumeCoordinator.onRouteChanged()
        scope.launch { playerRepository.pause(id) }
    }

    private suspend fun resolveAcousticCorrectionForRoute(route: OutputRoute): Long {
        return when (route) {
            // Phone speaker: normally the in-device sync model trusts the
            // platform's reported output latency (getOutputLatency), which is
            // correct on honest HALs. But some HALs (e.g. Xiaomi/MIUI) under-report
            // it by omitting the analog/speaker stage, leaving playback tens of ms
            // late with no software way to detect it. If a built-in-speaker
            // acoustic calibration was run (auto on group join, or manual), it
            // stored ONLY that under-reported shortfall; apply it here. 0 when not
            // calibrated or on honest HALs. NOTE: we store the shortfall ABOVE
            // getOutputLatency, not the full round trip, so this does not
            // reintroduce the old "locked but audibly out of sync" double-count.
            OutputRoute.SPEAKER -> {
                val calibrations = settingsRepository.acousticRouteCalibrations.first()
                val correctionUs = calibrations[AcousticCalibrationCoordinator.SPEAKER_ROUTE_KEY]?.correctionUs ?: 0L
                if (correctionUs > 0L) Log.d(TAG, "Speaker acoustic correction: ${correctionUs / 1000}ms")
                correctionUs
            }
            OutputRoute.WIRED, OutputRoute.USB, OutputRoute.UNKNOWN -> 0L
            OutputRoute.BT -> {
                val productName = sendspinManager.getRoutedDeviceProductName() ?: "unknown"
                val routeKey = "bt:$productName"
                val calibrations = settingsRepository.acousticRouteCalibrations.first()
                val calibration = calibrations[routeKey]
                val correctionUs = calibration?.correctionUs ?: 0L
                Log.d(TAG, "Acoustic correction for $routeKey: ${correctionUs / 1000}ms (${if (calibration != null) calibration.quality else "not calibrated"})")
                correctionUs
            }
        }
    }

    // In-flight reconnect reconciliation, kept so a later reconnect supersedes it.
    private var reconnectReassertJob: Job? = null

    // Watches an active duck so the gain is restored when the interrupting sound
    // ends, rather than on a fixed timer. See [duckUntilInterrupterEnds].
    private var duckWatchJob: Job? = null

    // Locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ===== Playback intent / state =====
    //
    // `_userIntent` is the SINGLE source of truth for "does the user want
    // Sendspin to be playing right now". It is mutated only by explicit user-
    // level actions: the local play/pause handlers, the playbackIntent flow
    // from the repository (UI-driven), the noisy receiver (BT unplug), the
    // BT-to-speaker fallback, permanent audio focus loss, and stop(). Reactive
    // signals from the Sendspin server/transport never touch it.
    //
    // `_currentIsPlaying` is DERIVED in start() via combine() over userIntent,
    // selected player, transport state, sync state, and server metadata. It
    // reflects "is the user wanting to play AND is the system actually
    // delivering audio right now". This is what we report to the MediaSession
    // and what handlePlayPause toggles against.
    //
    // Auto-resume on MA reconnect still checks userIntent + sendspinSelected
    // explicitly, because user intent survives a player-selection switch (you
    // can be enjoying Sendspin and open the JBL screen to control it) but
    // auto-resume must NOT fire on a player the user is no longer using.
    private val _userIntent = MutableStateFlow(false)
    private val _currentIsPlaying = MutableStateFlow(false)
    private val currentIsPlaying: Boolean get() = _currentIsPlaying.value

    // State
    private var currentArt: Bitmap? = null
    private var currentArtUrl: String? = null
    @Volatile var isStreaming = false; private set
    @Volatile var isReady = false; private set
    @Volatile private var transportState = SendspinState.DISCONNECTED
    @Volatile private var localSyncState = SyncState.IDLE
    private var currentTrackUri: String? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    var sendspinPlayerId: String? = null; private set
    private val collectorJobs = mutableListOf<Job>()
    private var autoRecoveryJob: Job? = null
    private var reconnectJob: Deferred<Boolean>? = null
    private var lastObservedInGroup: Boolean? = null
    private var groupObserverStartedAtMs = 0L
    private var lastGroupJoinRelockAtMs = 0L
    // One-shot timer that commits the deferred initial solo verdict. The players
    // flow is distinctUntilChanged, so once it has emitted "solo" it will NOT
    // re-emit when streaming starts — without this the deferred verdict would
    // never commit and a solo phone would stay in SYNC (correcting + audible
    // resampling warble) forever instead of downgrading to DIRECT.
    private var deferredSoloJob: Job? = null

    /** Use MA player timeline as source of truth (matches seek command target). */
    private fun serverPositionMs(rawPositionMs: Long): Long {
        return rawPositionMs.coerceAtLeast(0L)
    }

    /**
     * Commit the deferred initial solo verdict after the startup grace. The
     * players flow is distinctUntilChanged and won't re-emit when streaming
     * starts, so the deferral (which keeps the SYNC default while group
     * membership is still unconfirmed) would otherwise never resolve and a solo
     * phone would stay in SYNC forever. If no group was observed by the time the
     * grace elapses, commit solo so the engine downgrades to DIRECT.
     */
    private fun scheduleDeferredSoloCommit() {
        if (deferredSoloJob?.isActive == true) return
        deferredSoloJob = scope.launch {
            delay(GROUP_SOLO_STARTUP_GRACE_MS)
            if (lastObservedInGroup == null) {
                Log.d(TAG, "Group check: solo grace elapsed, committing DIRECT (inGroup=false)")
                lastObservedInGroup = false
                sendspinManager.setInSyncGroup(false)
            }
        }
    }

    private fun requestGroupJoinRelock(player: net.asksakis.massdroidv2.domain.model.Player) {
        if (!isStreaming || player.state != PlaybackState.PLAYING) return

        val now = System.currentTimeMillis()
        if (now - lastGroupJoinRelockAtMs < GROUP_JOIN_RELOCK_COOLDOWN_MS) return
        lastGroupJoinRelockAtMs = now

        val targetPlayerId = player.activeGroup ?: player.playerId
        val mediaElapsed = player.currentMedia?.elapsedTime
        val positionSec = when {
            currentPositionMs > 0L -> currentPositionMs / 1000.0
            mediaElapsed != null -> mediaElapsed
            else -> playerRepository.elapsedTime.value
        }.coerceAtLeast(0.0)

        Log.d(TAG, "Group join relock: seek($targetPlayerId, ${"%.3f".format(positionSec)}s) streaming=$isStreaming")
        if (targetPlayerId != player.playerId) {
            sendspinManager.expectDiscontinuity("group-join")
        }
        scope.launch { playerRepository.seek(targetPlayerId, positionSec) }
    }

    fun start() {
        currentRoute = resolveOutputRoute()
        scope.launch {
            val correctionUs = resolveAcousticCorrectionForRoute(currentRoute)
            sendspinManager.setRouteAcousticExtraUs(correctionUs)
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        // Note: volumeCoordinator is started once by SendspinCoordinator with
        // its longer-lived scope; this controller's per-start lifecycle would
        // re-arm it across restarts and lose the syncEnabled observer.
        if (collectorJobs.isNotEmpty()) {
            Log.d(TAG, "start() ignored: already running")
            return
        }
        groupObserverStartedAtMs = System.currentTimeMillis()
        deferredSoloJob?.cancel()
        lastObservedInGroup = null

        // AudioTrack routing change listener (canonical route detection from actual track)
        sendspinManager.setOnRoutingChangedCallback { checkRouteChange() }

        setupAudioFocus()
        registerNoisyReceiver()

        // Persist callback for clock offset measurements
        sendspinManager.onClockOffsetPersist = { serverMinusWallUs ->
            scope.launch { settingsRepository.setSendspinClockOffsetUs(serverMinusWallUs) }
        }
        // Eager group check before connect so engine starts in correct mode
        scope.launch {
            val ssId = settingsRepository.sendspinClientId.first()
            if (ssId != null) {
                // Wait for player data (up to 5s) if empty on cold start
                val allPlayers = playerRepository.players.value.ifEmpty {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        playerRepository.players.first { it.isNotEmpty() }
                    } ?: emptyList()
                }
                val self = allPlayers.find { it.playerId == ssId }
                val selfInGroup = self?.groupChilds?.any { it != ssId } == true
                val childOfOther = allPlayers.any { it.playerId != ssId && ssId in it.groupChilds }
                if (selfInGroup || childOfOther) {
                    lastObservedInGroup = true
                    sendspinManager.setInSyncGroup(true)
                    applyGroupedSyncFormat(ssId)
                    Log.d(TAG, "Eager group check: inGroup=true before connect")
                } else if (self != null) {
                    // Our own player IS in the list -> player data is loaded and
                    // reliable, and no group references it: genuinely solo. Commit
                    // DIRECT now so playback does not start with a few seconds of
                    // needless SYNC correction (Measuring + resampling warble)
                    // before the collector downgrades. (self==null = data not yet
                    // loaded -> NOT authoritative, keep SYNC default below.)
                    lastObservedInGroup = false
                    sendspinManager.setInSyncGroup(false)
                    Log.d(TAG, "Eager group check: solo confirmed (self present, no group) -> DIRECT before connect")
                } else {
                    // No reliable player data yet: keep default SYNC (safe). The
                    // continuous collector + deferred-solo timer refine it.
                    Log.d(TAG, "Eager group check: no player data, keeping SYNC default (collector will refine)")
                }
            }
            ensureSendspinConnected()
        }
        scope.launch {
            val persistedOffset = settingsRepository.sendspinClockOffsetUs.first()
            sendspinManager.seedClockOffset(persistedOffset)
        }

        collectorJobs += scope.launch {
            settingsRepository.sendspinSyncDelayMs.collect { delayMs ->
                sendspinManager.setSyncDelayMs(delayMs)
            }
        }

        collectorJobs += scope.launch {
            settingsRepository.sendspinCompressorLevel.collect { level ->
                sendspinManager.setCompressorLevel(level)
            }
        }

        collectorJobs += scope.launch {
            settingsRepository.sendspinDither.collect { enabled ->
                sendspinManager.setDither(enabled)
            }
        }

        // Collector 1: Observe connection state. Updates `isStreaming` /
        // `isReady` via `recomputeAvailability()`, manages wake/wifi locks
        // and audio-focus acquisition on the streaming edge, and asks the
        // audio engine to flush on ERROR. It does NOT touch playback
        // intent or `_currentIsPlaying` — the derived flow does that.
        collectorJobs += scope.launch {
            sendspinManager.connectionState.collect { state ->
                val wasStreaming = transportState == SendspinState.STREAMING
                val wasError = transportState == SendspinState.ERROR
                transportState = state
                recomputeAvailability()
                Log.d(TAG, "Sendspin state: $state, isStreaming=$isStreaming, isReady=$isReady, sync=$localSyncState")

                // Audio focus still tracks the STREAMING transport edge. The wake
                // + Wi-Fi locks are NOT acquired here anymore: they follow the
                // manager's audioResourcesActive flow (collector below), so they
                // release once the phone stops being an actual output even though
                // the client stays connected (STREAMING) as an available player.
                if (!wasStreaming && transportState == SendspinState.STREAMING) {
                    if (!hasAudioFocus) requestAudioFocus()
                }
                if (wasStreaming && transportState != SendspinState.STREAMING) {
                    Log.d(TAG, "Sendspin dropped while streaming")
                }

                // ERROR transitions: flush the audio engine so the next ready
                // state starts from a clean baseline. The transport itself
                // recovers via SendspinClient's internal backoff (single
                // reconnect path); we do NOT schedule a controller-side
                // restart here — that was the source of issue #43's "trying
                // to enable" loop where the controller's 2s recovery raced
                // with the client's own scheduler.
                if (state == SendspinState.ERROR && !wasError) {
                    sendspinManager.onTransportFailure()
                }
                notifyStateChanged()
            }
        }

        // Collector: audio-resource ownership. Wake + Wi-Fi locks are held only
        // while the phone is an actual output (a protocol stream is live). The
        // manager keeps this true across track changes and drops it after a grace
        // once playback moves elsewhere, so the locks + native Oboe output are not
        // held indefinitely while the client stays connected as an available
        // player. distinctUntilChanged so we only act on real edges.
        collectorJobs += scope.launch {
            // StateFlow is already conflated (distinct-by-equality), so collect
            // sees only real true/false edges.
            sendspinManager.audioResourcesActive.collect { active ->
                if (active) acquireLocks() else releaseLocks()
            }
        }

        // Collector: sync state drives `isReady` via recomputeAvailability().
        // The derived flow folds rebuffering / idle into `_currentIsPlaying`.
        collectorJobs += scope.launch {
            sendspinManager.syncState.collect { state ->
                localSyncState = state
                recomputeAvailability()
                notifyStateChanged()
            }
        }

        collectorJobs += scope.launch {
            sendspinManager.serverMetadata.collect { metadata ->
                if (metadata == null) return@collect

                val title = metadata.title?.takeIf { it.isNotBlank() } ?: currentTitle
                val artist = metadata.artist ?: currentArtist
                val album = metadata.album ?: currentAlbum
                val durationMs = metadata.progress?.trackDuration ?: currentDurationMs
                val rawPositionMs = metadata.progress?.trackProgress
                val positionMs = rawPositionMs?.let(::serverPositionMs) ?: currentPositionMs
                val artUrl = metadata.artworkUrl ?: currentArtUrl
                val artChanged = artUrl != currentArtUrl

                currentTitle = title
                currentArtist = artist
                currentAlbum = album
                currentDurationMs = durationMs
                currentPositionMs = positionMs

                if (artChanged) {
                    currentArtUrl = artUrl
                    currentArt = loadArt(artUrl)
                }

                // playbackSpeed flows into `_currentIsPlaying` via the
                // derived combine in start(); no direct write here.
                notifyMetadataChanged()
                notifyStateChanged()
            }
        }

        collectorJobs += scope.launch {
            playerRepository.discontinuityCommands.collect { command ->
                if (command.playerId != sendspinPlayerId) return@collect
                val reason = when (command.kind) {
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.NEXT -> "next"
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.PREVIOUS -> "previous"
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.SEEK -> "seek"
                }
                Log.d("sendspindbg", "discontinuity command: $reason buf=${sendspinManager.bufferedAudioMs()}ms")
                sendspinManager.expectDiscontinuity(reason)
            }
        }

        // Collector 2: Observe sendspin player metadata from the players list
        collectorJobs += scope.launch {
            playerRepository.players
                .map { list ->
                    val ssId = sendspinPlayerId ?: return@map Pair<net.asksakis.massdroidv2.domain.model.Player?, Boolean>(null, false)
                    val self = list.find { it.playerId == ssId }
                    // Match the eager pre-connect check exactly. MA's group_childs
                    // includes the parent ITSELF, and activeGroup is unreliable (can be
                    // null when grouped, or transiently set for a solo player on the
                    // Sendspin connect handshake). Using isNotEmpty()/activeGroup here
                    // gave a FALSE-POSITIVE "grouped" verdict for a genuinely solo player
                    // (server group_childs=[]), wrongly swapping the engine into SYNC.
                    // The true test is "are there OTHER members besides me".
                    val selfInGroup = self?.groupChilds?.any { it != ssId } == true
                    val childOfOther = list.any { it.playerId != ssId && ssId in it.groupChilds }
                    Pair(self, selfInGroup || childOfOther)
                }
                .distinctUntilChanged()
                .collect { (player, inGroup) ->
                    // Don't decide group state until player data is available
                    if (player == null) return@collect
                    Log.d(TAG, "Group check: inGroup=$inGroup player=${player.displayName}")
                    val previousGroupState = lastObservedInGroup
                    val deferInitialSoloVerdict = previousGroupState == null &&
                        !inGroup &&
                        !isStreaming &&
                        System.currentTimeMillis() - groupObserverStartedAtMs < GROUP_SOLO_STARTUP_GRACE_MS
                    if (deferInitialSoloVerdict) {
                        Log.d(TAG, "Group check: deferring initial solo verdict, keeping SYNC default")
                        scheduleDeferredSoloCommit()
                    } else {
                        // A definitive verdict (group seen, streaming, or grace
                        // elapsed via the timer) supersedes the deferral.
                        deferredSoloJob?.cancel()
                        val joinedGroup = previousGroupState == false && inGroup
                        lastObservedInGroup = inGroup
                        sendspinManager.setInSyncGroup(inGroup)
                        if (inGroup) applyGroupedSyncFormat(player.playerId)
                        if (joinedGroup) requestGroupJoinRelock(player)
                    }
                    // Playing-state used to be reconciled from player.state
                    // here with a 400ms transient-pause debouncer. After the
                    // derived-flow refactor the source of truth for "is
                    // Sendspin actually playing right now" is the combine in
                    // start() (userIntent + transport + sync + metadata speed
                    // + selected player). This collector keeps only
                    // group/metadata bookkeeping.
                    if (!isStreaming) return@collect
                    val media = player.currentMedia
                    val hasMeaningfulMetadata =
                        media?.title?.isNotBlank() == true ||
                        media?.artist?.isNotBlank() == true ||
                        media?.album?.isNotBlank() == true ||
                        media?.imageUrl != null
                    if (!hasMeaningfulMetadata) {
                        notifyStateChanged()
                        return@collect
                    }
                    val title = media?.title?.takeIf { it.isNotBlank() } ?: currentTitle.ifBlank { "MassDroid Speaker" }
                    val artist = media?.artist?.takeIf { it.isNotBlank() } ?: currentArtist
                    val album = media?.album?.takeIf { it.isNotBlank() } ?: currentAlbum
                    val durationMs = ((media?.duration ?: 0.0) * 1000).toLong()
                    val artUrl = media?.imageUrl

                    val artChanged = artUrl != currentArtUrl

                    currentTitle = title
                    currentArtist = artist
                    currentAlbum = album
                    currentDurationMs = durationMs
                    currentTrackUri = media?.uri ?: currentTrackUri

                    if (artChanged && artUrl != null) {
                        currentArtUrl = artUrl
                        currentArt = loadArt(artUrl)
                    }

                    currentPositionMs = serverPositionMs(((media?.elapsedTime ?: 0.0) * 1000).toLong())

                    notifyMetadataChanged()
                    notifyStateChanged()
                }
        }

        // Collector: immediate audio pause/resume when the UI (or any other
        // caller of `playerRepository.play()/pause()`) targets the sendspin
        // player. This is the canonical UI-driven path for user intent —
        // both this and `handlePlay`/`handlePause` route through
        // `playerRepository.play()/pause()`, so writes here cover both.
        collectorJobs += scope.launch {
            playerRepository.playbackIntent.collect { willPlay ->
                val selectedId = playerRepository.selectedPlayer.value?.playerId ?: return@collect
                if (selectedId != sendspinPlayerId) return@collect
                if (willPlay) {
                    _userIntent.value = true
                    if (!hasAudioFocus) requestAudioFocus()
                    if (isReady) {
                        sendspinManager.resumeAudio()
                    } else {
                        ensureSendspinConnected()
                    }
                } else {
                    if (!isReady) return@collect
                    _userIntent.value = false
                    sendspinManager.pauseAudio()
                }
            }
        }

        // Derived flow: `_currentIsPlaying` = user intent AND sendspin is the
        // selected player AND the transport is actually flowing audio (not
        // syncing/error/idle) AND the server-reported playback speed is > 0.
        // Replaces the 17 imperative writes the previous design scattered
        // across collectors and handlers. Writes only `_currentIsPlaying`
        // and fires `notifyStateChanged()` on each emission so MediaSession
        // tracks the same canonical state as our internal consumers.
        //
        // Two chained 3-ary combines because the 5-ary overload's lambda
        // inference trips on the heterogeneous nullable flow types here.
        collectorJobs += scope.launch {
            val transportFlow = combine(
                sendspinManager.connectionState,
                sendspinManager.syncState,
                sendspinManager.serverMetadata,
            ) { transport, sync, metadata ->
                val streaming = transport == SendspinState.STREAMING
                val syncOk = sync != SyncState.SYNC_ERROR_REBUFFERING &&
                    sync != SyncState.IDLE
                // Treat unknown/missing speed as "not paused": the server
                // omits the field on first frames and on some transports.
                val speedOk = (metadata?.progress?.playbackSpeed ?: 1) > 0
                streaming && syncOk && speedOk
            }
            combine(
                _userIntent,
                playerRepository.selectedPlayer,
                transportFlow,
            ) { intent, selected, transportPlaying ->
                val sendspinSelected = sendspinPlayerId != null &&
                    selected?.playerId == sendspinPlayerId
                intent && sendspinSelected && transportPlaying
            }
                .distinctUntilChanged()
                .collect { playing ->
                    _currentIsPlaying.value = playing
                    notifyStateChanged()
                }
        }

        // Collector 4: Read settings and start sendspin
        collectorJobs += scope.launch {
            val url = settingsRepository.serverUrl.first()
            if (url.isBlank()) {
                Log.e(TAG, "No server URL, cannot start sendspin")
                return@launch
            }

            var clientId = settingsRepository.sendspinClientId.first()
            if (clientId == null) {
                clientId = UUID.randomUUID().toString()
                settingsRepository.setSendspinClientId(clientId)
            }

            sendspinPlayerId = clientId
            val ssState = sendspinManager.connectionState.value
            if (ssState == SendspinState.DISCONNECTED || ssState == SendspinState.ERROR) {
                // Seed the Sendspin player volume so the server doesn't reset
                // to 100% on connect. The coordinator picks the right source:
                // sync ON → derive from STREAM_MUSIC; sync OFF → use the
                // last-known MA volume (STREAM_MUSIC may be pinned at 100%
                // for car BT and we don't want that echoed into MA).
                val seedVolume = volumeCoordinator.seedStartupVolume()
                sendspinManager.setVolume(seedVolume)
                sendspinManager.start(clientId, clientName, buildCredentialsProvider())
                Log.d(TAG, "Sendspin started, playerId=$clientId seedVol=$seedVolume")
            } else {
                Log.d(TAG, "Sendspin already $ssState, skipping redundant start")
            }

            launch {
                val readyState = withTimeoutOrNull(10_000) {
                    sendspinManager.connectionState
                        .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
                }
                if (readyState == null) {
                    Log.w(TAG, "Startup: sendspin did not reach ready state, skipping snapshot restore")
                    return@launch
                }
                // Bootstrap user intent from the server's persisted playback
                // state. When the MA player was left playing before the app
                // restarted, the engine follows the server and streams, but no
                // local play() ran, so _userIntent stays false. A later transient
                // focus dip (e.g. another app plays a short clip) then refuses to
                // resume on focus regain and silently kills playback. Adopting a
                // server-confirmed PLAYING session as intent once at startup
                // closes that gap without deriving intent from transport (the
                // focus-loss handlers remain the sole writers of intent=false).
                val startedPlaying = withTimeoutOrNull(8_000) {
                    playerRepository.players
                        .map { list -> list.firstOrNull { it.playerId == clientId }?.state }
                        .first { it == PlaybackState.PLAYING }
                }
                if (startedPlaying != null && !_userIntent.value) {
                    Log.d(TAG, "Startup: adopting server-initiated playback as user intent")
                    _userIntent.value = true
                    if (!hasAudioFocus) requestAudioFocus()
                }
            }
        }

        // Collector 6: Refresh Sendspin transport when MA reconnects, and
        // auto-resume playback if the user wanted to play. The auto-resume
        // gate is the canonical `_userIntent` (cleared by noisy receiver on
        // BT disconnect, by handlePause, and by permanent audio focus loss).
        // This honours the BT-disconnect-pause contract: when the user
        // leaves the car, music does not silently come back on the phone
        // speaker; it stays paused until they explicitly play.
        //
        // We also explicitly require Sendspin to be the currently selected
        // player. User intent survives a player-selection switch (you can
        // be enjoying Sendspin in the kitchen and open the JBL screen to
        // control your living-room speaker), but auto-resume must never
        // fire on a player the user has stepped away from.
        //
        // The manager.refresh() call is a graceful reconnect through the
        // client's single state machine — no stop+start race with the
        // client's own backoff scheduler.
        collectorJobs += scope.launch {
            var connectedBefore = false
            wsClient.connectionState.collect { state ->
                val isConnected = state is ConnectionState.Connected
                if (isConnected && connectedBefore) {
                    val currentSsState = sendspinManager.connectionState.value
                    val sendspinIsSelected =
                        playerRepository.selectedPlayer.value?.playerId == sendspinPlayerId
                    val intent = _userIntent.value
                    val wantToResume = intent && sendspinIsSelected
                    Log.d(
                        TAG,
                        "MA reconnected, sendspin is $currentSsState, refreshing " +
                            "(wantToResume=$wantToResume, userIntent=$intent, " +
                            "sendspinIsSelected=$sendspinIsSelected)"
                    )
                    // Supersede an in-flight run. Moving `refresh()` off the
                    // collector means a second reconnect within the ack wait
                    // could otherwise refresh the Sendspin transport while the
                    // first run's pause was still unconfirmed, which is exactly
                    // the ordering this is here to guarantee. Cancelling only
                    // abandons the WAIT: that pause has already been written to
                    // the socket, and this run sends its own.
                    reconnectReassertJob?.cancel()
                    reconnectReassertJob =
                        launch { resumeOrReassertPause(wantToResume, intent, sendspinIsSelected) }
                }
                if (isConnected) connectedBefore = true
            }
        }
    }

    /**
     * Reconcile with the server after the MA connection comes back, then refresh
     * the Sendspin transport.
     *
     * The pause we send when an external sink disappears is fire-and-forget, and
     * a sink disappears exactly when a car is switched off, which is often the
     * same moment the phone loses the network. A command written into a socket
     * whose peer is already unreachable is buffered locally and then discarded
     * with the connection, with no error: the app believes it paused while the
     * server keeps the queue playing. When the connection returns, the server
     * pushes a `stream/start` for the still-playing queue and the engine plays it
     * out of whatever sink now exists, which is the phone speaker. That is how a
     * drive ended with the music continuing in the room for eight minutes.
     *
     * So when the user does not want playback and the output would land on the
     * phone speaker, re-assert the pause and WAIT for the server's answer before
     * refreshing the Sendspin transport. Waiting is the part that keeps it
     * silent: the two run on separate sockets, so refreshing first lets the
     * handshake and the `stream/start` overtake the pause and leak a few hundred
     * milliseconds of audio.
     *
     * The condition is read from live state rather than remembered, and the
     * pause is sent without first asking whether the server agrees, because
     * pausing an already-paused queue is a no-op. Being on the phone speaker is
     * what makes it safe: a sink the user chose (car, headphones) never takes
     * this path, so a head unit that sends its own play is unaffected.
     */
    private suspend fun resumeOrReassertPause(
        wantToResume: Boolean,
        intent: Boolean,
        sendspinIsSelected: Boolean,
    ) {
        val id = sendspinPlayerId
        // Paused, and the output would land on the phone speaker. `_userIntent` is
        // re-read on top of the `intent` the reconnect logged because the user can
        // press play in the gap before this coroutine runs, and a pause sent after
        // that would stop the playback they just asked for. The route is resolved
        // last, being the only expensive check.
        if (id != null && !intent && !_userIntent.value && sendspinIsSelected &&
            !resolveOutputRoute().isExternal
        ) {
            Log.d(TAG, "Reconnected paused on the phone speaker -> re-asserting pause before refresh")
            try {
                val answered = withTimeoutOrNull(PAUSE_REASSERT_TIMEOUT_MS) {
                    playerRepository.pauseConfirmed(id)
                }
                if (answered == null) {
                    Log.w(TAG, "Pause re-assert not answered within ${PAUSE_REASSERT_TIMEOUT_MS}ms")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pause re-assert failed: ${e.message}")
            }
        }
        sendspinManager.refresh()
        if (!wantToResume) return
        val ready = withTimeoutOrNull(15_000) {
            sendspinManager.connectionState
                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
        }
        if (ready == null) {
            Log.w(TAG, "Auto-resume aborted: Sendspin not ready within 15s")
            return
        }
        val resumeId = sendspinPlayerId ?: return
        Log.d(TAG, "Auto-resuming play after MA reconnect")
        try {
            playerRepository.play(resumeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auto-resume play failed: ${e.message}")
        }
    }

    fun stop() {
        for (job in collectorJobs) job.cancel(CancellationException("Sendspin stop"))
        collectorJobs.clear()
        autoRecoveryJob?.cancel()
        autoRecoveryJob = null
        // A duck left in place would be a silent player on the next start, and
        // nothing else restores the gain once this watch is gone.
        reconnectReassertJob?.cancel()
        reconnectReassertJob = null
        duckWatchJob?.cancel()
        duckWatchJob = null
        sendspinManager.restoreVolume()
        reconnectJob?.cancel()
        reconnectJob = null
        abandonAudioFocus()
        unregisterNoisyReceiver()
        releaseLocks()
        // volumeCoordinator outlives this controller — stopped by SendspinCoordinator.
        sendspinManager.stop()
        // Collectors are cancelled above so the derived flow no longer runs;
        // reset both flows explicitly so a subsequent start() begins clean.
        _userIntent.value = false
        _currentIsPlaying.value = false
        clearAllFreezes()
        isReady = false
        isStreaming = false
        notifyStateChanged()
        Log.d(TAG, "Sendspin controller stopped")
    }

    fun destroy() {
        try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        stop()
        scope.cancel()
    }

    // region Public playback commands

    fun handlePlay() {
        val id = sendspinPlayerId ?: return

        playerRepository.selectPlayer(id)
        _userIntent.value = true
        if (!hasAudioFocus) requestAudioFocus()
        if (isReady) sendspinManager.resumeAudio()
        scope.launch {
            if (!isReady && !ensureSendspinConnected()) {
                _userIntent.value = false
                return@launch
            }
            playerRepository.play(id)
        }
    }

    /**
     * Auto-start playback once a Bluetooth sink has CONNECTED AND STABILIZED (e.g.
     * getting in the car), so we don't depend on the head unit sending its own play
     * (some don't, or send it before Sendspin is ready — the source of the "connects
     * but no audio" race).
     *
     * It waits for the route to actually settle on BT — present and with no route
     * change for a quiet window — rather than a fixed delay, because the A2DP
     * handshake flaps speaker<->bt for a few seconds on connect. Then:
     * - Skipped when a phone call / Teams / Meet / any VoIP session is active
     *   (MODE_IN_CALL/IN_COMMUNICATION/RINGTONE) so connecting BT for a call never
     *   forces music on.
     * - Skipped when already playing (`_userIntent` set, e.g. a transient flap kept
     *   it going); this only kicks in after a real pause/idle.
     */
    fun autoPlayOnBtConnect() {
        if (sendspinPlayerId == null) return
        scope.launch {
            if (!awaitBtRouteStable()) {
                Log.d(TAG, "BT connect auto-play skipped: route did not stabilize on BT")
                return@launch
            }
            // Poke the unified car-route evaluation once BT re-stabilizes. The connect-time A2DP
            // flap re-binds the sink under a new AAudio device id (same product) via this native
            // reopen path, which does not fire a checkRouteChange transition; without this poke the
            // coordinator's settle re-check still aborts the exit (Oboe is routed to the car again),
            // but poking here re-affirms promptly. Idempotent: never re-pins.
            volumeCoordinator.onRouteChanged()
            if (_userIntent.value) return@launch  // already playing / intending to
            if (isInActiveCall()) {
                Log.d(TAG, "BT connect auto-play skipped: active call/communication")
                return@launch
            }
            Log.d(TAG, "BT route stabilized -> auto-play")
            handlePlay()
        }
    }

    /**
     * Suspend until the output route has settled on BT: it reads BT and the route
     * generation does not change across a quiet window (the connect handshake bumps
     * the generation on every speaker<->bt flap). Returns false on timeout (BT never
     * stabilized — failed connect / immediate disconnect).
     */
    private suspend fun awaitBtRouteStable(): Boolean {
        val deadline = System.currentTimeMillis() + BT_STABILIZE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (resolveOutputRoute() == OutputRoute.BT) {
                val genBefore = routeChangeGeneration
                delay(BT_STABILIZE_QUIET_MS)
                if (routeChangeGeneration == genBefore && resolveOutputRoute() == OutputRoute.BT) {
                    return true
                }
            } else {
                delay(BT_STABILIZE_POLL_MS)
            }
        }
        return false
    }

    fun handlePause() {
        val id = sendspinPlayerId ?: return

        _userIntent.value = false
        sendspinManager.pauseAudio()
        scope.launch { playerRepository.pause(id) }
    }

    fun handlePlayPause() {
        val id = sendspinPlayerId ?: return
        // Toggle off actual playback state, not intent: external controllers
        // (car HMI, watch) show MediaSession's currentIsPlaying, so a click
        // means "do what the icon implies".
        val wantPlay = !currentIsPlaying
        if (wantPlay) {
            _userIntent.value = true
            if (!hasAudioFocus) requestAudioFocus()
            if (isReady) sendspinManager.resumeAudio()
        } else {
            _userIntent.value = false
            sendspinManager.pauseAudio()
        }
        scope.launch {
            if (wantPlay && !isReady && !ensureSendspinConnected()) {
                _userIntent.value = false
                return@launch
            }
            playerRepository.playPause(id)
        }
    }

    fun handleNext() {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.next(id) }
    }

    fun handlePrev() {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.previous(id) }
    }

    fun handleSeek(posMs: Long) {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.seek(id, posMs / 1000.0) }
    }

    // endregion

    // region Audio focus

    /**
     * The focus transitions below log at INFO, not DEBUG, on purpose. ProGuard
     * strips `Log.d` and `Log.v` from release builds (`app/proguard-rules.pro`),
     * so on a release build the only focus line that used to survive was the duck
     * safety timeout. A field report then cannot be answered: two reports about an
     * alarm and an Android Auto notification interrupting playback came in with no
     * way to tell which focus event had arrived or what we did with it. Focus
     * changes happen a handful of times a day, so keeping them costs nothing in
     * log volume. Do not demote them back to `Log.d`.
     */
    private fun setupAudioFocus() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        val intent = _userIntent.value
                        Log.i(TAG, "Audio focus gained, isStreaming=$isStreaming isReady=$isReady userIntent=$intent")
                        hasAudioFocus = true
                        // Phone call still up: do not resume yet (mid-call focus
                        // blip). Keep the output frozen/paused; the real GAIN that
                        // arrives when the call ends (mode == NORMAL) resumes it.
                        if (isInActiveCall()) {
                            Log.i(TAG, "Focus gained during active call (mode=${audioManager.mode}); staying paused until the call ends")
                            return@setOnAudioFocusChangeListener
                        }
                        duckWatchJob?.cancel()
                        duckWatchJob = null
                        sendspinManager.restoreVolume()
                        // If the transient loss FROZE the solo output (buffer
                        // preserved), just unfreeze: playback resumes instantly
                        // and click-free from the intact buffer, no flush/rebuffer.
                        if (unfreezeOutput("focus")) {
                            Log.i(TAG, "Focus regained: unfroze output, buffer preserved")
                            return@setOnAudioFocusChangeListener
                        }
                        // Resume is gated on `_userIntent` — the canonical
                        // user-level intent flow. Noisy receiver, BT-to-speaker
                        // fallback, and permanent focus loss all clear it, so the
                        // route-change focus-shuffle that follows a BT disconnect
                        // will not silently hand audio off to the phone speaker.
                        if (!intent) {
                            Log.i(TAG, "Focus gained but intent is paused, staying paused")
                            return@setOnAudioFocusChangeListener
                        }
                        if (isStreaming) {
                            sendspinManager.resumeAudio()
                        } else {
                            // Not streaming: during a long interruption (e.g. a
                            // phone call routes audio to the earpiece, the Oboe
                            // stream disconnects and the server ends the idle
                            // stream) the engine drops to IDLE. Re-trigger
                            // playback so the server restarts the stream and
                            // audio comes back; reconnect first if the transport
                            // is no longer ready.
                            sendspinManager.resumeAudio()
                            val id = sendspinPlayerId
                            if (id != null) {
                                scope.launch {
                                    if (!isReady) ensureSendspinConnected()
                                    playerRepository.play(id)
                                }
                            }
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.i(TAG, "Audio focus lost permanently")
                        hasAudioFocus = false
                        unfreezeOutput("focus")
                        // Align intent with the permanent loss: another app
                        // has taken over, the user is no longer "trying to
                        // play". Without this, a later AUDIOFOCUS_GAIN would
                        // resume against the user's wish.
                        _userIntent.value = false
                        if (isStreaming) {
                            val id = sendspinPlayerId
                            if (id != null) {
                                scope.launch { playerRepository.pause(id) }
                            }
                        }
                        sendspinManager.pauseAudio()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        if (isStreaming) {
                            if (isInActiveCall()) {
                                // PHONE CALL: freeze (preserve the buffer) instead
                                // of flushing. After a flush the server continues
                                // from its look-ahead pointer and never resends the
                                // current position, so the resume held only future
                                // audio and the grouped snap waited out tens of
                                // seconds of silence. Freezing keeps the buffer; on
                                // regain solo resumes from the freeze point, grouped
                                // skips forward to the live group position under the
                                // mute. See unfreezeOutput.
                                Log.i(TAG, "Audio focus lost transiently (active call): freezing")
                                freezeOutput("focus")
                            } else {
                                // SHORT non-call interruption (notification ping,
                                // nav prompt): just DUCK. Freezing here stopped and
                                // reopened the Oboe stream, which on some HALs
                                // re-routed playback to the EARPIECE, and if the
                                // follow-up AUDIOFOCUS_GAIN was delayed it left
                                // playback muted long after the ping ended. Ducking
                                // keeps the stream alive and routed; restoreVolume()
                                // on GAIN brings the level back.
                                Log.i(TAG, "Audio focus lost transiently (no call): ducking")
                                duckUntilInterrupterEnds()
                            }
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.i(TAG, "Audio focus: ducking (pre-duck vol=${sendspinManager.currentVolume})")
                        duckUntilInterrupterEnds()
                    }
                }
            }
            .build()
    }

    /**
     * Duck, and restore the gain when the interrupting sound actually stops.
     *
     * The release used to be a flat ten-second timer whose premise was "no
     * AUDIOFOCUS_GAIN by now means the GAIN was dropped". That is wrong for any
     * interruption longer than the timer: measured against a real alarm on two
     * phones, the GAIN arrived after 14.8 s, 16.4 s, 12.8 s and 19.6 s, so the
     * timer restored full volume over a still-ringing alarm every time. The
     * events were never missing, we were second-guessing them.
     *
     * The platform does report when another app's sound starts and stops, through
     * [AudioManager.AudioPlaybackCallback]. An app without MODIFY_AUDIO_ROUTING
     * still sees the usage of other apps' players (AOSP's
     * `AudioPlaybackConfiguration.anonymizedCopy` keeps usage, content type and
     * flags, and strips only the identifiers), which is all this needs.
     *
     * So the wait has two phases, each with its own bound. First the interrupter
     * has to appear, within [DUCK_INTERRUPTER_WAIT_MS]; if it never does we
     * cannot observe it and restore on that deadline, which preserves the
     * original fix for an interrupter that takes focus and never gives it back.
     * Then we wait for it to go away, capped by [DUCK_MAX_MS]. A GAIN arriving at
     * any point cancels this and restores immediately.
     *
     * Both phases collect a freshly seeded flow, so the transition between them
     * cannot be missed: a registration reports the current state before waiting
     * for a change.
     */
    private fun duckUntilInterrupterEnds() {
        sendspinManager.duck()
        duckWatchJob?.cancel()
        duckWatchJob = scope.launch {
            val appeared = withTimeoutOrNull(DUCK_INTERRUPTER_WAIT_MS) {
                interrupterActive().first { it }
            }
            if (appeared == null) {
                Log.w(TAG, "Duck: no interrupting sound visible within ${DUCK_INTERRUPTER_WAIT_MS}ms, restoring gain")
                sendspinManager.restoreVolume()
                return@launch
            }
            val ended = withTimeoutOrNull(DUCK_MAX_MS) {
                interrupterActive().first { !it }
            }
            if (ended == null) {
                Log.w(TAG, "Duck: interrupting sound still playing after ${DUCK_MAX_MS}ms, restoring gain")
            } else {
                Log.i(TAG, "Duck: interrupting sound ended, restoring gain")
            }
            sendspinManager.restoreVolume()
        }
    }

    /**
     * Whether any other app is playing something with an [INTERRUPTER_USAGES]
     * usage. Emits the current answer on collection, then again on every playback
     * configuration change.
     */
    private fun interrupterActive(): Flow<Boolean> = callbackFlow {
        fun isActive(configs: List<android.media.AudioPlaybackConfiguration>) =
            configs.any { it.audioAttributes.usage in INTERRUPTER_USAGES }

        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                trySend(isActive(configs))
            }
        }
        audioManager.registerAudioPlaybackCallback(callback, null)
        // Seed with the state at registration time, so a sound that started (or
        // stopped) before we began listening is not waited for forever.
        trySend(isActive(audioManager.activePlaybackConfigurations))
        awaitClose { audioManager.unregisterAudioPlaybackCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Request audio focus for ANY output path (BT, speaker, Android Auto, wired…).
     * Distinguishes the three platform results instead of collapsing them to
     * "denied":
     *  - GRANTED: we hold focus now.
     *  - DELAYED: accepted but queued (common on Samsung's per-device MultiFocus
     *    stack during a BT route transition). We do NOT hold focus yet, but the
     *    system delivers AUDIOFOCUS_GAIN when it frees up and the listener resumes
     *    then — so this is benign, not a real denial.
     *  - FAILED: genuinely refused (another app holds non-yielding focus).
     * Returns true only when focus is held right now; the engine plays regardless
     * (focus is advisory for our own output), the result just drives ducking/resume.
     */
    private fun requestAudioFocus(): Boolean {
        hasAudioFocus = when (audioManager.requestAudioFocus(focusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                Log.d(TAG, "Audio focus request: granted")
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                Log.d(TAG, "Audio focus request: delayed (gain will arrive when the path frees up)")
                false
            }
            else -> {
                Log.d(TAG, "Audio focus request: denied")
                false
            }
        }
        return hasAudioFocus
    }

    // True while a phone call is ringing or active. A transient focus blip
    // mid-call (ringing -> active transition, call-waiting, speakerphone
    // toggle, or the Oboe stream reconnecting) can deliver AUDIOFOCUS_GAIN
    // while the call is still up; resuming then plays music over the call. We
    // gate the focus-driven resume on this and wait for the real post-call GAIN.
    private fun isInActiveCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    private fun abandonAudioFocus() {
        if (!::focusRequest.isInitialized) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    // endregion

    // region Noisy receiver

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                context.unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
    }

    // endregion

    // region Locks

    private fun acquireLocks() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MassDroid::Sendspin")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(wifiMode, "MassDroid::Sendspin")
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // endregion

    // region Format + connection helpers

    private suspend fun applyPreferredFormatForCurrentNetwork(playerId: String) {
        try {
            if (lastObservedInGroup == true) {
                applyGroupedSyncFormat(playerId)
                return
            }
            val formatName = settingsRepository.sendspinAudioFormat.first()
            val format = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(formatName)
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val apiValue = format.toApiValue(isWifi)
            savePreferredFormatIfNeeded(
                playerId = playerId,
                apiValue = apiValue,
                reason = "$format/${if (isWifi) "WiFi" else "Mobile"}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Format apply failed: ${e.message}")
        }
    }

    private suspend fun applyGroupedSyncFormat(playerId: String) {
        try {
            savePreferredFormatIfNeeded(
                playerId = playerId,
                apiValue = GROUPED_SENDSPIN_FORMAT,
                reason = "grouped sync",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Grouped format apply failed: ${e.message}")
        }
    }

    private suspend fun savePreferredFormatIfNeeded(
        playerId: String,
        apiValue: String,
        reason: String,
    ) {
        // Authoritative check against the actual server config only. No
        // in-memory cache: the server can clear an "incompatible" override on
        // its own, and a stale cache would then never re-apply the format.
        val current = playerRepository.getPlayerConfig(playerId)?.sendspinFormat
        if (current == apiValue) {
            Log.d(TAG, "Sendspin format already $apiValue ($reason), skipping save")
            return
        }
        playerRepository.savePlayerConfig(playerId, mapOf("preferred_sendspin_format" to apiValue))
        Log.d(TAG, "Applied Sendspin format $apiValue ($reason, was=${current ?: "unknown"})")
    }

    // region Sendspin connection helpers

    /**
     * Credentials lookup used by the SendspinClient on every (re)connect
     * attempt. Pulls the live MA WebSocket token if present, falling back to
     * the persisted token so the very first attempt after a process restart
     * also has something to send.
     */
    private fun buildCredentialsProvider(): suspend () -> net.asksakis.massdroidv2.data.sendspin.SendspinClient.Credentials? = {
        val url = settingsRepository.serverUrl.first()
        val token = wsClient.authToken ?: settingsRepository.authToken.first()
        if (url.isBlank() || token.isBlank()) {
            null
        } else {
            net.asksakis.massdroidv2.data.sendspin.SendspinClient.Credentials(url, token)
        }
    }

    /**
     * Ensure Sendspin is in a playable state. Three cases:
     *
     *  1. Already SYNCING/STREAMING -> immediate true.
     *  2. Mid-connect (CONNECTING/AUTHENTICATING/HANDSHAKING) -> wait for
     *     SYNCING/STREAMING with a timeout.
     *  3. DISCONNECTED/ERROR -> apply preferred network format, then nudge
     *     the client to retry now via refresh(). The client's own backoff
     *     scheduler owns the reconnect loop; we just kick it.
     */
    private suspend fun ensureSendspinConnected(): Boolean {
        reconnectJob?.let { existing ->
            if (existing.isActive) {
                Log.d(TAG, "Reconnect already in progress, waiting")
                return existing.await()
            }
            reconnectJob = null
        }

        val state = sendspinManager.connectionState.value
        if (state == SendspinState.SYNCING || state == SendspinState.STREAMING) return true

        if (state != SendspinState.DISCONNECTED && state != SendspinState.ERROR) {
            Log.d(TAG, "Sendspin is $state, waiting for ready")
            return waitForReady(timeoutMs = 10_000)
        }

        val clientId = sendspinPlayerId ?: return false

        val job = scope.async {
            applyPreferredFormatForCurrentNetwork(clientId)
            val latestState = sendspinManager.connectionState.value
            if (latestState == SendspinState.SYNCING || latestState == SendspinState.STREAMING) {
                return@async true
            }
            Log.d(TAG, "Nudging sendspin reconnect (was $state, latest=$latestState)")
            sendspinManager.refresh()
            waitForReady(timeoutMs = 10_000)
        }
        reconnectJob = job
        return try {
            job.await()
        } finally {
            if (reconnectJob === job) reconnectJob = null
        }
    }

    private suspend fun waitForReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            sendspinManager.connectionState
                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
        } != null

    // endregion

    // endregion

    // region Art loading

    private suspend fun loadArt(url: String?): Bitmap? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val client = wsClient.getImageClient()
                val request = okhttp3.Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                // Report a refusal instead of decoding it. Music Assistant answers an
                // unresolvable image with 404 (verified, and with an empty body), which
                // decodes to null and left a missing cover with no explanation at all.
                if (!response.isSuccessful) {
                    Log.w(TAG, "Album art rejected by server: HTTP ${response.code} for $url")
                    response.close()
                    return@withContext null
                }
                response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load album art (${e.javaClass.simpleName}: ${e.message}): $url")
                null
            }
        }
    }

    // endregion

    // region Notification callbacks

    private fun notifyMetadataChanged() {
        onMetadataChanged(
            SendspinMetadata(
                title = currentTitle,
                artist = currentArtist,
                album = currentAlbum,
                durationMs = currentDurationMs,
                positionMs = currentPositionMs,
                art = currentArt,
                artUrl = currentArtUrl,
                trackUri = currentTrackUri
            )
        )
    }

    private fun notifyStateChanged() {
        onStateChanged(isReady, isStreaming, currentIsPlaying)
    }

    private fun recomputeAvailability() {
        isStreaming = transportState == SendspinState.STREAMING
        isReady = (transportState == SendspinState.SYNCING || transportState == SendspinState.STREAMING) &&
            localSyncState != SyncState.SYNC_ERROR_REBUFFERING && localSyncState != SyncState.IDLE
    }

    // endregion
}
