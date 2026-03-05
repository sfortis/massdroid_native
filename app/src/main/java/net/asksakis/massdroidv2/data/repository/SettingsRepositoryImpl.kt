package net.asksakis.massdroidv2.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
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
        private val KEY_SENDSPIN_CLIENT_ID = stringPreferencesKey("sendspin_client_id")
        private val KEY_LIBRARY_DISPLAY_MODES = stringPreferencesKey("library_display_modes")
        private val KEY_LIBRARY_SORT_OPTIONS = stringPreferencesKey("library_sort_options")
        private val KEY_LIBRARY_SORT_DESC = stringPreferencesKey("library_sort_desc")
        private val KEY_LIBRARY_FAV_ONLY = stringPreferencesKey("library_fav_only")
    }

    override val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    override val authToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    override val selectedPlayerId: Flow<String?> = context.dataStore.data.map { prefs ->
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

    override val clientCertAlias: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLIENT_CERT_ALIAS]
    }

    override suspend fun setClientCertAlias(alias: String?) {
        context.dataStore.edit {
            if (alias != null) it[KEY_CLIENT_CERT_ALIAS] = alias
            else it.remove(KEY_CLIENT_CERT_ALIAS)
        }
    }

    override val username: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USERNAME] ?: ""
    }

    override suspend fun setUsername(username: String) {
        context.dataStore.edit { it[KEY_USERNAME] = username }
    }

    override val password: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PASSWORD] ?: ""
    }

    override suspend fun setPassword(password: String) {
        context.dataStore.edit { it[KEY_PASSWORD] = password }
    }

    override val sendspinEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SENDSPIN_ENABLED] ?: true
    }

    override suspend fun setSendspinEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SENDSPIN_ENABLED] = enabled }
    }

    override val smartListeningEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SMART_LISTENING_ENABLED] ?: false
    }

    override suspend fun setSmartListeningEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SMART_LISTENING_ENABLED] = enabled }
    }

    override val sendspinClientId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SENDSPIN_CLIENT_ID]
    }

    override suspend fun setSendspinClientId(clientId: String) {
        context.dataStore.edit { it[KEY_SENDSPIN_CLIENT_ID] = clientId }
    }

    override val libraryDisplayModes: Flow<Map<Int, LibraryDisplayMode>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_LIBRARY_DISPLAY_MODES] ?: return@map emptyMap()
        raw.split(",").associate { entry ->
            val (tab, mode) = entry.split(":")
            tab.toInt() to LibraryDisplayMode.valueOf(mode)
        }
    }

    override suspend fun setLibraryDisplayMode(tab: Int, mode: LibraryDisplayMode) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_DISPLAY_MODES]).toMutableMap()
            current[tab] = mode.name
            prefs[KEY_LIBRARY_DISPLAY_MODES] = encodeStringMap(current)
        }
    }

    override val librarySortOptions: Flow<Map<Int, SortOption>> = context.dataStore.data.map { prefs ->
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

    override val librarySortDescending: Flow<Map<Int, Boolean>> = context.dataStore.data.map { prefs ->
        parseStringMap(prefs[KEY_LIBRARY_SORT_DESC]).mapValues { (_, v) -> v == "true" }
    }

    override suspend fun setLibrarySortDescending(tab: Int, descending: Boolean) {
        context.dataStore.edit { prefs ->
            val current = parseStringMap(prefs[KEY_LIBRARY_SORT_DESC]).toMutableMap()
            current[tab] = descending.toString()
            prefs[KEY_LIBRARY_SORT_DESC] = encodeStringMap(current)
        }
    }

    override val libraryFavoritesOnly: Flow<Map<Int, Boolean>> = context.dataStore.data.map { prefs ->
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
        return raw.split(",").filter { it.contains(":") }.associate { entry ->
            val (k, v) = entry.split(":", limit = 2)
            k.toInt() to v
        }
    }

    private fun encodeStringMap(map: Map<Int, String>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }
}
