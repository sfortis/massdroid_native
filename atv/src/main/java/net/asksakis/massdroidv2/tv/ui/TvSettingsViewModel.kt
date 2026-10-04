package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

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
    calibrationCoordinator: AcousticCalibrationCoordinator,
) : ViewModel() {

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
}
