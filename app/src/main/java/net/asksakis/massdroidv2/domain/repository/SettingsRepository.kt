package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
import net.asksakis.massdroidv2.domain.model.SortOption

interface SettingsRepository {
    val serverUrl: Flow<String>
    val authToken: Flow<String>
    val selectedPlayerId: Flow<String?>
    val clientCertAlias: Flow<String?>
    val username: Flow<String>
    val password: Flow<String>
    val sendspinEnabled: Flow<Boolean>
    val smartListeningEnabled: Flow<Boolean>
    val includeBetaUpdates: Flow<Boolean>
    val sendspinClientId: Flow<String?>
    val sendspinSnapshotTrackUri: Flow<String?>
    val sendspinSnapshotPositionSeconds: Flow<Double>
    val sendspinSnapshotQueueUris: Flow<List<String>>
    val libraryDisplayModes: Flow<Map<Int, LibraryDisplayMode>>
    val librarySortOptions: Flow<Map<Int, SortOption>>
    val librarySortDescending: Flow<Map<Int, Boolean>>
    val libraryFavoritesOnly: Flow<Map<Int, Boolean>>

    suspend fun setServerUrl(url: String)
    suspend fun setAuthToken(token: String)
    suspend fun setSelectedPlayerId(playerId: String?)
    suspend fun setClientCertAlias(alias: String?)
    suspend fun setUsername(username: String)
    suspend fun setPassword(password: String)
    suspend fun setSendspinEnabled(enabled: Boolean)
    suspend fun setSmartListeningEnabled(enabled: Boolean)
    suspend fun setIncludeBetaUpdates(enabled: Boolean)
    suspend fun setSendspinClientId(clientId: String)
    suspend fun setSendspinSnapshot(trackUri: String?, positionSeconds: Double, queueUris: List<String>)
    suspend fun clearSendspinSnapshot()
    suspend fun setLibraryDisplayMode(tab: Int, mode: LibraryDisplayMode)
    suspend fun setLibrarySortOption(tab: Int, option: SortOption)
    suspend fun setLibrarySortDescending(tab: Int, descending: Boolean)
    suspend fun setLibraryFavoritesOnly(tab: Int, favoritesOnly: Boolean)
}
