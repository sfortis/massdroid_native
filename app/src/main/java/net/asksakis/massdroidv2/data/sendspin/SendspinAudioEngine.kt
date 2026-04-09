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
    var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float) -> Unit)?
    var onOutputLatencyMeasured: ((Long) -> Unit)?

    // Clock sync
    var clockSynchronizer: ClockSynchronizer?
    var staticDelayMs: Int

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

    // Buffer info
    fun bufferDurationMs(): Long
    fun bufferedBytes(): Long

    // Output latency
    fun seedOutputLatency(persistedUs: Long)

    // Sync-specific (no-op in DIRECT mode)
    fun shiftAnchorForDelayChange(deltaMs: Int)

    // Cleanup
    fun release()
}
