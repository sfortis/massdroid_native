package net.asksakis.massdroidv2.data.repository

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.asksakis.massdroidv2.domain.connection.normalizedAddress
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.model.ServerAccount
import net.asksakis.massdroidv2.domain.model.SortOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.asksakis.massdroidv2.domain.repository.AcousticRouteCalibration
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listener's settings. Android backup and device transfer copy this file (see
 * `res/xml/backup_rules.xml`), so a restored phone keeps its server and preferences.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * What must stay on this device: the MA credentials, and values that belong to this phone
 * alone (its Sendspin identity and pairing state, its clock offset, its output calibration).
 * Backup leaves this file out, so a restore never hands a working password to another device
 * nor makes a second phone show up as the first one's player. The keys used to live in
 * [dataStore]; [MoveDeviceKeysMigration] moves them on first access.
 */
private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "device",
    produceMigrations = { context -> listOf(MoveDeviceKeysMigration(context.dataStore)) },
)

private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
private val KEY_TRUSTED_SERVER_ID = stringPreferencesKey("trusted_server_id")
private val KEY_CLIENT_CERT_ALIAS = stringPreferencesKey("client_cert_alias")
private val KEY_USERNAME = stringPreferencesKey("username")
private val KEY_PASSWORD = stringPreferencesKey("password")

// One token and one username per Music Assistant server, named by its server_id.
private fun serverTokenKey(serverId: String) = stringPreferencesKey("auth_token@$serverId")
private fun serverUsernameKey(serverId: String) = stringPreferencesKey("username@$serverId")
private val KEY_SERVER_ACCOUNTS_MIGRATED = booleanPreferencesKey("server_accounts_migrated")
private val KEY_SERVER_ADDRESSES_MIGRATED = booleanPreferencesKey("server_addresses_migrated")
// Every address the phone signed in through, named by the address: the server_id it leads to
// and when it was last used. One server can have several, such as its home and remote address.
private const val ADDRESS_PREFIX = "addr@"
private fun addressKey(address: String) = stringPreferencesKey("$ADDRESS_PREFIX$address")
private fun addressUsedAtKey(address: String) = longPreferencesKey("addr_used_at@$address")
// The one address per server that builds before 2026-11-02 kept; moved to the keys above once.
private const val LEGACY_SERVER_URL_PREFIX = "url@"
private fun legacyServerUrlKey(serverId: String) = stringPreferencesKey("$LEGACY_SERVER_URL_PREFIX$serverId")
private fun legacyServerUsedAtKey(serverId: String) = longPreferencesKey("used_at@$serverId")
private val KEY_SENDSPIN_CLIENT_ID = stringPreferencesKey("sendspin_client_id")
private val KEY_SENDSPIN_LEGACY_CLIENT_ID = stringPreferencesKey("sendspin_legacy_client_id")
private val KEY_SENDSPIN_MIGRATED_SERVERS = stringSetPreferencesKey("sendspin_migrated_servers")
private val KEY_SENDSPIN_UNPAIRED_SERVERS = stringSetPreferencesKey("sendspin_unpaired_servers")
private val KEY_SENDSPIN_SYNC_DELAY_MS = stringPreferencesKey("sendspin_sync_delay_ms")
private val KEY_SENDSPIN_CLOCK_OFFSET_US = stringPreferencesKey("sendspin_server_minus_wall_us")
private val KEY_LEGACY_ACOUSTIC_MIC_PATH_US = stringPreferencesKey("acoustic_mic_path_us")
private val KEY_LEGACY_ACOUSTIC_PHONE_BASELINE_US = stringPreferencesKey("acoustic_phone_baseline_us")
private val KEY_ACOUSTIC_ROUTE_CALIBRATIONS = stringPreferencesKey("acoustic_route_calibrations")
private val KEY_OUTPUT_FINE_TUNE_MS = stringPreferencesKey("output_fine_tune_ms")
private val KEY_LEGACY_SYNC_DELAY_RESET = booleanPreferencesKey("legacy_sync_delay_reset")

