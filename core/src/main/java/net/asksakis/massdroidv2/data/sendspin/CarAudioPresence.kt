package net.asksakis.massdroidv2.data.sendspin

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a Bluetooth sink the user flagged as car audio is connected right now.
 *
 * Event-driven from the platform's device callback and the car-device setting, so
 * consumers observe a StateFlow instead of polling AudioManager. Follow Me uses it
 * to stop reacting to motion while driving: in the car the significant-motion
 * sensor fires every few seconds and every trigger ran a BLE burst that could hear
 * nothing (66 triggers and 384 empty reads in 15 minutes on 2026-09-10), and the
 * falling edge is the moment to re-detect the room, because stepping out of the
 * car is how the user arrives somewhere.
 */
@Singleton
class CarAudioPresence @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var carDevices: Set<String> = emptySet()
    private var started = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = recompute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = recompute()
    }

    /** Idempotent. The callback is cheap and the singleton lives as long as the process. */
    fun start() {
        if (started) return
        started = true
        audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        scope.launch {
            settingsRepository.carAudioBtDevices.collect { devices ->
                carDevices = devices
                recompute()
            }
        }
        recompute()
    }

    private fun recompute() {
        val now = carSinkConnected(audioManager?.connectedBtSinkKeys().orEmpty(), carDevices)
        if (_connected.value != now) {
            Log.d(TAG, if (now) "Car audio connected" else "Car audio released")
            _connected.value = now
        }
    }

    internal companion object {
        private const val TAG = "CarAudioPresence"

        /** Pure rule, tested: any connected sink key that the user flagged as car audio. */
        fun carSinkConnected(connectedSinkKeys: Collection<String>, carDevices: Set<String>): Boolean =
            carDevices.isNotEmpty() && connectedSinkKeys.any { it in carDevices }
    }
}
