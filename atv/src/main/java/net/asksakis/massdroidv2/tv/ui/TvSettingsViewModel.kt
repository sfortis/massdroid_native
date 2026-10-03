package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * TV settings. The audio sync delay (ms) is persisted to the shared settings and
 * applied live by the core SendspinAudioController (which observes it and calls
 * SendspinManager.setSyncDelayMs). Negative shifts this device's playback
 * EARLIER, to compensate HDMI/AVR output latency so the Shield aligns with the
 * rest of a sync group.
 */
@HiltViewModel
class TvSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val syncDelayMs: StateFlow<Int> = settingsRepository.sendspinSyncDelayMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Audio quality/codec. Applied live by the core coordinator's format observer. */
    val audioFormat: StateFlow<SendspinAudioFormat> = settingsRepository.sendspinAudioFormat
        .map { SendspinAudioFormat.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendspinAudioFormat.AUTOMATIC)

    fun setSyncDelay(ms: Int) {
        viewModelScope.launch {
            settingsRepository.setSendspinSyncDelayMs(ms.coerceIn(-1000, 1000))
        }
    }

    fun setAudioFormat(format: SendspinAudioFormat) {
        viewModelScope.launch { settingsRepository.setSendspinAudioFormat(format.name) }
    }

    fun nudge(deltaMs: Int) = setSyncDelay(syncDelayMs.value + deltaMs)
}
