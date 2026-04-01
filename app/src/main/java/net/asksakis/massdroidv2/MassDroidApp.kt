package net.asksakis.massdroidv2

import android.app.Application
import android.security.KeyChain
import android.util.Log
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

    @Inject
    lateinit var providerManifestCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache

    @Inject
    lateinit var json: kotlinx.serialization.json.Json

    @Inject
    lateinit var lastFmLibraryEnricher: net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val result = appUpdateChecker.checkForUpdates(force = false, includePrerelease = includeBeta)
                if (result is net.asksakis.massdroidv2.data.update.AppUpdateChecker.CheckResult.UpdateAvailable) {
                    Log.d("MassDroidApp", "Update available: ${result.info.version}")
                }
            } catch (e: Exception) {
                Log.d("MassDroidApp", "Background update check skipped: ${e.message}")
            }
        }

        // Connect to PlaybackService for media notification (required for MIUI/vendor ROMs
        // that block late service binding)
        connectPlaybackService()

        // Observe connection state: save token on connect, clear on auth failure
        appScope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        // Save fresh token to DataStore before anything reads it
                        wsClient.authToken?.let { token ->
                            settingsRepository.setAuthToken(token)
                            Log.d("MassDroidApp", "Token saved to DataStore")
                        }
                        providerManifestCache.fetchManifests(wsClient, json)
                        lastFmLibraryEnricher.enrichAllUnenriched()
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

    private fun connectPlaybackService() {
        appScope.launch(Dispatchers.Main) {
            try {
                val sessionToken = androidx.media3.session.SessionToken(
                    this@MassDroidApp,
                    android.content.ComponentName(this@MassDroidApp, net.asksakis.massdroidv2.service.PlaybackService::class.java)
                )
                androidx.media3.session.MediaController.Builder(this@MassDroidApp, sessionToken)
                    .buildAsync()
                Log.d("MassDroidApp", "PlaybackService controller connected")
            } catch (e: Exception) {
                Log.e("MassDroidApp", "PlaybackService connect failed: ${e.message}")
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { wsClient.getHttpClient() }
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}