internal val DEVICE_KEYS: List<Preferences.Key<*>> = listOf(
    KEY_AUTH_TOKEN, KEY_TRUSTED_SERVER_ID, KEY_CLIENT_CERT_ALIAS, KEY_USERNAME, KEY_PASSWORD,
    KEY_SENDSPIN_CLIENT_ID, KEY_SENDSPIN_LEGACY_CLIENT_ID, KEY_SENDSPIN_MIGRATED_SERVERS,
    KEY_SENDSPIN_UNPAIRED_SERVERS, KEY_SENDSPIN_SYNC_DELAY_MS, KEY_SENDSPIN_CLOCK_OFFSET_US,
    KEY_LEGACY_ACOUSTIC_MIC_PATH_US, KEY_LEGACY_ACOUSTIC_PHONE_BASELINE_US,
    KEY_ACOUSTIC_ROUTE_CALIBRATIONS, KEY_OUTPUT_FINE_TUNE_MS, KEY_LEGACY_SYNC_DELAY_RESET,
)
private val KEY_DEVICE_KEYS_MOVED = booleanPreferencesKey("device_keys_moved")

/**
 * Moves [DEVICE_KEYS] from the settings file into the device file once, then deletes them from
 * the settings file. Runs before the device file's first read, so no reader sees it half done.
 */
