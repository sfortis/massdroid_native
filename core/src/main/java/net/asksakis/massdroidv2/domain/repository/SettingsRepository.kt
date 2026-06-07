package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.LibraryDisplayMode
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
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
    val libraryDisplayModes: Flow<Map<LibraryTabKey, LibraryDisplayMode>>
    val librarySortOptions: Flow<Map<Int, SortOption>>
    val librarySortDescending: Flow<Map<Int, Boolean>>
    val libraryFavoritesOnly: Flow<Map<Int, Boolean>>
    val lastFmApiKey: Flow<String>
    val themeMode: Flow<String>
    val sendspinAudioFormat: Flow<String>
    /**
     * Client-side sync delay applied locally by the Android Sendspin engine,
     * range -1000..+1000 ms. **Positive shifts playback later, negative
     * shifts playback sooner** — the intuitive sign convention used by the
     * Music Assistant web UI's "Sendspin sync delay" slider. Independent of
     * the per-player spec field `static_delay_ms` (which is server-side and
     * available only on MA servers with PR #3689 deployed).
     */
    val sendspinSyncDelayMs: Flow<Int>
    val sendspinClockOffsetUs: Flow<Long>
    /**
     * Whether Sendspin player volume should be bridged to the phone's
     * STREAM_MUSIC. ON (default): hardware volume keys, in-app slider, AA host
     * volume, and the MA-side player volume all converge on a single gain
     * stage. OFF: STREAM_MUSIC stays independent — useful in cars where the
     * head unit attenuates further and the user wants phone STREAM_MUSIC at
     * 100% for full-fidelity BT transport while still controlling the
     * Sendspin player volume server-side.
     */
    val sendspinSyncSystemVolume: Flow<Boolean>
    /**
     * Last known Sendspin player volume (0..100), persisted across process
     * death. The MA server resets a re-registering Sendspin player to 100%, so
     * on startup we seed it back from this value rather than the phone's live
     * STREAM_MUSIC, which at launch can reflect a different output route's
     * stored level (the app's audio route is torn down on exit). Default 100.
     */
    val sendspinLastVolume: Flow<Int>
    /**
     * Cached one-way mic-path latency in microseconds, measured once on the
     * phone built-in speaker (chirp roundTrip minus Oboe outputHAL). Stays
     * stable per device + Android version, so subsequent BT calibrations
     * skip the phone-speaker reference pass and reuse this value to isolate
     * the BT output latency from the chirp roundTrip. 0 means uncalibrated;
     * callers fall back to a single-pass BT calibration (with Oboe input
     * latency as an approximation) in that case.
     */
    val acousticMicPathUs: Flow<Long>
    val acousticRouteCalibrations: Flow<Map<String, AcousticRouteCalibration>>

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
    suspend fun setLibraryDisplayMode(tab: LibraryTabKey, mode: LibraryDisplayMode)
    suspend fun setLibrarySortOption(tab: Int, option: SortOption)
    suspend fun setLibrarySortDescending(tab: Int, descending: Boolean)
    suspend fun setLibraryFavoritesOnly(tab: Int, favoritesOnly: Boolean)
    suspend fun setLastFmApiKey(key: String)
    suspend fun setThemeMode(mode: String)
    suspend fun setSendspinAudioFormat(format: String)
    suspend fun setSendspinSyncDelayMs(delayMs: Int)
    suspend fun setSendspinClockOffsetUs(offsetUs: Long)
    suspend fun setSendspinSyncSystemVolume(enabled: Boolean)
    suspend fun setSendspinLastVolume(volume: Int)
    suspend fun setAcousticMicPathUs(valueUs: Long)
    suspend fun setAcousticRouteCalibration(routeKey: String, calibration: AcousticRouteCalibration)
    suspend fun removeAcousticRouteCalibration(routeKey: String)
}

@kotlinx.serialization.Serializable
data class AcousticRouteCalibration(
    val correctionUs: Long,
    val quality: String,
    val updatedAt: Long
)
