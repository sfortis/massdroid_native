package net.asksakis.massdroidv2.data.proximity

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProximityConfig"
private const val CONFIG_FILE = "proximity_config.json"

@Singleton
class ProximityConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val file: File get() = File(context.filesDir, CONFIG_FILE)
    private val _config = MutableStateFlow(ProximityConfig())
    val config: StateFlow<ProximityConfig> = _config.asStateFlow()


    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            val text = file.readText()
            _config.value = json.decodeFromString<ProximityConfig>(text)
        } catch (e: Exception) {
            Log.d(TAG, "No config or corrupt: ${e.message}")
            _config.value = ProximityConfig()
        }
    }

    suspend fun save(config: ProximityConfig) = withContext(Dispatchers.IO) {
        try {
            file.writeText(json.encodeToString(ProximityConfig.serializer(), config))
            _config.value = config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config: ${e.message}")
        }
    }

    suspend fun update(transform: (ProximityConfig) -> ProximityConfig) {
        save(transform(_config.value))
    }
}
