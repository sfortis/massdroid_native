package net.asksakis.massdroidv2.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.VolumeSetArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher

class SendspinCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val volumeCoordinator: SendspinVolumeCoordinator,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    // Sendspin player name for this device; defaults to "MassDroid" (phone).
    private val clientName: String = "MassDroid",
    // Android Automotive OS build: the device IS the car speaker, so Sendspin is
    // the one and only player. Output is the built-in car audio HAL, NOT BT A2DP,
    // so the BT-gated auto-select / routing used on the phone never fires in the
    // car. In automotive we therefore force Sendspin enabled, auto-select it
    // unconditionally, and route all transport to it regardless of BT state.
    private val isAutomotive: Boolean = false,
    private val onConnectionStateChanged: () -> Unit,
    private val onTargetChanged: (reason: String) -> Unit,
    private val onActive: (reason: String) -> Unit,
    private val onInactive: (reason: String) -> Unit,
    private val onWifiConnected: (reason: String) -> Unit,
    // Optional now-playing surface hooks (used by the TV front-end to drive a
    // MediaSession). Default no-op so the phone wiring is unaffected.
    private val onMetadata: (SendspinMetadata) -> Unit = {},
    private val onPlayingChanged: (playing: Boolean) -> Unit = {},
) {
    companion object {
        private const val TAG = "SendspinCoord"
        private const val GROUPED_SENDSPIN_FORMAT = "flac:48000:16:2"
        // Hidden but long-stable AudioManager broadcast: sent only on a volume change
        // (carries stream type + new/old value), unlike a Settings.System observer
        // which also wakes on unrelated changes. String literals because the
        // constants are @hide.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }

    var playerId: String? = null
        private set

    var isActive: Boolean = false
        private set

    var controller: SendspinAudioController? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var btAudioCallback: AudioDeviceCallback? = null

    fun start() {
        createController()
        volumeCoordinator.start(scope)
        // Car build: Sendspin is the sole output, so force it on. There is no
        // in-car UI to enable it, and the user setting is irrelevant here.
        // Also lock the engine to DIRECT (solo): a car is never part of a
        // multi-room sync group, so the group-detection heuristic must never be
        // allowed to swap us into the getTimestamp-dependent SYNC engine.
        if (isAutomotive) {
            scope.launch { settingsRepository.setSendspinEnabled(true) }
            sendspinManager.setForceSolo(true)
        }
        observePlayerId()
        observeEnabled()
        observeConnectionState()
        observeAudioFormatPreference()
        observeShortcutActions()
        observePhoneVolume()
        registerBtAudioDeviceCallback()
    }

    fun destroy() {
        unregisterBtAudioDeviceCallback()
        unregisterPhoneVolumeObserver()
        unregisterNetworkCallback()
        volumeCoordinator.stop()
        controller?.destroy()
        controller = null
        isActive = false
        onInactive("destroy")
    }

    fun shouldRouteToSendspin(): Boolean =
        if (isAutomotive) {
            // The car device is the speaker; whenever Sendspin is up, every
            // transport command drives it directly (the proven BT-routed path),
            // independent of which player is "selected" or any BT state.
            isActive && playerId != null
        } else {
            isBtA2dpActive() && isActive && playerId != null &&
                playerRepository.selectedPlayer.value?.playerId != playerId
        }

    fun shouldBlockProximitySelectionForBt(): Boolean =
        isBtA2dpActive() && isActive && playerId != null

    private fun createController() {
        controller = SendspinAudioController(
            context = context,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            volumeCoordinator = volumeCoordinator,
            // Car build registers as a distinct MA player name so it is obvious which
            // entry is the head unit (the phone/TV also register as "MassDroid").
            clientName = if (isAutomotive) "$clientName AAOS" else clientName,
            onMetadataChanged = { onMetadata(it) },
            onStateChanged = { _, _, playing ->
                onConnectionStateChanged()
                onPlayingChanged(playing)
            }
        )
    }

    private fun observePlayerId() {
        scope.launch {
            settingsRepository.sendspinClientId.collect { id ->
                val changed = playerId != id
                playerId = id
                if (changed && id != null) {
                    onTargetChanged("sendspin_id_loaded")
                    autoSelectSendspinForBt("sendspin_id_loaded")
                }
            }
        }
    }

    private fun observeEnabled() {
        scope.launch {
            settingsRepository.sendspinEnabled.collect { enabled ->
                Log.d(TAG, "Sendspin enabled: $enabled")
                if (enabled) {
                    startIfConnected("sendspin_active")
                } else if (isActive) {
                    isActive = false
                    controller?.stop()
                    onInactive("sendspin_disabled")
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            wsClient.connectionState.collect { state ->
                onConnectionStateChanged()
                if (state is ConnectionState.Connected && settingsRepository.sendspinEnabled.first()) {
                    startIfConnected("ws_connected")
                }
            }
        }
    }

    private fun startIfConnected(reason: String) {
        if (isActive) return
        if (wsClient.connectionState.value !is ConnectionState.Connected) return
        isActive = true
        controller?.start()
        onActive(reason)
        autoSelectSendspinForBt(reason)
    }

    private fun observeAudioFormatPreference() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wifiState = MutableStateFlow(isOnWifi(connectivityManager))
        // Tracks the initial transport — the first onCapabilitiesChanged is the
        // baseline (already-active network at registration), not a transition,
        // so we skip handover handling for it. Subsequent toggles trigger the
        // WS + connection-pool reset path.
        var lastWifiSeen: Boolean? = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Treat wired Ethernet as a local high-bandwidth network too (Shield/TV).
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (wifi != wifiState.value) {
                    Log.d(TAG, "Network changed: ${if (wifi) "WiFi" else "Mobile"}")
                    if (wifi) onWifiConnected("wifi-connected")
                }
                // Force fast WS reconnect + OkHttp pool eviction on transport
                // flip. Without this, the dead WiFi socket keeps the WS alive
                // for ~30-60s until the ping interval times out, and Coil keeps
                // pulling stale connections from the pool (visible as missing
                // album art after the switch).
                if (lastWifiSeen != null && lastWifiSeen != wifi) {
                    Log.d(TAG, "Transport flipped (wifi=$lastWifiSeen → $wifi), nudging WS + pool")
                    wsClient.handleTransportChange()
                }
                lastWifiSeen = wifi
                wifiState.value = wifi
                sendspinManager.setCellularHint(!wifi)
            }

            override fun onLost(network: Network) {
                val wifi = isOnWifi(connectivityManager)
                Log.d(TAG, "Network lost, fallback: ${if (wifi) "WiFi" else "Mobile/None"}")
                wifiState.value = wifi
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)

        scope.launch {
            var lastFormat = settingsRepository.sendspinAudioFormat.first()
            settingsRepository.sendspinAudioFormat
                .distinctUntilChanged()
                .collect { formatName ->
                    if (formatName == lastFormat) {
                        lastFormat = formatName
                        return@collect
                    }
                    lastFormat = formatName
                    val sendspinPlayerId = settingsRepository.sendspinClientId.first() ?: return@collect
                    if (wsClient.connectionState.value !is ConnectionState.Connected) return@collect
                    val format = SendspinAudioFormat.fromStored(formatName)
                    val apiValue = if (isSendspinInGroup(sendspinPlayerId)) {
                        GROUPED_SENDSPIN_FORMAT
                    } else {
                        format.toApiValue(wifiState.value)
                    }
                    val netType = if (wifiState.value) "WiFi" else "Mobile"
                    try {
                        savePreferredFormatIfNeeded(
                            playerId = sendspinPlayerId,
                            apiValue = apiValue,
                            reason = "preference $format/$netType",
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update audio format: ${e.message}")
                    }
                }
        }

        scope.launch {
            var lastWifi: Boolean? = null
            wifiState.collect { isWifi ->
                val netType = if (isWifi) "WiFi" else "Mobile"
                val format = SendspinAudioFormat.fromStored(settingsRepository.sendspinAudioFormat.first())
                if (lastWifi != null && lastWifi != isWifi && format == SendspinAudioFormat.SMART) {
                    val sendspinPlayerId = settingsRepository.sendspinClientId.first()
                    if (sendspinPlayerId != null && wsClient.connectionState.value is ConnectionState.Connected) {
                        val apiValue = if (isSendspinInGroup(sendspinPlayerId)) {
                            GROUPED_SENDSPIN_FORMAT
                        } else {
                            format.toApiValue(isWifi)
                        }
                        try {
                            savePreferredFormatIfNeeded(
                                playerId = sendspinPlayerId,
                                apiValue = apiValue,
                                reason = "network $netType",
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update network format: ${e.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Network: $netType (format $format)")
                }
                lastWifi = isWifi
            }
        }
    }

    private fun isSendspinInGroup(sendspinPlayerId: String): Boolean {
        val players = playerRepository.players.value
        val self = players.find { it.playerId == sendspinPlayerId }
        val selfInGroup = self?.activeGroup != null || self?.groupChilds?.isNotEmpty() == true
        val childOfOther = players.any { it.playerId != sendspinPlayerId && sendspinPlayerId in it.groupChilds }
        return selfInGroup || childOfOther
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

    private fun observeShortcutActions() {
        scope.launch {
            shortcutDispatcher.pendingAction
                .filterNotNull()
                .collect { action ->
                    if (action !is ShortcutAction.PlayNow) return@collect
                    shortcutDispatcher.consume()
                    Log.d(TAG, "PlayNow shortcut: sendspinActive=$isActive")
                    val sendspinOn = settingsRepository.sendspinEnabled.first()
                    if (!sendspinOn) {
                        settingsRepository.setSendspinEnabled(true)
                    } else {
                        startIfConnected("shortcut_play_now")
                    }
                    val targetId = playerId ?: settingsRepository.sendspinClientId.first()
                    if (targetId != null) {
                        controller?.handlePlay()
                    }
                }
        }
    }

    private fun observePhoneVolume() {
        // Listen for the dedicated volume-changed broadcast instead of a
        // Settings.System ContentObserver. The observer watched the WHOLE URI and so
        // also woke on unrelated changes (notably screen-on), which could be misread
        // as a user volume change and bounced to MA (the "jumped to 100% on
        // screen-on" bug). This broadcast fires ONLY on a real volume change and
        // carries the stream type, so we react solely to STREAM_MUSIC.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!isActive) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (stream != AudioManager.STREAM_MUSIC) return
                // The coordinator reads the STREAM_MUSIC index itself and decides
                // (index-based dedup) whether this is a real user change or its own
                // mirror write.
                volumeCoordinator.onPhoneStreamVolumeChanged()
            }
        }
        volumeReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerBtAudioDeviceCallback() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        btAudioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val btAdded = addedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (btAdded) {
                    val deviceName = addedDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    }?.productName
                    autoSelectSendspinForBt("bt_device_added", deviceName)
                    // Start playback on connect (gated on no active call/meeting,
                    // inside the controller). Only when Sendspin is the active path.
                    if (isActive) controller?.autoPlayOnBtConnect()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(btAudioCallback, null)
        autoSelectSendspinForBt("bt_callback_registered")
    }

    private fun autoSelectSendspinForBt(reason: String, deviceName: CharSequence? = null) {
        scope.launch {
            // Phone/TV: only hijack selection when audio is actually going out
            // over BT A2DP. Car: Sendspin is the only player, so always select it
            // (the car's audio HAL is not a BT A2DP device).
            if (!isAutomotive && !isBtA2dpActive()) return@launch
            val target = playerId ?: settingsRepository.sendspinClientId.first() ?: return@launch
            val currentSelected = playerRepository.selectedPlayer.value?.playerId
            if (currentSelected == target) return@launch
            Log.d(
                TAG,
                "BT A2DP active${deviceName?.let { " ($it)" } ?: ""}, auto-selecting Sendspin player ($reason)"
            )
            playerRepository.selectPlayer(target)
        }
    }

    private fun unregisterBtAudioDeviceCallback() {
        val callback = btAudioCallback ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(callback)
        btAudioCallback = null
    }

    private fun unregisterPhoneVolumeObserver() {
        volumeReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        volumeReceiver = null
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    // "Local high-bandwidth" network: Wi-Fi OR wired Ethernet (Android TV /
    // Shield is frequently on Ethernet). Both pick the lossless FLAC path and
    // skip the cellular recovery buffers; only true mobile data falls back to
    // Opus. Named isOnWifi for historical continuity.
    private fun isOnWifi(connectivityManager: ConnectivityManager): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun isBtA2dpActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }
}