internal class MoveDeviceKeysMigration(
    private val settings: DataStore<Preferences>,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[KEY_DEVICE_KEYS_MOVED] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val stored = settings.data.first()
        val moved = currentData.toMutablePreferences()
        for (key in DEVICE_KEYS) copyKey(stored, moved, key)
        moved[KEY_DEVICE_KEYS_MOVED] = true
        return moved.toPreferences()
    }

    override suspend fun cleanUp() {
        settings.edit { prefs -> DEVICE_KEYS.forEach { prefs.remove(it) } }
    }

    private fun <T> copyKey(from: Preferences, to: MutablePreferences, key: Preferences.Key<T>) {
        val value = from[key] ?: return
        if (to[key] == null) to[key] = value
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val context: Context
) : SettingsRepository {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SELECTED_PLAYER = stringPreferencesKey("selected_player_id")
        private val KEY_SENDSPIN_ENABLED = booleanPreferencesKey("sendspin_enabled")
        private val KEY_BLOCKED_ALIAS_PROVIDERS = stringPreferencesKey("blocked_artist_alias_providers")
        private val KEY_SMART_LISTENING_ENABLED = booleanPreferencesKey("smart_listening_enabled")
        private val KEY_SEARCH_GRID_MODE = booleanPreferencesKey("search_grid_mode")
        private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        private val KEY_MB_IDENTITY_REVISION = intPreferencesKey("mb_identity_revision")
        private val KEY_LAST_SEEN_WHATS_NEW = intPreferencesKey("last_seen_whats_new")
        private val KEY_SMART_MIX_VARIETY = floatPreferencesKey("smart_mix_variety")
        private val KEY_SMART_MIX_DISCOVERY = floatPreferencesKey("smart_mix_discovery")
        private val KEY_SMART_MIX_LENGTH = floatPreferencesKey("smart_mix_length")
        private val KEY_SMART_MIX_STRICTNESS = floatPreferencesKey("smart_mix_strictness")
        private const val DEFAULT_SMART_MIX_VARIETY = 0.5f
        private const val DEFAULT_SMART_MIX_DISCOVERY = 0.5f
        private const val DEFAULT_SMART_MIX_LENGTH = 0.5f
        private const val DEFAULT_SMART_MIX_STRICTNESS = 0.5f
        private val KEY_INCLUDE_BETA_UPDATES = booleanPreferencesKey("include_beta_updates")
        private val KEY_PERSISTENT_LOG_ENABLED = booleanPreferencesKey("persistent_log_enabled")
        private val KEY_SHOW_ABOUT_TEXT = booleanPreferencesKey("show_about_text")
        private val KEY_LIBRARY_DISPLAY_MODES = stringPreferencesKey("library_display_modes")
        private val KEY_LIBRARY_SORT_OPTIONS = stringPreferencesKey("library_sort_options")
        private val KEY_LIBRARY_SORT_DESC = stringPreferencesKey("library_sort_desc")
        private val KEY_LIBRARY_FAV_ONLY = stringPreferencesKey("library_fav_only")
        private val KEY_LIBRARY_PROVIDER_FILTERS = stringPreferencesKey("library_provider_filters")
        private val KEY_LIBRARY_ALBUM_ARTISTS_ONLY = booleanPreferencesKey("library_album_artists_only")
        private val KEY_LIBRARY_ALBUM_TYPES = stringPreferencesKey("library_album_types")
        private val KEY_PLAYLIST_SORT_KEY = stringPreferencesKey("playlist_sort_key")
        private val KEY_PLAYLIST_SORT_DESC = booleanPreferencesKey("playlist_sort_desc")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SENDSPIN_AUDIO_FORMAT = stringPreferencesKey("sendspin_audio_format")
        private val KEY_SENDSPIN_SYNC_SYSTEM_VOLUME = stringPreferencesKey("sendspin_sync_system_volume")
        private val KEY_SENDSPIN_COMPRESSOR_LEVEL = stringPreferencesKey("sendspin_compressor_level")
        private val KEY_SENDSPIN_DITHER = stringPreferencesKey("sendspin_dither")
        private val KEY_KNOWN_BT_DEVICES = stringPreferencesKey("known_bt_devices")
        private val KEY_CAR_AUDIO_BT_DEVICES = stringPreferencesKey("car_audio_bt_devices")
        private val KEY_PINNED_PLAYLISTS = stringPreferencesKey("pinned_playlists")
        private val KEY_SENDSPIN_LAST_VOLUME = stringPreferencesKey("sendspin_last_volume")
        private val KEY_RECENT_SEED_CLUSTERS = stringPreferencesKey("recent_seed_cluster_genres")
        private val KEY_RECENT_MIX_TRACKS = stringPreferencesKey("recent_mix_track_uris")
        private val KEY_RECENT_MIX_ARTISTS = stringPreferencesKey("recent_mix_artist_keys")
        private val KEY_RECENT_MIX_GENRES = stringPreferencesKey("recent_mix_genres")

        // One cluster per line, its genres comma-separated. Genres never contain
        // either character (genres are words: "drum and bass", "hip hop")
        // and the DB already round-trips them through comma-joined lists.
        private const val CLUSTER_SEPARATOR = "\n"
        private const val GENRE_SEPARATOR = ","
    }

    private val safeData = context.dataStore.data
        .catch { emit(emptyPreferences()) }

    private val deviceData = context.deviceDataStore.data
        .catch { emit(emptyPreferences()) }

    override val serverUrl: Flow<String> = safeData.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    override val authToken: Flow<String> = deviceData.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    override val selectedPlayerId: Flow<String?> = safeData.map { prefs ->
        prefs[KEY_SELECTED_PLAYER]
    }

    override val trustedServerId: Flow<String?> = deviceData.map { prefs ->
        prefs[KEY_TRUSTED_SERVER_ID]
    }

    override suspend fun setTrustedServerId(serverId: String?) {
        context.deviceDataStore.edit {
            if (serverId != null) it[KEY_TRUSTED_SERVER_ID] = serverId
            else it.remove(KEY_TRUSTED_SERVER_ID)
        }
    }

    override suspend fun setTrustedServerIdIf(serverId: String, stillCurrent: () -> Boolean) {
        context.deviceDataStore.edit { if (stillCurrent()) it[KEY_TRUSTED_SERVER_ID] = serverId }
    }

    override suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    override suspend fun setAuthToken(token: String) {
        context.deviceDataStore.edit { it[KEY_AUTH_TOKEN] = token }
    }

    override suspend fun setSelectedPlayerId(playerId: String?) {
        context.dataStore.edit {
            if (playerId != null) it[KEY_SELECTED_PLAYER] = playerId
            else it.remove(KEY_SELECTED_PLAYER)
        }
    }

    override val clientCertAlias: Flow<String?> = deviceData.map { prefs ->
        prefs[KEY_CLIENT_CERT_ALIAS]
    }

    override suspend fun setClientCertAlias(alias: String?) {
        context.deviceDataStore.edit {
            if (alias != null) it[KEY_CLIENT_CERT_ALIAS] = alias
            else it.remove(KEY_CLIENT_CERT_ALIAS)
        }
    }

    override val username: Flow<String> = deviceData.map { prefs ->
        prefs[KEY_USERNAME] ?: ""
    }

    override suspend fun setUsername(username: String) {
        context.deviceDataStore.edit { it[KEY_USERNAME] = username }
    }

    override val password: Flow<String> = deviceData.map { prefs ->
        prefs[KEY_PASSWORD] ?: ""
    }

    override suspend fun setPassword(password: String) {
        context.deviceDataStore.edit { it[KEY_PASSWORD] = password }
    }

    override fun serverAccount(serverId: String): Flow<ServerAccount> = deviceData.map { prefs ->
        ServerAccount(
            token = prefs[serverTokenKey(serverId)].orEmpty(),
            username = prefs[serverUsernameKey(serverId)].orEmpty(),
        )
    }.distinctUntilChanged()

    override suspend fun saveSignIn(serverId: String, url: String, token: String, username: String, password: String) {
        context.deviceDataStore.edit {
            it[KEY_AUTH_TOKEN] = token
            it[KEY_USERNAME] = username
            it[KEY_PASSWORD] = password
            it[KEY_TRUSTED_SERVER_ID] = serverId
            it.putServerEntry(serverId, url, token, username)
        }
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    override suspend fun saveConnectionToken(serverId: String, url: String, token: String) {
        context.deviceDataStore.edit {
            it[KEY_AUTH_TOKEN] = token
            it.putServerEntry(serverId, url, token, it[KEY_USERNAME].orEmpty())
        }
    }

    override suspend fun forgetRejectedToken(serverId: String?, token: String) {
        context.deviceDataStore.edit { it.forgetRejectedToken(serverId, token) }
    }

    override suspend fun forgetServerToken(serverId: String) {
        context.deviceDataStore.edit { it.remove(serverTokenKey(serverId)) }
    }

    override suspend fun migrateToServerAccounts() {
        val url = serverUrl.first()
        context.deviceDataStore.edit { it.migrateToServerAccounts(url) }
    }

    override suspend fun forgetServerAt(url: String, serverId: String?, savedUrl: String) {
        context.deviceDataStore.edit { it.forgetServerAt(url, serverId, savedUrl) }
    }

    override val usedServerUrls: Flow<List<String>> = deviceData.map { it.usedAddresses() }.distinctUntilChanged()

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

    override val searchGridMode: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SEARCH_GRID_MODE] ?: true
    }

    override suspend fun setSearchGridMode(grid: Boolean) {
        context.dataStore.edit { it[KEY_SEARCH_GRID_MODE] = grid }
    }

    override val recentSearches: Flow<List<String>> = safeData.map { prefs ->
        prefs[KEY_RECENT_SEARCHES].toRecentSearches()
    }

    override suspend fun addRecentSearch(query: String, replacing: String?) {
        if (normalizeSearchQuery(query).isBlank()) return
        context.dataStore.edit { prefs ->
            val merged = mergeRecentSearch(
                prefs[KEY_RECENT_SEARCHES].toRecentSearches(), query, replacing
            )
            prefs[KEY_RECENT_SEARCHES] = merged.joinToString("\n")
        }
    }

    override suspend fun removeRecentSearch(query: String) {
        val entry = normalizeSearchQuery(query)
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_SEARCHES].toRecentSearches()
            val remaining = current.filterNot { it.equals(entry, ignoreCase = true) }
            if (remaining.size != current.size) {
                prefs[KEY_RECENT_SEARCHES] = remaining.joinToString("\n")
            }
        }
    }

    override suspend fun clearRecentSearches() {
        context.dataStore.edit { it.remove(KEY_RECENT_SEARCHES) }
    }

    private fun String?.toRecentSearches(): List<String> =
        this?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    override val blockedArtistAliasProviders: Flow<String> = safeData.map { prefs ->
        prefs[KEY_BLOCKED_ALIAS_PROVIDERS].orEmpty()
    }

    override suspend fun setBlockedArtistAliasProviders(fingerprint: String) {
        context.dataStore.edit { it[KEY_BLOCKED_ALIAS_PROVIDERS] = fingerprint }
    }

    override val musicBrainzIdentityRevision: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_MB_IDENTITY_REVISION] ?: 0
    }

    override suspend fun setMusicBrainzIdentityRevision(revision: Int) {
        context.dataStore.edit { it[KEY_MB_IDENTITY_REVISION] = revision }
    }

    override val lastSeenWhatsNewVersion: Flow<Int> = safeData.map { prefs ->
        prefs[KEY_LAST_SEEN_WHATS_NEW] ?: 0
    }

    override suspend fun setLastSeenWhatsNewVersion(versionCode: Int) {
        context.dataStore.edit { it[KEY_LAST_SEEN_WHATS_NEW] = versionCode }
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

    override val showAboutText: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_SHOW_ABOUT_TEXT] ?: true
    }

    override suspend fun setShowAboutText(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_ABOUT_TEXT] = enabled }
    }

    override val persistentLogEnabled: Flow<Boolean?> = safeData.map { prefs ->
        prefs[KEY_PERSISTENT_LOG_ENABLED]
    }

    override suspend fun setPersistentLogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PERSISTENT_LOG_ENABLED] = enabled }
    }

    override val sendspinClientId: Flow<String?> = deviceData.map { prefs ->
        prefs[KEY_SENDSPIN_CLIENT_ID]
    }

    override suspend fun setSendspinClientId(clientId: String) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_CLIENT_ID] = clientId }
    }

    override val sendspinLegacyClientId: Flow<String?> = deviceData.map { prefs ->
        prefs[KEY_SENDSPIN_LEGACY_CLIENT_ID]
    }

    override suspend fun setSendspinLegacyClientId(playerId: String) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_LEGACY_CLIENT_ID] = playerId }
    }

    override val sendspinMigratedServers: Flow<Set<String>> = deviceData.map { prefs ->
        prefs[KEY_SENDSPIN_MIGRATED_SERVERS].orEmpty()
    }

    override suspend fun addSendspinMigratedServer(serverId: String) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_MIGRATED_SERVERS] = it[KEY_SENDSPIN_MIGRATED_SERVERS].orEmpty() + serverId }
    }

    override val sendspinUnpairedServers: Flow<Set<String>> = deviceData.map { prefs ->
        prefs[KEY_SENDSPIN_UNPAIRED_SERVERS].orEmpty()
    }

    override suspend fun addSendspinUnpairedServer(serverId: String) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_UNPAIRED_SERVERS] = it[KEY_SENDSPIN_UNPAIRED_SERVERS].orEmpty() + serverId }
    }

    override suspend fun removeSendspinUnpairedServer(serverId: String) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_UNPAIRED_SERVERS] = it[KEY_SENDSPIN_UNPAIRED_SERVERS].orEmpty() - serverId }
    }

    override suspend fun clearSendspinUnpairedServers() {
        context.deviceDataStore.edit { it.remove(KEY_SENDSPIN_UNPAIRED_SERVERS) }
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
        prefs[KEY_SENDSPIN_AUDIO_FORMAT] ?: SendspinAudioFormat.AUTOMATIC.name
    }

    override suspend fun setSendspinAudioFormat(format: String) {
        context.dataStore.edit { it[KEY_SENDSPIN_AUDIO_FORMAT] = format }
    }

    override val sendspinClockOffsetUs: Flow<Long> = deviceData.map { prefs ->
        prefs[KEY_SENDSPIN_CLOCK_OFFSET_US]?.toLongOrNull() ?: 0L
    }

    override suspend fun setSendspinClockOffsetUs(offsetUs: Long) {
        context.deviceDataStore.edit { it[KEY_SENDSPIN_CLOCK_OFFSET_US] = offsetUs.toString() }
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

    override val recentSeedClusterGenres: Flow<List<Set<String>>> = safeData.map { prefs ->
        prefs[KEY_RECENT_SEED_CLUSTERS]
            ?.split(CLUSTER_SEPARATOR)
            ?.mapNotNull { line ->
                line.split(GENRE_SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                    .takeIf { it.isNotEmpty() }
            }
            ?: emptyList()
    }

    override suspend fun setRecentSeedClusterGenres(clusters: List<Set<String>>) {
        context.dataStore.edit { prefs ->
            val serialized = clusters
                .filter { it.isNotEmpty() }
                .joinToString(CLUSTER_SEPARATOR) { cluster ->
                    cluster.joinToString(GENRE_SEPARATOR)
                }
            if (serialized.isEmpty()) prefs.remove(KEY_RECENT_SEED_CLUSTERS)
            else prefs[KEY_RECENT_SEED_CLUSTERS] = serialized
        }
    }

    override val recentMixTrackUris: Flow<List<Set<String>>> = safeData.map { prefs ->
        decodeWindows(prefs[KEY_RECENT_MIX_TRACKS])
    }

    override suspend fun setRecentMixTrackUris(windows: List<Set<String>>) =
        writeWindows(KEY_RECENT_MIX_TRACKS, windows)

    override val recentMixArtistKeys: Flow<List<Set<String>>> = safeData.map { prefs ->
        decodeWindows(prefs[KEY_RECENT_MIX_ARTISTS])
    }

    override suspend fun setRecentMixArtistKeys(windows: List<Set<String>>) =
        writeWindows(KEY_RECENT_MIX_ARTISTS, windows)

    override val recentMixGenres: Flow<List<String>> = safeData.map { prefs ->
        prefs[KEY_RECENT_MIX_GENRES]
            ?.split(CLUSTER_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    override suspend fun setRecentMixGenres(genres: List<String>) {
        context.dataStore.edit { prefs ->
            val serialized = genres.filter { it.isNotBlank() }.joinToString(CLUSTER_SEPARATOR)
            if (serialized.isEmpty()) prefs.remove(KEY_RECENT_MIX_GENRES)
            else prefs[KEY_RECENT_MIX_GENRES] = serialized
        }
    }

    /**
     * One window per line, members comma separated. Malformed or empty windows are
     * dropped rather than failing the read: a corrupt preference must cost variety
     * at worst, never the mix.
     */
    private fun decodeWindows(raw: String?): List<Set<String>> =
        raw?.split(CLUSTER_SEPARATOR)
            ?.mapNotNull { line ->
                line.split(GENRE_SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                    .takeIf { it.isNotEmpty() }
            }
            ?: emptyList()

    private suspend fun writeWindows(
        key: Preferences.Key<String>,
        windows: List<Set<String>>
    ) {
        context.dataStore.edit { prefs ->
            val serialized = windows
                .filter { it.isNotEmpty() }
                .joinToString(CLUSTER_SEPARATOR) { it.joinToString(GENRE_SEPARATOR) }
            if (serialized.isEmpty()) prefs.remove(key) else prefs[key] = serialized
        }
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

    override val pinnedPlaylistUris: Flow<Set<String>> = safeData.map { prefs ->
        prefs[KEY_PINNED_PLAYLISTS]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    override suspend fun setPlaylistPinned(uri: String, pinned: Boolean) {
        if (uri.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_PLAYLISTS]?.split("\n")?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            val changed = if (pinned) current.add(uri) else current.remove(uri)
            if (changed) prefs[KEY_PINNED_PLAYLISTS] = current.joinToString("\n")
        }
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

    override val acousticRouteCalibrations: Flow<Map<String, AcousticRouteCalibration>> = deviceData.map { prefs ->
        decodeCalibrations(prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS])
    }

    private val calibrationSerializer = MapSerializer(String.serializer(), AcousticRouteCalibration.serializer())

    /**
     * The stored calibrations, without the round-trip ones from before the
     * absolute method: those measured a different quantity and are not applied.
     * Manual values (the TV's output delay) are kept.
     */
    private fun decodeCalibrations(raw: String?): Map<String, AcousticRouteCalibration> {
        if (raw == null) return emptyMap()
        val all = try {
            Json.decodeFromString(calibrationSerializer, raw)
        } catch (_: Exception) { emptyMap() }
        return all.filterValues {
            it.method == AcousticRouteCalibration.METHOD_ABSOLUTE || it.method == AcousticRouteCalibration.METHOD_MANUAL
        }
    }

    private fun encodeCalibrations(map: Map<String, AcousticRouteCalibration>): String =
        Json.encodeToString(calibrationSerializer, map)

    override suspend fun setAcousticRouteCalibration(routeKey: String, calibration: AcousticRouteCalibration) {
        context.deviceDataStore.edit { prefs ->
            val current = decodeCalibrations(prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS]).toMutableMap()
            current[routeKey] = calibration
            prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS] = encodeCalibrations(current)
            // Left by the round-trip calibration; nothing reads them any more.
            prefs.remove(KEY_LEGACY_ACOUSTIC_MIC_PATH_US)
            prefs.remove(KEY_LEGACY_ACOUSTIC_PHONE_BASELINE_US)
        }
    }

    override suspend fun removeAcousticRouteCalibration(routeKey: String) {
        context.deviceDataStore.edit { prefs ->
            val current = decodeCalibrations(prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS]).toMutableMap()
            current.remove(routeKey)
            prefs[KEY_ACOUSTIC_ROUTE_CALIBRATIONS] = encodeCalibrations(current)
        }
    }

    override val outputFineTuneMs: Flow<Map<String, Int>> = deviceData.map { prefs ->
        decodeFineTunes(prefs[KEY_OUTPUT_FINE_TUNE_MS])
    }

    private val fineTuneSerializer = MapSerializer(String.serializer(), Int.serializer())

    private fun decodeFineTunes(raw: String?): Map<String, Int> {
        if (raw == null) return emptyMap()
        val all = try {
            Json.decodeFromString(fineTuneSerializer, raw)
        } catch (_: Exception) { emptyMap() }
        val max = SettingsRepository.OUTPUT_FINE_TUNE_MAX_MS
        return all.mapValues { (_, ms) -> ms.coerceIn(-max, max) }.filterValues { it != 0 }
    }

    override suspend fun setOutputFineTuneMs(routeKey: String, ms: Int) {
        val max = SettingsRepository.OUTPUT_FINE_TUNE_MAX_MS
        val clamped = ms.coerceIn(-max, max)
        context.deviceDataStore.edit { prefs ->
            val current = decodeFineTunes(prefs[KEY_OUTPUT_FINE_TUNE_MS]).toMutableMap()
            if (clamped == 0) current.remove(routeKey) else current[routeKey] = clamped
            prefs[KEY_OUTPUT_FINE_TUNE_MS] = Json.encodeToString(fineTuneSerializer, current)
        }
    }

    override suspend fun resetLegacySyncDelayOnce(): Int? {
        var previous: Int? = null
        context.deviceDataStore.edit { prefs ->
            if (prefs[KEY_LEGACY_SYNC_DELAY_RESET] == true) return@edit
            previous = prefs[KEY_SENDSPIN_SYNC_DELAY_MS]?.toIntOrNull() ?: 0
            prefs.remove(KEY_SENDSPIN_SYNC_DELAY_MS)
            prefs[KEY_LEGACY_SYNC_DELAY_RESET] = true
        }
        return previous
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

    override val libraryAlbumArtistsOnly: Flow<Boolean> = safeData.map { prefs ->
        prefs[KEY_LIBRARY_ALBUM_ARTISTS_ONLY] ?: false
    }

    override suspend fun setLibraryAlbumArtistsOnly(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_LIBRARY_ALBUM_ARTISTS_ONLY] = enabled }
    }

    // Album types joined by '|': the server's type names are plain lowercase words.
    override val libraryAlbumTypes: Flow<Set<String>> = safeData.map { prefs ->
        prefs[KEY_LIBRARY_ALBUM_TYPES].orEmpty().split("|").filter { it.isNotBlank() }.toSet()
    }

    override suspend fun setLibraryAlbumTypes(types: Set<String>) {
        context.dataStore.edit { prefs ->
            if (types.isEmpty()) prefs.remove(KEY_LIBRARY_ALBUM_TYPES) else prefs[KEY_LIBRARY_ALBUM_TYPES] = types.joinToString("|")
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

internal const val MAX_RECENT_SEARCHES = 10

/**
 * The stored form of a search query: trimmed, with runs of whitespace collapsed.
 *
 * The history is stored newline separated, so an entry must not carry a newline
 * of its own, and collapsing the rest of the whitespace also makes "pink  floyd"
 * and "pink floyd" the same entry.
 */
internal fun normalizeSearchQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ")

/**
 * [query] folded into [current] as the newest entry, capped at [MAX_RECENT_SEARCHES].
 *
 * Searching the same thing again moves it to the top rather than storing it
 * twice. [replacing] is an entry the caller knows this query grew out of, from
 * the same typing run, and it is dropped so that typing "pink floyd" leaves one
 * entry instead of "pink" and "pink floyd". Anything else in [current] is left
 * alone, however similar it looks: a shorter search made earlier was a search
 * of its own.
 */
internal fun mergeRecentSearch(
    current: List<String>,
    query: String,
    replacing: String? = null
): List<String> {
    val entry = normalizeSearchQuery(query)
    if (entry.isBlank()) return current
    val superseded = replacing?.let { normalizeSearchQuery(it) }?.takeIf { it.isNotBlank() }
    val kept = current.filterNot {
        it.equals(entry, ignoreCase = true) || it.equals(superseded, ignoreCase = true)
    }
    return (listOf(entry) + kept).take(MAX_RECENT_SEARCHES)
}

// The per-server entries of the device store. Top-level and internal so the rules are tested on
// plain preferences, without a DataStore.

/** An address as the keys name it; see [normalizedAddress]. */
private fun addressOf(url: String) = normalizedAddress(url)

/**
 * Saves one server's account and the address it was used through. The address now leads to
 * [serverId]: a different server saved before at the same address keeps its token, because two
 * servers can share a private address on different networks, such as a friend's and one's own.
 */
internal fun MutablePreferences.putServerEntry(
    serverId: String,
    url: String,
    token: String,
    username: String,
    usedAtMs: Long = System.currentTimeMillis()
) {
    this[serverTokenKey(serverId)] = token
    this[serverUsernameKey(serverId)] = username
    putAddress(addressOf(url), serverId, usedAtMs)
}

private fun MutablePreferences.putAddress(address: String, serverId: String, usedAtMs: Long) {
    // Compared as addresses, without regard to case or a default port, so one address never
    // has two entries, also beside a key an earlier build stored unnormalized.
    storedKeysFor(address).filter { it != address }.forEach { removeAddress(it) }
    this[addressKey(address)] = serverId
    this[addressUsedAtKey(address)] = usedAtMs
}

/**
 * Forgets the address [url], and the account of each server behind it that no other address
 * leads to: the server that answered there ([serverId]), which is the account the form showed,
 * and the server the address was saved for. A token no row leads to would only wait to be
 * refused. The current address ([savedUrl]) is never forgotten, and neither is the current
 * server's account: Sign out leaves those.
 */
internal fun MutablePreferences.forgetServerAt(url: String, serverId: String?, savedUrl: String) {
    val wanted = addressOf(url)
    if (savedUrl.isBlank() || wanted.equals(addressOf(savedUrl), ignoreCase = true)) return
    val keys = storedKeysFor(wanted)
    val servers = (listOfNotNull(serverId) + keys.mapNotNull { this[addressKey(it)] }).distinct()
    keys.forEach { removeAddress(it) }
    val current = this[KEY_TRUSTED_SERVER_ID]
    servers.filter { it != current && addresses().none { address -> this[addressKey(address)] == it } }
        .forEach {
            remove(serverTokenKey(it))
            remove(serverUsernameKey(it))
        }
}

/** Forgets [token] where it is still the saved one: the current account and [serverId]'s entry. */
internal fun MutablePreferences.forgetRejectedToken(serverId: String?, token: String) {
    if (this[KEY_AUTH_TOKEN] == token) this[KEY_AUTH_TOKEN] = ""
    if (serverId != null && this[serverTokenKey(serverId)] == token) remove(serverTokenKey(serverId))
}

/**
 * Brings the saved accounts up to date, once each: the account saved before tokens were kept
 * per server moves to its server's entry, and the one address per server that the first builds
 * of that kept becomes that server's first address. A flag marks each done even when there was
 * nothing to move.
 */
internal fun MutablePreferences.migrateToServerAccounts(url: String) {
    migrateLegacyAddresses()
    if (this[KEY_SERVER_ACCOUNTS_MIGRATED] == true) return
    this[KEY_SERVER_ACCOUNTS_MIGRATED] = true
    val serverId = this[KEY_TRUSTED_SERVER_ID] ?: return
    val token = this[KEY_AUTH_TOKEN].orEmpty()
    if (token.isBlank() || url.isBlank() || this[serverTokenKey(serverId)] != null) return
    putServerEntry(serverId, url, token, this[KEY_USERNAME].orEmpty(), usedAtMs = 0L)
}

private fun MutablePreferences.migrateLegacyAddresses() {
    if (this[KEY_SERVER_ADDRESSES_MIGRATED] == true) return
    this[KEY_SERVER_ADDRESSES_MIGRATED] = true
    asMap().keys.map { it.name }.filter { it.startsWith(LEGACY_SERVER_URL_PREFIX) }
        .map { it.removePrefix(LEGACY_SERVER_URL_PREFIX) }
        .forEach { serverId ->
            val url = this[legacyServerUrlKey(serverId)]
            val usedAt = this[legacyServerUsedAtKey(serverId)] ?: 0L
            remove(legacyServerUrlKey(serverId))
            remove(legacyServerUsedAtKey(serverId))
            if (!url.isNullOrBlank()) putAddress(addressOf(url), serverId, usedAt)
        }
}

/** Every address signed in through, most recently used first. */
internal fun Preferences.usedAddresses(): List<String> =
    addresses().sortedByDescending { this[addressUsedAtKey(it)] ?: 0L }

/** The stored keys that name [address], an earlier build's unnormalized spelling included. */
private fun Preferences.storedKeysFor(address: String): List<String> =
    addresses().filter { addressOf(it).equals(address, ignoreCase = true) }

private fun Preferences.addresses(): List<String> =
    asMap().keys.map { it.name }.filter { it.startsWith(ADDRESS_PREFIX) }.map { it.removePrefix(ADDRESS_PREFIX) }

private fun MutablePreferences.removeAddress(address: String) {
    remove(addressKey(address))
    remove(addressUsedAtKey(address))
}
