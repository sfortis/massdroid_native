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
    /** Smart Mix variety 0f..1f: higher = wider per-artist track pool + jitter so repeated mixes diverge. */
    val smartMixVariety: Flow<Float>
    /** Smart Mix discovery 0f..1f: higher = more exploration / adjacent artists and genres, less comfort. */
    val smartMixDiscovery: Flow<Float>
    /** Smart Mix length 0f..1f: mapped to a track-count target for generated mixes. */
    val smartMixLength: Flow<Float>
    /** Smart Mix strictness 0f..1f: higher = seed only from your more-loved tracks (by score). */
    val smartMixStrictness: Flow<Float>
    val includeBetaUpdates: Flow<Boolean>
    val sendspinClientId: Flow<String?>
    val libraryDisplayModes: Flow<Map<LibraryTabKey, LibraryDisplayMode>>
    val librarySortOptions: Flow<Map<Int, SortOption>>
    val librarySortDescending: Flow<Map<Int, Boolean>>
    val libraryFavoritesOnly: Flow<Map<Int, Boolean>>
    val libraryProviderFilters: Flow<Map<Int, Set<String>>>
    // Single global sort for the tracks inside any playlist (PlaylistDetail), applied to every
    // playlist and persisted across navigation/restart (not per-playlist, not per-tab).
    val playlistSortKey: Flow<String>
    val playlistSortDescending: Flow<Boolean>
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
     * Phone-as-speaker output dynamic-range compressor level: 0 = off, 1 = soft,
     * 2 = medium, 3 = hard. Amplitude-only effect in the native output (sync-safe).
     * Higher levels reduce dynamic range (louder, denser) for noisy or late-night
     * listening; 0 keeps the original dynamics. Default 0.
     */
    val sendspinCompressorLevel: Flow<Int>
    /**
     * High-end output quantization for phone-as-speaker: noise-shaped TPDF dither
     * at the float->int16 step (sync-safe, amplitude only). Default off.
     */
    val sendspinDither: Flow<Boolean>
    /**
     * BT output devices the app has routed to at least once (route keys like
     * `bt:MINI45864`). Populates the "car audio" picker so the user can flag a
     * device without us enumerating bonded devices (no BLUETOOTH_CONNECT).
     */
    val knownBtDevices: Flow<Set<String>>
    /**
     * BT devices (route keys) flagged as car audio: on connect their output is
     * pinned to STREAM_MUSIC 100% (the head unit's own dial does the attenuation)
     * and the STREAM_MUSIC<->MA volume bridge is disabled so a car-pinned level is
     * never reset. Other BT devices keep the normal bridge.
     */
    val carAudioBtDevices: Flow<Set<String>>
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
    suspend fun setSmartMixVariety(value: Float)
    suspend fun setSmartMixDiscovery(value: Float)
    suspend fun setSmartMixLength(value: Float)
    suspend fun setSmartMixStrictness(value: Float)
    /** Restore all Smart Mix tuning knobs (variety, discovery, length) to defaults. */
    suspend fun resetSmartMixTuning()
    suspend fun setIncludeBetaUpdates(enabled: Boolean)
    suspend fun setSendspinClientId(clientId: String)
    suspend fun setLibraryDisplayMode(tab: LibraryTabKey, mode: LibraryDisplayMode)
    suspend fun setLibrarySortOption(tab: Int, option: SortOption)
    suspend fun setLibrarySortDescending(tab: Int, descending: Boolean)
    suspend fun setLibraryFavoritesOnly(tab: Int, favoritesOnly: Boolean)
    suspend fun setLibraryProviderFilters(tab: Int, instanceIds: Set<String>)
    suspend fun setPlaylistSortKey(key: String)
    suspend fun setPlaylistSortDescending(descending: Boolean)
    suspend fun setLastFmApiKey(key: String)
    suspend fun setThemeMode(mode: String)
    suspend fun setSendspinAudioFormat(format: String)
    suspend fun setSendspinSyncDelayMs(delayMs: Int)
    suspend fun setSendspinClockOffsetUs(offsetUs: Long)
    suspend fun setSendspinSyncSystemVolume(enabled: Boolean)
    suspend fun setSendspinCompressorLevel(level: Int)
    suspend fun setSendspinDither(enabled: Boolean)
    /** Record a BT route key as seen (idempotent), for the car-audio picker. */
    suspend fun recordKnownBtDevice(routeKey: String)
    /** Flag/unflag a BT route key as car audio (full volume on connect). */
    suspend fun setCarAudioBtDevice(routeKey: String, enabled: Boolean)
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
