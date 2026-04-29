package net.asksakis.massdroidv2.service

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.LocalSpeakerVolumeBridge
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.ShortcutAction
import net.asksakis.massdroidv2.ui.ShortcutActionDispatcher

class SendspinCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val localVolumeBridge: LocalSpeakerVolumeBridge,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val onConnectionStateChanged: () -> Unit,
    private val onTargetChanged: (reason: String) -> Unit,
    private val onActive: (reason: String) -> Unit,
    private val onWifiConnected: (reason: String) -> Unit,
) {
    companion object {
        private const val TAG = "SendspinCoord"
    }

    var playerId: String? = null
        private set

    var isActive: Boolean = false
        private set

    var controller: SendspinAudioController? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var volumeObserver: ContentObserver? = null
    private var btAudioCallback: AudioDeviceCallback? = null

    fun start() {
        createController()
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
        controller?.destroy()
        controller = null
        isActive = false
    }

    fun shouldRouteToSendspin(): Boolean =
        isBtA2dpActive() && isActive && playerId != null &&
            playerRepository.selectedPlayer.value?.playerId != playerId

    fun shouldBlockProximitySelectionForBt(): Boolean =
        isBtA2dpActive() && isActive && playerId != null

    private fun createController() {
        controller = SendspinAudioController(
            context = context,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            localVolumeBridge = localVolumeBridge,
            onMetadataChanged = { _ -> },
            onStateChanged = { _, _, _ -> onConnectionStateChanged() }
        )
    }

    private fun observePlayerId() {
        scope.launch {
            settingsRepository.sendspinClientId.collect { id ->
                val changed = playerId != id
                playerId = id
                if (changed && id != null) onTargetChanged("sendspin_id_loaded")
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
    }

    private fun observeAudioFormatPreference() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wifiState = MutableStateFlow(isOnWifi(connectivityManager))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (wifi != wifiState.value) {
                    Log.d(TAG, "Network changed: ${if (wifi) "WiFi" else "Mobile"}")
                    if (wifi) onWifiConnected("wifi-connected")
                }
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
                    val apiValue = format.toApiValue(wifiState.value)
                    val netType = if (wifiState.value) "WiFi" else "Mobile"
                    Log.d(TAG, "Audio format preference changed: $format, network=$netType, sending $apiValue")
                    try {
                        playerRepository.savePlayerConfig(
                            sendspinPlayerId,
                            mapOf("preferred_sendspin_format" to apiValue)
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
                    val activeController = controller
                    if (activeController != null && activeController.isStreaming) {
                        val codec = format.toCodec(isWifi)
                        val bitDepth = format.toBitDepth(isWifi)
                        Log.d(TAG, "Smart mode network switch: $netType, requesting $codec ${bitDepth}bit")
                        sendspinManager.requestFormat(codec, bitDepth = bitDepth)
                    } else {
                        Log.d(TAG, "Network: $netType (Smart mode, not streaming, skip format request)")
                    }
                } else {
                    Log.d(TAG, "Network: $netType (format $format)")
                }
                lastWifi = isWifi
            }
        }
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
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val sendspinPlayerId = playerId ?: return
                if (!isActive) return
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val percent = if (max > 0) ((cur * 100f / max) + 0.5f).toInt() else 0
                scope.launch { playerRepository.setVolume(sendspinPlayerId, percent) }
            }
        }
        volumeObserver = observer
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
    }

    private fun registerBtAudioDeviceCallback() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        btAudioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val btAdded = addedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                val target = playerId
                if (btAdded && isActive && target != null) {
                    val currentSelected = playerRepository.selectedPlayer.value?.playerId
                    if (currentSelected != target) {
                        val deviceName = addedDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }?.productName
                        Log.d(TAG, "BT A2DP connected ($deviceName), auto-selecting Sendspin player")
                        playerRepository.selectPlayer(target)
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(btAudioCallback, null)
    }

    private fun unregisterBtAudioDeviceCallback() {
        val callback = btAudioCallback ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(callback)
        btAudioCallback = null
    }

    private fun unregisterPhoneVolumeObserver() {
        volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun isOnWifi(connectivityManager: ConnectivityManager): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isBtA2dpActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }
}
