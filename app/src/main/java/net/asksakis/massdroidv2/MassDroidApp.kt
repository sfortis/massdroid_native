package net.asksakis.massdroidv2

import android.app.Application
import android.content.Intent
import android.security.KeyChain
import android.util.Log
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.service.SendspinService
import javax.inject.Inject

@HiltAndroidApp
class MassDroidApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var wsClient: MaWebSocketClient

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var playHistoryRepository: PlayHistoryRepository

    @Inject
    lateinit var appUpdateChecker: AppUpdateChecker

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendspinServiceStarted = false

    override fun onCreate() {
        super.onCreate()
        // Load saved mTLS certificate and credentials at startup
        appScope.launch {
            val alias = settingsRepository.clientCertAlias.first()
            if (alias != null) {
                try {
                    val privateKey = KeyChain.getPrivateKey(this@MassDroidApp, alias)
                    val certChain = KeyChain.getCertificateChain(this@MassDroidApp, alias)
                    if (privateKey != null && certChain != null) {
                        wsClient.configureMtls(privateKey, certChain)
                        Log.d("MassDroidApp", "mTLS loaded on startup: $alias")
                    }
                } catch (e: Exception) {
                    Log.e("MassDroidApp", "Failed to load mTLS cert: ${e.message}")
                }
            }
            // Load saved credentials for token-fallback
            val username = settingsRepository.username.first()
            val password = settingsRepository.password.first()
            if (username.isNotBlank() && password.isNotBlank()) {
                wsClient.setSavedCredentials(username, password)
                Log.d("MassDroidApp", "Saved credentials loaded for user: $username")
            }
            wsClient.markStartupReady()
        }

        // Clean up old play history entries
        appScope.launch {
            try {
                playHistoryRepository.cleanup(retentionMonths = 6)
            } catch (e: Exception) {
                Log.e("MassDroidApp", "Play history cleanup failed: ${e.message}")
            }
        }

        appScope.launch {
            try {
                val includeBeta = settingsRepository.includeBetaUpdates.first()
                appUpdateChecker.checkForUpdates(force = false, includePrerelease = includeBeta)
            } catch (e: Exception) {
                Log.d("MassDroidApp", "Background update check skipped: ${e.message}")
            }
        }

        // Observe connection state: save token on connect, clear on auth failure, auto-start Sendspin
        appScope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        // Save fresh token to DataStore before anything reads it
                        wsClient.authToken?.let { token ->
                            settingsRepository.setAuthToken(token)
                            Log.d("MassDroidApp", "Token saved to DataStore")
                        }
                        val sendspinOn = settingsRepository.sendspinEnabled.first()
                        if (sendspinOn && !sendspinServiceStarted) {
                            sendspinServiceStarted = true
                            val intent = Intent(this@MassDroidApp, SendspinService::class.java).apply {
                                action = SendspinService.ACTION_START
                            }
                            ContextCompat.startForegroundService(this@MassDroidApp, intent)
                            Log.d("MassDroidApp", "Sendspin service auto-started after WS connect")
                        }
                    }
                    is ConnectionState.Error -> {
                        // If token was rejected (cleared by WS client), clear from DataStore too
                        if (wsClient.authToken == null) {
                            settingsRepository.setAuthToken("")
                            Log.d("MassDroidApp", "Invalid token cleared from DataStore")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { wsClient.getHttpClient() }
            .crossfade(true)
            .build()
    }
}
