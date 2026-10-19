package net.asksakis.massdroidv2.tv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/** Where a rename of this TV's player stands, for the Save button and its message. */
enum class PlayerRenameStatus { IDLE, SAVING, SAVED, FAILED }

/** The output delay of the output the TV plays on now. */
data class TvOutputDelay(val routeKey: String, val outputName: String, val delayMs: Int)

/**
 * TV settings. The output delay is the Sendspin spec's delay beyond the audio
 * port: how late the TV or AV receiver plays what the box sends over HDMI. It
 * is stored per output as a manual calibration, and the core
 * SendspinAudioController applies it live: this device plays that much
 * earlier, and the server is told through `static_delay_ms`.
 */
@HiltViewModel
class TvSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    calibrationCoordinator: AcousticCalibrationCoordinator,
) : ViewModel() {

    /**
     * The name Music Assistant shows for this TV's own Sendspin player, or null until the
     * player has registered. The client id setting holds the id of the transport in use
     * (the Noise key on MA 2.10 and later), so it is the id Music Assistant knows.
     */
    val playerName: StateFlow<String?> = combine(
        settingsRepository.sendspinClientId,
        playerRepository.players,
    ) { id, players ->
        id?.let { playerId -> players.firstOrNull { it.playerId == playerId }?.displayName }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _renameStatus = MutableStateFlow(PlayerRenameStatus.IDLE)
    val renameStatus: StateFlow<PlayerRenameStatus> = _renameStatus.asStateFlow()

    /**
     * Renames this TV's player in Music Assistant, through the same player config the phone
     * app writes. The name is kept by the server, so it survives reconnects and restarts.
     */
    fun renamePlayer(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _renameStatus.value == PlayerRenameStatus.SAVING) return
        viewModelScope.launch {
            val playerId = settingsRepository.sendspinClientId.first()
            if (playerId == null) {
                _renameStatus.value = PlayerRenameStatus.FAILED
                return@launch
            }
            _renameStatus.value = PlayerRenameStatus.SAVING
            _renameStatus.value = try {
                playerRepository.renamePlayer(playerId, trimmed)
                PlayerRenameStatus.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Player rename failed: ${e.message}")
                PlayerRenameStatus.FAILED
            }
        }
    }

    /** Clears a saved or failed message once the user edits the name again. */
    fun onPlayerNameEdited() {
        if (_renameStatus.value != PlayerRenameStatus.SAVING) _renameStatus.value = PlayerRenameStatus.IDLE
    }

    /** Null while the output cannot be named (several Bluetooth sinks, none routed). */
    val outputDelay: StateFlow<TvOutputDelay?> = combine(
        calibrationCoordinator.currentOutput,
        settingsRepository.acousticRouteCalibrations,
    ) { output, calibrations ->
        output.routeKey?.let { key ->
            TvOutputDelay(key, output.name, ((calibrations[key]?.correctionUs ?: 0L) / 1000L).toInt())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Audio quality/codec. Applied live by the core coordinator's format observer. */
    val audioFormat: StateFlow<SendspinAudioFormat> = settingsRepository.sendspinAudioFormat
        .map { SendspinAudioFormat.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendspinAudioFormat.AUTOMATIC)

    fun setOutputDelay(ms: Int) {
        val routeKey = outputDelay.value?.routeKey ?: return
        val clamped = ms.coerceIn(0, SettingsRepository.MANUAL_OUTPUT_DELAY_MAX_MS)
        viewModelScope.launch {
            if (clamped == 0) {
                settingsRepository.removeAcousticRouteCalibration(routeKey)
            } else {
                settingsRepository.setAcousticRouteCalibration(routeKey, AcousticRouteCalibration.manual(clamped))
            }
        }
    }

    fun setAudioFormat(format: SendspinAudioFormat) {
        viewModelScope.launch { settingsRepository.setSendspinAudioFormat(format.name) }
    }

    private companion object {
        const val TAG = "TvSettingsVM"
    }
}
