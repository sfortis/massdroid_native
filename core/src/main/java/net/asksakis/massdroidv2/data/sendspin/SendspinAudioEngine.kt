package net.asksakis.massdroidv2.data.sendspin

/** Protocol-derived stream/start classification. Decided by SendspinManager, not the engine. */
enum class ProtocolStartType {
    NEW_STREAM,
    CONTINUATION
}

/** Runtime correction mode. SYNC = multi-device beat sync. DIRECT = solo playback. */
enum class CorrectionMode {
    SYNC,
    DIRECT
}

/**
 * Sendspin audio playback engine interface.
 * Single implementation with configurable CorrectionMode.
 */
interface SendspinAudioEngine {

    // State
    val syncState: SyncState
    val measuredOutputLatencyUs: Long
    val correctionMode: CorrectionMode

    // Callbacks
    var onSyncStateChanged: ((SyncState) -> Unit)?
    var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float, dacAbsoluteMs: Float?) -> Unit)?

    // Clock sync
    var clockSynchronizer: ClockSynchronizer?
    /**
     * Client-side UX sync nudge, -1000..+1000 ms. Positive shifts playback
     * later, negative shifts it sooner — intuitive sign convention matching
     * the Music Assistant web UI's "Sendspin sync delay" slider. Independent
     * from the spec field `static_delay_ms` (which the engine derives from
     * acoustic calibration when reporting client/state to the server).
     */
    var syncDelayMs: Int
    var routeAcousticExtraUs: Long

    // Correction mode
    fun setCorrectionMode(mode: CorrectionMode)
    fun setCellularTransport(cellular: Boolean)

    // Configuration
    fun configure(
        codecName: String = "opus",
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: String? = null,
        startType: ProtocolStartType = ProtocolStartType.NEW_STREAM
    )
    fun currentConfigureGeneration(): Long

    // Frame input
    fun onBinaryMessage(data: ByteArray, generation: Long)

    // Playback control
    fun setPaused(paused: Boolean)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)

    // Stream lifecycle
    fun onStreamEnd()
    fun clearBuffer()
    fun expectDiscontinuity(reason: String)
    fun onTransportFailure()
    fun onOutputRouteChanged(reason: String)
    fun getRoutedDeviceType(): Int? = null
    fun getRoutedDeviceProductName(): String? = null

    // Buffer info
    fun bufferDurationMs(): Long
    fun bufferedBytes(): Long

    // Sync-specific (no-op in DIRECT mode). Called when the UX sync nudge
    // changes so the playback anchor shifts immediately without waiting for
    // a stream boundary. deltaMs is the change in syncDelayMs (positive ms
    // means user wants playback shifted later, anchor shifts forward).
    fun shiftAnchorForSyncDelayChange(deltaMs: Int)

    // Cleanup
    fun release()
}
