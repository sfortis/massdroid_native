package net.asksakis.massdroidv2.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
import net.asksakis.massdroidv2.domain.model.SortOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val context: Context
) : SettingsRepository {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_SELECTED_PLAYER = stringPreferencesKey("selected_player_id")
        private val KEY_CLIENT_CERT_ALIAS = stringPreferencesKey("client_cert_alias")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_SENDSPIN_ENABLED = booleanPreferencesKey("sendspin_enabled")
        private val KEY_SMART_LISTENING_ENABLED = booleanPreferencesKey("smart_listening_enabled")
        private val KEY_SMART_MIX_VARIETY = floatPreferencesKey("smart_mix_variety")
        private val KEY_SMART_MIX_DISCOVERY = floatPreferencesKey("smart_mix_discovery")
        private val KEY_SMART_MIX_LENGTH = floatPreferencesKey("smart_mix_length")
        private val KEY_SMART_MIX_STRICTNESS = floatPreferencesKey("smart_mix_strictness")
        private const val DEFAULT_SMART_MIX_VARIETY = 0.5f
        private const val DEFAULT_SMART_MIX_DISCOVERY = 0.5f
        private const val DEFAULT_SMART_MIX_LENGTH = 0.5f
        private const val DEFAULT_SMART_MIX_STRICTNESS = 0.5f
        private val KEY_INCLUDE_BETA_UPDATES = booleanPreferencesKey("include_beta_updates")
        private val KEY_SENDSPIN_CLIENT_ID = stringPreferencesKey("sendspin_client_id")
        private val KEY_LIBRARY_DISPLAY_MODES = stringPreferencesKey("library_display_modes")
        private val KEY_LIBRARY_SORT_OPTIONS = stringPreferencesKey("library_sort_options")
        private val KEY_LIBRARY_SORT_DESC = stringPreferencesKey("library_sort_desc")
        private val KEY_LIBRARY_FAV_ONLY = stringPreferencesKey("library_fav_only")
        private val KEY_LIBRARY_PROVIDER_FILTERS = stringPreferencesKey("library_provider_filters")
        private val KEY_PLAYLIST_SORT_KEY = stringPreferencesKey("playlist_sort_key")
        private val KEY_PLAYLIST_SORT_DESC = booleanPreferencesKey("playlist_sort_desc")
        private val KEY_LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SENDSPIN_AUDIO_FORMAT = stringPreferencesKey("sendspin_audio_format")
        private val KEY_SENDSPIN_SYNC_DELAY_MS = stringPreferencesKey("sendspin_sync_delay_ms")
        private val KEY_SENDSPIN_CLOCK_OFFSET_US = stringPreferencesKey("sendspin_server_minus_wall_us")
        private val KEY_SENDSPIN_SYNC_SYSTEM_VOLUME = stringPreferencesKey("sendspin_sync_system_volume")
        private val KEY_SENDSPIN_COMPRESSOR_LEVEL = stringPreferencesKey("sendspin_compressor_level")
        private val KEY_SENDSPIN_DITHER = stringPreferencesKey("sendspin_dither")
        private val KEY_KNOWN_BT_DEVICES = stringPreferencesKey("known_bt_devices")
        private val KEY_CAR_AUDIO_BT_DEVICES = stringPreferencesKey("car_audio_bt_devices")
        private val KEY_SENDSPIN_LAST_VOLUME = stringPreferencesKey("sendspin_last_volume")
        private val KEY_ACOUSTIC_MIC_PATH_US = stringPreferencesKey("acoustic_mic_path_us")
        private val KEY_ACOUSTIC_ROUTE_CALIBRATIONS = stringPreferencesKey("acoustic_route_calibrations")
    }

    private val safeData = context.dataStore.data
        .catch { emit(emptyPreferences()) }

    override val serverUrl: Flow<String> = safeData.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    override val authToken: Flow<String> = safeData.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    override val selectedPlayerId: Flow<String?> = safeData.map { prefs ->
        prefs[KEY_SELECTED_PLAYER]
    }

    override suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    override suspend fun setAuthToken(token: String) {
        context.dataStore.edit { it[KEY_AUTH_TOKEN] = token }
    }

    override suspend fun setSelectedPlayerId(playerId: String?) {
        context.dataStore.edit {
            if (playerId != null) it[KEY_SELECTED_PLAYER] = playerId
            else it.remove(KEY_SELECTED_PLAYER)
        }
    }

    override val clientCertAlias: Flow<String?> = safeData.map { prefs ->
        prefs[KEY_CLIENT_CERT_ALIAS]
    }

    override suspend fun setClientCertAlias(alias: String?) {
        context.dataStore.edit {
            if (alias != null) it[KEY_CLIENT_CERT_ALIAS] = alias
            else it.remove(KEY_CLIENT_CERT_ALIAS)
        }
    }

    override val username: Flow<String> = safeData.map { prefs ->
        prefs[KEY_USERNAME] ?: ""
    }

    override suspend fun setUsername(username: String) {
        context.dataStore.edit { it[KEY_USERNAME] = username }
    }

    override val password: Flow<String> = safeData.map { prefs ->
        prefs[KEY_PASSWORD] ?: ""
    }

    override suspend fun setPassword(password: String) {
        context.dataStore.edit { it[KEY_PASSWORD] = password }
    }

    override val sendspinEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_ENABLED] ?: true
    }

    override suspend fun setSendspinEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SENDSPIN_ENABLED] = enabled }
    }

    override val smartListeningEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SMART_LISTENING_ENABLED] ?: false
    }

    override suspend fun setSmartListeningEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SMART_LISTENING_ENABLED] = enabled }
    }

    override val smartMixVariety: Flow<Float> = safeData.map { prefs ->
        (prefs[KEY_SMART_MIX_VARIETY] ?: DEFAULT_SMART_MIX_VARIETY).coerceIn(0f, 1f)
    }

    override suspend fun setSmartMixVariety(value: Float) {
        context.dataStore.edit { it[KEY_SMART_MIX_VARIETY] = value.coerceIn(0f, 1f) }
    }

    override val smartMixDiscovery: Flow<Float> = safeData.map { prefs ->
        (prefs[KEY_SMART_MIX_DISCOVERY] ?: DEFAULT_SMART_MIX_DISCOVERY).coerceIn(0f, 1f)
    }

    override suspend fun setSmartMixDiscovery(value: Float) {
        context.dataStore.edit { it[KEY_SMART_MIX_DISCOVERY] = value.coerceIn(0f, 1f) }
    }

    override val smartMixLength: Flow<Float> = safeData.map { prefs ->
        (prefs[KEY_SMART_MIX_LENGTH] ?: DEFAULT_SMART_MIX_LENGTH).coerceIn(0f, 1f)
    }

    override suspend fun setSmartMixLength(value: Float) {
        context.dataStore.edit { it[KEY_SMART_MIX_LENGTH] = value.coerceIn(0f, 1f) }
    }

    override val smartMixStrictness: Flow<Float> = safeData.map { prefs ->
        (prefs[KEY_SMART_MIX_STRICTNESS] ?: DEFAULT_SMART_MIX_STRICTNESS).coerceIn(0f, 1f)
    }

    override suspend fun setSmartMixStrictness(value: Float) {
        context.dataStore.edit { it[KEY_SMART_MIX_STRICTNESS] = value.coerceIn(0f, 1f) }
    }

    override suspend fun resetSmartMixTuning() {
        context.dataStore.edit {
            it.remove(KEY_SMART_MIX_VARIETY)
            it.remove(KEY_SMART_MIX_DISCOVERY)
            it.remove(KEY_SMART_MIX_LENGTH)
            it.remove(KEY_SMART_MIX_STRICTNESS)
        }
    }

    override val includeBetaUpdates: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_INCLUDE_BETA_UPDATES] ?: false
    }

    override suspend fun setIncludeBetaUpdates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INCLUDE_BETA_UPDATES] = enabled }
    }

    override val sendspinClientId: Flow<String?> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_CLIENT_ID]
    }

    override suspend fun setSendspinClientId(clientId: String) {
        context.dataStore.edit { it[KEY_SENDSPIN_CLIENT_ID] = clientId }
    }

    override val libraryDisplayModes: Flow<Map<LibraryTabKey, LibraryDisplayMode>> = safeData.map { prefs ->
        parseNamedStringMap(prefs[KEY_LIBRARY_DISPLAY_MODES]).mapNotNull { (tabKey, mode) ->
            val tab = LibraryTabKey.fromStoredKey(tabKey) ?: return@mapNotNull null
            val parsed = runCatching { LibraryDisplayMode.valueOf(mode) }.getOrNull() ?: return@mapNotNull null
            tab to parsed
        }.toMap()
    }

    override suspend fun setLibraryDisplayMode(tab: LibraryTabKey, mode: LibraryDisplayMode) {
        context.dataStore.edit { prefs ->
            val current = parseNamedStringMap(prefs[KEY_LIBRARY_DISPLAY_MODES]).toMutableMap()
            current[tab.name] = mode.name
            prefs[KEY_LIBRARY_DISPLAY_MODES] = encodeNamedStringMap(current)
        }
    }

    override val librarySortOptions: Flow<Map<Int, SortOption>> = safeData.map { prefs ->
        parseStringMap(prefs[KEY_LIBRARY_SORT_OPTIONS]).mapValues { (_, v) ->
            SortOption.entries.find { it.name == v } ?: SortOption.NAME
        }
    }

    override suspend fun setLibrarySortOption(tab: Int, option: SortOption) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_SORT_OPTIONS]).toMutableMap()
            current[tab] = option.name
            prefs[KEY_LIBRARY_SORT_OPTIONS] = encodeStringMap(current)
        }
    }

    override val librarySortDescending: Flow<Map<Int, Boolean>> = safeData.map { prefs ->
        parseStringMap(prefs[KEY_LIBRARY_SORT_DESC]).mapValues { (_, v) -> v == "true" }
    }

    override suspend fun setLibrarySortDescending(tab: Int, descending: Boolean) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_SORT_DESC]).toMutableMap()
            current[tab] = descending.toString()
            prefs[KEY_LIBRARY_SORT_DESC] = encodeStringMap(current)
        }
    }

    override val lastFmApiKey: Flow<String> = safeData.map { prefs ->
        prefs[KEY_LASTFM_API_KEY] ?: ""
    }

    override suspend fun setLastFmApiKey(key: String) {
        context.dataStore.edit { it[KEY_LASTFM_API_KEY] = key }
    }

    override val themeMode: Flow<String> = safeData.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "auto"
    }

    override suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    override val playlistSortKey: Flow<String> = safeData.map { prefs ->
        prefs[KEY_PLAYLIST_SORT_KEY] ?: "POSITION"
    }

    override suspend fun setPlaylistSortKey(key: String) {
        context.dataStore.edit { it[KEY_PLAYLIST_SORT_KEY] = key }
    }

    override val playlistSortDescending: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_PLAYLIST_SORT_DESC] ?: false
    }

    override suspend fun setPlaylistSortDescending(descending: Boolean) {
        context.dataStore.edit { it[KEY_PLAYLIST_SORT_DESC] = descending }
    }

    override val sendspinAudioFormat: Flow<String> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_AUDIO_FORMAT] ?: "SMART"
    }

    override suspend fun setSendspinAudioFormat(format: String) {
        context.dataStore.edit { it[KEY_SENDSPIN_AUDIO_FORMAT] = format }
    }

    override val sendspinSyncDelayMs: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_SYNC_DELAY_MS]?.toIntOrNull()?.coerceIn(-1000, 1000) ?: 0
    }

    override suspend fun setSendspinSyncDelayMs(delayMs: Int) {
        context.dataStore.edit { it[KEY_SENDSPIN_SYNC_DELAY_MS] = delayMs.coerceIn(-1000, 1000).toString() }
    }

    override val sendspinClockOffsetUs: Flow<Long> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_CLOCK_OFFSET_US]?.toLongOrNull() ?: 0L
    }

    override suspend fun setSendspinClockOffsetUs(offsetUs: Long) {
        context.dataStore.edit { it[KEY_SENDSPIN_CLOCK_OFFSET_US] = offsetUs.toString() }
    }

    override val sendspinSyncSystemVolume: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_SYNC_SYSTEM_VOLUME]?.toBooleanStrictOrNull() ?: true
    }

    override suspend fun setSendspinSyncSystemVolume(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SENDSPIN_SYNC_SYSTEM_VOLUME] = enabled.toString() }
    }

    override val sendspinCompressorLevel: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_COMPRESSOR_LEVEL]?.toIntOrNull()?.coerceIn(0, 3) ?: 0
    }

    override suspend fun setSendspinCompressorLevel(level: Int) {
        context.dataStore.edit { it[KEY_SENDSPIN_COMPRESSOR_LEVEL] = level.coerceIn(0, 3).toString() }
    }

    override val sendspinDither: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_DITHER]?.toBooleanStrictOrNull() ?: false
    }

    override suspend fun setSendspinDither(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SENDSPIN_DITHER] = enabled.toString() }
    }

    override val knownBtDevices: Flow<Set<String>> = safeData.map { prefs ->
        prefs[KEY_KNOWN_BT_DEVICES]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    override suspend fun recordKnownBtDevice(routeKey: String) {
        if (routeKey.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_KNOWN_BT_DEVICES]?.split("\n")?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (current.add(routeKey)) prefs[KEY_KNOWN_BT_DEVICES] = current.joinToString("\n")
        }
    }

    override val carAudioBtDevices: Flow<Set<String>> = safeData.map { prefs ->
        prefs[KEY_CAR_AUDIO_BT_DEVICES]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    override suspend fun setCarAudioBtDevice(routeKey: String, enabled: Boolean) {
        if (routeKey.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CAR_AUDIO_BT_DEVICES]?.split("\n")?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            val changed = if (enabled) current.add(routeKey) else current.remove(routeKey)
            if (changed) prefs[KEY_CAR_AUDIO_BT_DEVICES] = current.joinToString("\n")
        }
    }

    override val sendspinLastVolume: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_LAST_VOLUME]?.toIntOrNull()?.coerceIn(0, 100) ?: 100
    }

    override suspend fun setSendspinLastVolume(volume: Int) {
        context.dataStore.edit { it[KEY_SENDSPIN_LAST_VOLUME] = volume.coerceIn(0, 100).toString() }
    }

    override val acousticMicPathUs: Flow<Long> = safeData.map { prefs ->
        prefs[KEY_ACOUSTIC_MIC_PATH_US]?.toLongOrNull() ?: 0L
    }

    override suspend fun setAcousticMicPathUs(valueUs: Long) {
        context.dataStore.edit { it[KEY_ACOUSTIC_MIC_PATH_US] = valueUs.coerceAtLeast(0L).toString() }
    }

    override val acousticRouteCalibrations: Flow<Map<String, AcousticRouteCalibration>> = safeData.map { prefs ->
        val raw = prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS] ?: return@map emptyMap()
        try {
            Json.decodeFromString(calibrationSerializer, raw)
        } catch (_: Exception) { emptyMap() }
    }

    private val calibrationSerializer = MapSerializer(String.serializer(), AcousticRouteCalibration.serializer())

    private fun encodeCalibrations(map: Map<String, AcousticRouteCalibration>): String =
        Json.encodeToString(calibrationSerializer, map)

    override suspend fun setAcousticRouteCalibration(routeKey: String, calibration: AcousticRouteCalibration) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS]?.let { raw ->
                try {
                    Json.decodeFromString(calibrationSerializer, raw).toMutableMap()
                } catch (_: Exception) { mutableMapOf() }
            } ?: mutableMapOf()
            current[routeKey] = calibration
            prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS] = encodeCalibrations(current)
        }
    }

    override suspend fun removeAcousticRouteCalibration(routeKey: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS]?.let { raw ->
                try {
                    Json.decodeFromString(calibrationSerializer, raw).toMutableMap()
                } catch (_: Exception) { mutableMapOf() }
            } ?: return@edit
            current.remove(routeKey)
            prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS] = encodeCalibrations(current)
        }
    }

    override val libraryFavoritesOnly: Flow<Map<Int, Boolean>> = safeData.map { prefs ->
        parseStringMap(prefs[KEY_LIBRARY_FAV_ONLY]).mapValues { (_, v) -> v == "true" }
    }

    override suspend fun setLibraryFavoritesOnly(tab: Int, favoritesOnly: Boolean) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_FAV_ONLY]).toMutableMap()
            current[tab] = favoritesOnly.toString()
            prefs[KEY_LIBRARY_FAV_ONLY] = encodeStringMap(current)
        }
    }

    // Provider filters per tab: value is the selected instance ids joined by '|' (instance ids never
    // contain ',', ':' or '|', so this nests safely inside the shared tab-keyed string-map encoding).
    override val libraryProviderFilters: Flow<Map<Int, Set<String>>> = safeData.map { prefs ->
        parseStringMap(prefs[KEY_LIBRARY_PROVIDER_FILTERS]).mapValues { (_, v) ->
            v.split("|").filter { it.isNotBlank() }.toSet()
        }
    }

    override suspend fun setLibraryProviderFilters(tab: Int, instanceIds: Set<String>) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_PROVIDER_FILTERS]).toMutableMap()
            if (instanceIds.isEmpty()) current.remove(tab) else current[tab] = instanceIds.joinToString("|")
            prefs[KEY_LIBRARY_PROVIDER_FILTERS] = encodeStringMap(current)
        }
    }

    private fun parseStringMap(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val key = parts[0].toIntOrNull() ?: return@mapNotNull null
                key to parts[1]
            }
            .toMap()
    }

    private fun parseNamedStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0] to parts[1]
            }
            .toMap()
    }

    private fun encodeStringMap(map: Map<Int, String>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun encodeNamedStringMap(map: Map<String, String>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }
}
