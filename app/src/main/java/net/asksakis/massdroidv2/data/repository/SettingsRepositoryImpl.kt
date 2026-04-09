package net.asksakis.massdroidv2.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
import net.asksakis.massdroidv2.domain.model.SortOption
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
        private val KEY_INCLUDE_BETA_UPDATES = booleanPreferencesKey("include_beta_updates")
        private val KEY_SENDSPIN_CLIENT_ID = stringPreferencesKey("sendspin_client_id")
        private val KEY_LIBRARY_DISPLAY_MODES = stringPreferencesKey("library_display_modes")
        private val KEY_LIBRARY_SORT_OPTIONS = stringPreferencesKey("library_sort_options")
        private val KEY_LIBRARY_SORT_DESC = stringPreferencesKey("library_sort_desc")
        private val KEY_LIBRARY_FAV_ONLY = stringPreferencesKey("library_fav_only")
        private val KEY_LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SENDSPIN_AUDIO_FORMAT = stringPreferencesKey("sendspin_audio_format")
        private val KEY_SENDSPIN_STATIC_DELAY_MS = stringPreferencesKey("sendspin_static_delay_ms")
        private val KEY_SENDSPIN_OUTPUT_LATENCY_US = stringPreferencesKey("sendspin_output_latency_us")
        private val KEY_SENDSPIN_CLOCK_OFFSET_US = stringPreferencesKey("sendspin_server_minus_wall_us")
        private val KEY_SENDSPIN_DEVICE_BIAS_US = stringPreferencesKey("sendspin_device_bias_us")
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

    override val sendspinAudioFormat: Flow<String> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_AUDIO_FORMAT] ?: "SMART"
    }

    override suspend fun setSendspinAudioFormat(format: String) {
        context.dataStore.edit { it[KEY_SENDSPIN_AUDIO_FORMAT] = format }
    }

    override val sendspinStaticDelayMs: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_STATIC_DELAY_MS]?.toIntOrNull()?.coerceIn(0, 5000) ?: 0
    }

    override suspend fun setSendspinStaticDelayMs(delayMs: Int) {
        context.dataStore.edit { it[KEY_SENDSPIN_STATIC_DELAY_MS] = delayMs.coerceIn(0, 5000).toString() }
    }

    override val sendspinOutputLatencyUs: Flow<Long> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_OUTPUT_LATENCY_US]?.toLongOrNull()?.coerceIn(0, 500_000) ?: 0L
    }

    override suspend fun setSendspinOutputLatencyUs(latencyUs: Long) {
        context.dataStore.edit { it[KEY_SENDSPIN_OUTPUT_LATENCY_US] = latencyUs.coerceIn(0, 500_000).toString() }
    }

    override val sendspinClockOffsetUs: Flow<Long> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_CLOCK_OFFSET_US]?.toLongOrNull() ?: 0L
    }

    override suspend fun setSendspinClockOffsetUs(offsetUs: Long) {
        context.dataStore.edit { it[KEY_SENDSPIN_CLOCK_OFFSET_US] = offsetUs.toString() }
    }

    override val sendspinDeviceBiasUs: Flow<Long> = safeData.map { prefs ->
        prefs[KEY_SENDSPIN_DEVICE_BIAS_US]?.toLongOrNull() ?: 0L
    }

    override suspend fun setSendspinDeviceBiasUs(biasUs: Long) {
        context.dataStore.edit { it[KEY_SENDSPIN_DEVICE_BIAS_US] = biasUs.toString() }
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
