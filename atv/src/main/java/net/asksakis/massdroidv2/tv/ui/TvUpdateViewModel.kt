package net.asksakis.massdroidv2.tv.ui

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.tv.BuildConfig
import javax.inject.Inject

/** Where the download of an update stands. */
sealed interface TvUpdateDownload {
    data object Idle : TvUpdateDownload
    data class Running(val percent: Int) : TvUpdateDownload
    data object Failed : TvUpdateDownload
}

/**
 * The GitHub updater on the TV: checks for a new release when the app connects to Music
 * Assistant (the shared checker allows one network check a day), and downloads and hands the
 * TV APK to the package installer when the user accepts. Release builds only: a debug build
 * has its own package, which the release APK would install beside it instead of updating.
 */
@HiltViewModel
class TvUpdateViewModel @Inject constructor(
    private val checker: AppUpdateChecker,
    private val wsClient: MaWebSocketClient,
) : ViewModel() {

    val pendingUpdate: StateFlow<AppUpdateChecker.UpdateInfo?> = checker.pendingUpdate

    private val _download = MutableStateFlow<TvUpdateDownload>(TvUpdateDownload.Idle)
    val download: StateFlow<TvUpdateDownload> = _download.asStateFlow()

    /** The package installer intent, for the screen to start. */
    private val _installIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val installIntent: SharedFlow<Intent> = _installIntent.asSharedFlow()

    init {
        if (!BuildConfig.DEBUG) viewModelScope.launch { checkWhenConnected() }
    }

    private suspend fun checkWhenConnected() {
        wsClient.connectionState.collect { state ->
            if (state !is ConnectionState.Connected) return@collect
            try {
                checker.checkForUpdates(force = false, includePrerelease = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Update check skipped: ${e.message}")
            }
        }
    }

    fun downloadAndInstall() {
        val info = pendingUpdate.value ?: return
        if (_download.value is TvUpdateDownload.Running) return
        _download.value = TvUpdateDownload.Running(0)
        viewModelScope.launch {
            checker.downloadUpdate(info) { percent -> _download.value = TvUpdateDownload.Running(percent) }
                .onSuccess { file ->
                    _download.value = TvUpdateDownload.Idle
                    _installIntent.tryEmit(checker.buildInstallIntent(file))
                    checker.dismissPendingUpdate()
                }
                .onFailure { error ->
                    Log.w(TAG, "Update download failed: ${error.message}")
                    _download.value = TvUpdateDownload.Failed
                }
        }
    }

    fun dismiss() {
        if (_download.value is TvUpdateDownload.Running) return
        _download.value = TvUpdateDownload.Idle
        checker.dismissPendingUpdate()
    }

    private companion object {
        const val TAG = "TvUpdate"
    }
}
