package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferOverflowException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

enum class SyncState {
    IDLE,
    SYNCHRONIZED,
    HOLDOVER_PLAYING_FROM_BUFFER,
    SYNC_ERROR_REBUFFERING
}

/**
 * Unified Sendspin audio engine. Configurable CorrectionMode:
 * SYNC = multi-device beat-exact sync (Kalman, drift correction, late-frame dropping).
 * DIRECT = solo playback (buffer/decode/play, no sync overhead).
 */
class SendspinSyncEngine : SendspinAudioEngine {

    companion object {
        private const val TAG = "AudioStream"
        private const val DBG = "AudioSwitchDbg"
        private const val HEADER_SIZE = 9
        private const val MAX_ENCODED_BUFFER_BYTES = 8_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024
        private const val HOLDOVER_MIN_BUFFER_MS = 750L
        private const val LATE_CHUNK_DROP_GRACE_MS = 200L
        private const val STARTUP_ENQUEUE_GRACE_MS = 2000L

        // Sync startup buffer per reason
        private const val SYNC_NEW_STREAM_BUFFER_MS = 800L
        private const val SYNC_RELOCK_BUFFER_MS = 500L
        private const val SYNC_CONTINUATION_BUFFER_MS = 400L
        // Precision wait caps (bounded, not infinite)
        private const val SYNC_NEW_STREAM_PRECISION_WAIT_MS = 3000L
        private const val SYNC_RELOCK_PRECISION_WAIT_MS = 3000L

        // Sync mode thresholds
        private const val MIN_SYNC_BUFFER_MS = 200L
        private const val SYNC_DEADBAND_MS = 1.0
        private const val SYNC_SAMPLE_CORRECTION_MS = 8.0
        private const val SYNC_RATE_GENTLE_MS = 20.0
        private const val SYNC_RESYNC_MS = 500.0  // matches SendspinDroid; DAC correction handles <500ms
        private const val SYNC_CHECK_INTERVAL_FRAMES = 10
        private const val SYNC_STARTUP_GRACE_MS = 3000L
        private const val SYNC_RESYNC_COOLDOWN_MS = 3000L
        private const val SYNC_ERROR_EMA_ALPHA = 0.10
        private const val CONTINUATION_GRACE_MS = 1000L
        private const val RATE_GENTLE = 0.005f   // 0.5% speed change
        private const val RATE_STRONG = 0.01f    // 1.0% speed change

        // Direct mode thresholds
        private const val DIRECT_STARTUP_MS_OPUS = 300L
        private const val DIRECT_STARTUP_MS_LOSSLESS = 500L
        private const val DIRECT_RECOVERY_MS_OPUS = 1500L
        private const val DIRECT_RECOVERY_MS_LOSSLESS = 2000L
        private const val DIRECT_CELLULAR_EXTRA_MS = 500L

        // "Play first, correct later" thresholds
        private const val CLOCK_PRECISION_TIMEOUT_MS = 10_000L
        private const val CLOCK_PRECISION_THRESHOLD_US = 15_000  // Int to match errorUs()

        // DAC divergence validator: anchor re-seat when DAC disagrees persistently
        private const val DAC_DIVERGENCE_EMA_ALPHA = 0.15
        private const val DAC_DIVERGENCE_THRESHOLD_MS = 15.0
        private const val DAC_DIVERGENCE_SIGN_COUNT = 10
        private const val DAC_DIVERGENCE_MATURITY = 20  // min DAC absolute samples before trusting
        private const val DAC_RESEAT_COOLDOWN_MS = 15_000L
        private const val DAC_DIVERGENCE_RESEAT_ENABLED = false
        private const val DAC_WARMUP_MS = 1_000L
        private const val BT_LIKE_OUTPUT_LATENCY_US = 50_000L
        private const val BT_LIKE_ACOUSTIC_LATENCY_US = 100_000L
        private const val FADE_IN_STEPS = 15  // ~300ms at 20ms/frame

        // BT hidden transport latency: varies wildly by codec/device (80-200ms).
        // Not compensated automatically; user tunes via static_delay per device.
        // TODO: per-device latency UI with auto-save by BT MAC address
    }

    private data class EncodedFrame(val serverTimestampUs: Long, val payload: ByteArray, val generation: Long) :
        Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    // Encoded frame queue (filled by WS thread, consumed by playback thread)
    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private var oversizedDropCount = 0L
    private var consecutiveDecodeFailures = 0
    private val codecLock = Any()

    // Audio output
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val playbackThreadLock = Any()
    @Volatile private var playbackGeneration = 0L

    // State
    @Volatile private var configured = false
    @Volatile private var playbackActive = true
    @Volatile private var playbackStarted = false
    @Volatile private var configureGeneration = 0L
    @Volatile private var activeCodec = "opus"
    @Volatile private var activeBitDepth = 16
    private var activeSampleRate = 0
    private var activeChannels = 0
    @Volatile private var frameCount = 0
    private var decodedFrameCount = 0
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var pendingContinuityCheck = false
    @Volatile private var pendingAudioTrackFlush = false
    @Volatile private var holdoverEndTimestampUs = 0L
    @Volatile private var lastPlayedServerTimestampUs = 0L
    @Volatile private var discardUntilTimestampUs = 0L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile private var forceDiscontinuityUntilMs = 0L
    @Volatile private var forceDiscontinuityReason = ""
    @Volatile private var gaplessGraceUntilMs = 0L      // extended starvation grace for track transitions
    @Volatile private var requiredSyncBufferMs = MIN_SYNC_BUFFER_MS
    @Volatile private var lateDropCount = 0L
    @Volatile private var enqueueLateDropGraceUntilUs = 0L
    @Volatile private var lastFlacLowBufferLogMs = 0L
    private var presentationTimeUs = 0L

    // Correction mode
    // Default SYNC: safer on cold start (sync startup thresholds).
    // Downgraded to DIRECT only when confirmed solo by group collector.
    @Volatile override var correctionMode = CorrectionMode.SYNC; private set
    private val isSyncMode get() = correctionMode == CorrectionMode.SYNC

    // Stream ownership (epoch invalidation, both modes)
    @Volatile private var streamGeneration = 0L
    @Volatile private var acceptGeneration = 0L
    @Volatile private var generationDropCount = 0L
    @Volatile private var hardBoundaryPending = true
    @Volatile private var awaitingStreamStartAfterDiscontinuity = false

    // Network-adaptive (DIRECT mode)
    @Volatile private var isCellular = false

    // Sync state per spec
    @Volatile override var syncState = SyncState.IDLE; private set
    override var onSyncStateChanged: ((SyncState) -> Unit)? = null

    // Volume
    @Volatile private var currentVolume = 1f
    @Volatile private var isMuted = false

    // Protocol semantic for internal recovery
    @Volatile private var lastProtocolStartType = ProtocolStartType.NEW_STREAM
    @Volatile private var lastCodecHeader: String? = null

    // Clock sync
    @Volatile override var clockSynchronizer: ClockSynchronizer? = null
    @Volatile override var staticDelayMs: Int = 0
    // Output latency measurement + acoustic correction (extracted)
    private val latencyModel = OutputLatencyModel(TAG)
    override var routeAcousticExtraUs: Long
        get() = latencyModel.routeAcousticExtraUs
        set(value) { latencyModel.routeAcousticExtraUs = value }
    // "Play first, correct later": defer corrections until clock converges
    @Volatile private var clockPreciseForCorrections = false
    // Sync startup reason: controls buffer threshold and precision wait
    private enum class SyncStartupReason { NEW_STREAM, RELOCK_AFTER_SEEK, SOFT_CONTINUATION }
    @Volatile private var syncStartupReason = SyncStartupReason.NEW_STREAM
    // Rate correction state
    @Volatile private var currentPlaybackRate = 1.0f
    // Sync-mute: mute audio until DAC sync error converges after hard boundary
    @Volatile var syncMuted = false; private set
    // Routing change callback (set by controller for route detection)
    var onRoutingChanged: (() -> Unit)? = null
    @Volatile private var suppressRoutingCallbacks = false
    /** Run [block] with routing callbacks suppressed, guaranteed reset via try/finally. */
    private inline fun withRoutingSuppressed(block: () -> Unit) {
        val previous = suppressRoutingCallbacks
        suppressRoutingCallbacks = true
        try { block() } finally { suppressRoutingCallbacks = previous }
    }
    @Volatile private var lastRoutedDeviceType = -1
    @Volatile private var lastRoutedProductName: String? = null

    // DAC calibration, timeline tracking, and drift measurement (extracted)
    private val dacValidator = DacDriftValidator(TAG)
    // Callbacks
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float, dacAbsoluteMs: Float?) -> Unit)? = null

    // Continuation grace: after soft stream/start, suppress correction briefly
    @Volatile private var continuationGraceUntilMs = 0L

    // Sync correction state
    @Volatile var smoothedSyncErrorMs = 0.0; private set  // EMA-filtered sync error (DAC-based)
    @Volatile var startupOffsetMs = 0.0; private set  // absolute offset captured at startup (positive = late)
    private var lastSyncLogMs = 0L
    @Volatile var resyncCount = 0; private set
    private var playbackStartedAtMs = 0L             // wall clock when playback started
    private var clockWaitStartMs = 0L                // wall clock when we started waiting for clock convergence
    private var lastResyncAtMs = 0L                  // wall clock of last hard resync
    private var anchorServerTimestampUs = 0L          // server ts of first played frame after grace
    private var anchorLocalUs = 0L                    // local time when anchor was set
    private var anchorLocalEquivalentUs = 0L          // cached serverToLocalUs(anchorTs) at anchor time

    // Per-write fade-in: avoids Thread.sleep() blocking the playback thread.
    // Each decoded frame write advances volume by one step (~20ms per step).
    private var fadeInRemaining = 0
    private var fadeInStep = 0

    /** Advance one fade-in step after a track.write(). No-op when fade is not active. */
    private fun applyFadeInStep(track: AudioTrack) {
        if (fadeInRemaining <= 0) return
        fadeInStep++
        track.setVolume(currentVolume * fadeInStep.toFloat() / FADE_IN_STEPS)
        fadeInRemaining--
    }

    private fun isBtLikeOutput(): Boolean =
        measuredOutputLatencyUs > BT_LIKE_OUTPUT_LATENCY_US ||
            routeAcousticExtraUs > BT_LIKE_ACOUSTIC_LATENCY_US

    private fun startSyncFadeIn(errorMs: Double, reason: String) {
        syncMuted = false
        if (!isMuted) {
            fadeInRemaining = FADE_IN_STEPS
            fadeInStep = 0
        }
        Log.d(TAG, "Sync converged, starting fade-in ($reason error=${"%.1f".format(errorMs)}ms)")
    }

    override fun bufferDurationMs(): Long {
        val headTs = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tailTs = lastEnqueuedTimestampUs
        if (tailTs <= 0L || tailTs < headTs) return 0L
        return ((tailTs - headTs + estimatedFrameDurationUs).coerceAtLeast(0L)) / 1000L
    }

    override fun bufferedBytes(): Long = frameQueueBytes.get()


    override fun setCorrectionMode(mode: CorrectionMode) {
        if (correctionMode == mode) return
        val old = correctionMode
        correctionMode = mode
        clockPreciseForCorrections = false
        dacValidator.resetTimeline(audioTrack)
        // Keep existing output latency measurement: same AudioTrack, same hardware pipeline.
        // Reset DAC timestamp stability so pre-cal runs on next sync startup
        // (group join/create needs fresh DAC calibration for precise alignment).
        if (mode == CorrectionMode.SYNC) {
            latencyModel.resetForBoundary()
            dacValidator.resetStability()
        }
        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
        Log.d(TAG, "CorrectionMode: $old -> $mode")
        // Reset sync-specific state
        anchorServerTimestampUs = 0L
        anchorLocalUs = 0L
        anchorLocalEquivalentUs = 0L
        smoothedSyncErrorMs = 0.0
        startupOffsetMs = 0.0
        pendingSampleCorrection = 0
        pendingSampleCount = 1
        lateDropCount = 0L
        enqueueLateDropGraceUntilUs = 0L
        resyncCount = 0
        lastResyncAtMs = 0L
        requiredSyncBufferMs = defaultBufferMs()
        // Group join (DIRECT -> SYNC): soft transition.
        // Cannot flush+rebuffer because pre-buffered frames are ~30s in the
        // future, causing a bogus 27s DAC sync error and infinite RESYNC loop.
        // Instead: mute, reset timing, let correction loop align, then fade in.
        if (mode == CorrectionMode.SYNC && playbackStarted) {
            syncMuted = true
            audioTrack?.setVolume(0f)
            playbackStartedAtMs = System.currentTimeMillis()
            continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
            Log.d(TAG, "Group join: soft transition, muted until sync converges")
        }
    }

    override fun setCellularTransport(cellular: Boolean) {
        isCellular = cellular
    }

    private fun nowLocalUs(): Long = System.nanoTime() / 1000L

    @Volatile private var hwBufferLatencyUs = 0L

    // Output latency measurement delegated to OutputLatencyModel
    override val measuredOutputLatencyUs: Long
        get() = latencyModel.measuredOutputLatencyUs

    /**
     * Target local play time with full latency compensation.
     * Used for playout timing, startup alignment, and anchor sync.
     *
     * Latency compensation = max(measuredPipeline, routeAcousticExtraUs).
     * - measuredPipeline: AudioTrack.getTimestamp() pipeline latency (AudioFlinger buffer).
     *   For phone ~25ms, for BT ~200-300ms (includes A2DP HAL buffering).
     * - routeAcousticExtraUs: calibration result (btRoundTrip - phoneBaseline).
     *   Captures BT transport + speaker delay relative to phone speaker.
     *   These overlap with measuredPipeline on BT, so acousticExtraUs() subtracts
     *   measuredPipeline to avoid double-counting.
     */
    private fun targetLocalPlayUs(serverTimestampUs: Long): Long {
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
            ?: serverTimestampUs
        return localUs + (staticDelayMs.toLong() * 1000L) - latencyModel.totalCompensationUs()
    }

    /**
     * Target local play time using only measured pipeline latency.
     * Used for buffer management (stale-frame skip, late-frame drop)
     * where the AudioTrack pipeline latency is the correct reference.
     */
    private fun targetLocalPlayUsForBuffering(serverTimestampUs: Long): Long {
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
            ?: serverTimestampUs
        val latencyCompensation = if (measuredOutputLatencyUs > 50_000L) {
            measuredOutputLatencyUs
        } else {
            0L
        }
        return localUs + (staticDelayMs.toLong() * 1000L) - latencyCompensation
    }

    private fun acousticExtraUs(): Long = latencyModel.acousticExtraUs()

    private fun hasOutputLatencyMeasurement(): Boolean = latencyModel.hasValidMeasurement()

    /** Lead using buffering target (for stale/late decisions). */
    private fun leadToLocalNowMs(serverTimestampUs: Long): Long =
        (targetLocalPlayUsForBuffering(serverTimestampUs) - nowLocalUs()) / 1000L

    private fun shouldDropLateFrame(serverTimestampUs: Long): Boolean {
        return leadToLocalNowMs(serverTimestampUs) < -LATE_CHUNK_DROP_GRACE_MS
    }

    private fun shouldDropLateFrameOnEnqueue(serverTimestampUs: Long): Boolean {
        if (nowLocalUs() < enqueueLateDropGraceUntilUs) return false
        return shouldDropLateFrame(serverTimestampUs)
    }

    private fun logLateDrop(serverTimestampUs: Long, source: String) {
        lateDropCount++
        maybeLogFlacLowBuffer("lateDrop-$source")
        if (lateDropCount <= 5 || lateDropCount % 100 == 0L) {
            Log.d(
                TAG,
                "Dropped late frame #$lateDropCount from $source: lead=${leadToLocalNowMs(serverTimestampUs)}ms buf=${bufferDurationMs()}ms"
            )
        }
    }

    private fun maybeLogFlacLowBuffer(reason: String) {
        if (activeCodec != "flac" || syncState != SyncState.SYNCHRONIZED) return
        val bufMs = bufferDurationMs()
        if (bufMs > 1000) return
        val now = System.currentTimeMillis()
        if (now - lastFlacLowBufferLogMs < 1000) return
        lastFlacLowBufferLogMs = now
        Log.d(
            DBG,
            "flacLowBuffer reason=$reason buf=${bufMs}ms queueBytes=${frameQueueBytes.get()} " +
                "lateDrops=$lateDropCount started=$playbackStarted playbackActive=$playbackActive"
        )
    }

    private fun isStartupBufferReady(): Boolean {
        return bufferDurationMs() >= requiredSyncBufferMs
    }

    private fun dropLateHeadFramesForStartup(): Boolean {
        if (nowLocalUs() < enqueueLateDropGraceUntilUs) return false
        var droppedAny = false
        while (true) {
            val head = frameQueue.peek() ?: break
            if (!shouldDropLateFrame(head.serverTimestampUs)) break
            frameQueue.poll() ?: break
            frameQueueBytes.addAndGet(-head.payload.size.toLong())
            logLateDrop(head.serverTimestampUs, "startup")
            droppedAny = true
        }
        return droppedAny
    }

    private fun transitionSyncState(newState: SyncState) {
        if (syncState == newState) return
        val old = syncState
        syncState = newState
        Log.d(TAG, "Sync: $old -> $newState (buf=${bufferDurationMs()}ms, ${frameQueueBytes.get() / 1000}KB)")
        if (newState == SyncState.SYNCHRONIZED) {
            requiredSyncBufferMs = defaultBufferMs()
        }
        onSyncStateChanged?.invoke(newState)
    }

    private fun defaultBufferMs(): Long = when (correctionMode) {
        CorrectionMode.SYNC -> syncBufferForReason(syncStartupReason)
        CorrectionMode.DIRECT -> {
            if (activeCodec == "opus") DIRECT_STARTUP_MS_OPUS else DIRECT_STARTUP_MS_LOSSLESS
        }
    }

    private fun syncBufferForReason(reason: SyncStartupReason): Long = when (reason) {
        SyncStartupReason.NEW_STREAM -> SYNC_NEW_STREAM_BUFFER_MS
        SyncStartupReason.RELOCK_AFTER_SEEK -> SYNC_RELOCK_BUFFER_MS
        SyncStartupReason.SOFT_CONTINUATION -> SYNC_CONTINUATION_BUFFER_MS
    }

    private fun syncPrecisionWaitMs(): Long = when (syncStartupReason) {
        SyncStartupReason.NEW_STREAM -> SYNC_NEW_STREAM_PRECISION_WAIT_MS
        SyncStartupReason.RELOCK_AFTER_SEEK -> SYNC_RELOCK_PRECISION_WAIT_MS
        SyncStartupReason.SOFT_CONTINUATION -> 0L  // no precision wait for soft continuation
    }

    private fun recoveryBufferMs(): Long = when (correctionMode) {
        CorrectionMode.SYNC -> SYNC_RELOCK_BUFFER_MS
        CorrectionMode.DIRECT -> {
            val base = if (activeCodec == "opus") DIRECT_RECOVERY_MS_OPUS else DIRECT_RECOVERY_MS_LOSSLESS
            base + if (isCellular) DIRECT_CELLULAR_EXTRA_MS else 0L
        }
    }

    override fun configure(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        startType: ProtocolStartType
    ) {
        val isNewStream = startType == ProtocolStartType.NEW_STREAM
        lastProtocolStartType = startType
        lastCodecHeader = codecHeader
        hardBoundaryPending = false
        awaitingStreamStartAfterDiscontinuity = false
        acceptGeneration = streamGeneration
        generationDropCount = 0

        // Same codec + same params
        if (configured && codecName == activeCodec && sampleRate == activeSampleRate
            && channels == activeChannels && bitDepth == activeBitDepth
        ) {
            if (isNewStream) {
                // NEW_STREAM same-codec: full reset, rebuffer
                Log.d(TAG, "configure semantic=NEW_STREAM path=same-codec codec=$codecName sync=$syncState buf=${bufferDurationMs()}ms")
                // Keep RELOCK_AFTER_SEEK if already set (seek -> streamEnd -> configure NEW_STREAM)
                if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                    syncStartupReason = SyncStartupReason.NEW_STREAM
                }
                clockPreciseForCorrections = false

                applyPlaybackRate(1.0f)
                configureGeneration++
                lastEnqueuedTimestampUs = 0L
                frameQueue.clear()
                frameQueueBytes.set(0)
                recreateCodecForStream(codecName, sampleRate, channels, bitDepth, codecHeader)
                presentationTimeUs = 0L
                lateDropCount = 0L
                decodedFrameCount = 0
                consecutiveDecodeFailures = 0
                dacValidator.resetTimeline(audioTrack)
                // AudioTrack was paused+flushed by onStreamEnd before this
                // configure call, so DAC calibrations are stale.
                dacValidator.clearCalibrations()
                lastFrameReceivedMs = System.currentTimeMillis()
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                pendingSampleCorrection = 0
                pendingSampleCount = 1
                latencyModel.resetForBoundary()
                holdoverEndTimestampUs = 0L
                pendingAudioTrackFlush = false
                gaplessGraceUntilMs = 0L
                playbackStarted = false
                requiredSyncBufferMs = recoveryBufferMs()
                enqueueLateDropGraceUntilUs = nowLocalUs() + (STARTUP_ENQUEUE_GRACE_MS * 1000L)
            } else {
                // CONTINUATION same-codec: preserve queue, fresh timing lock
                // Keep RELOCK if already set (stream/clear before continuation = hard boundary)
                if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                    syncStartupReason = if (isSyncMode) SyncStartupReason.RELOCK_AFTER_SEEK else SyncStartupReason.SOFT_CONTINUATION
                }
                Log.d(TAG, "configure semantic=CONTINUATION path=same-codec action=timing-reset codec=$codecName buf=${bufferDurationMs()}ms reason=$syncStartupReason")
                configureGeneration++
                lastFrameReceivedMs = System.currentTimeMillis()
                lateDropCount = 0L
                // Full timing state reset (fresh lock, not fresh stream)
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                startupOffsetMs = 0.0
                pendingSampleCorrection = 0
                pendingSampleCount = 1
                resyncCount = 0
                lastResyncAtMs = 0L
                // Reset DAC timeline: stream/clear invalidated the previous write history.
                // Without this, DAC metrics after continuation use stale frame counters.
                dacValidator.resetTimeline(audioTrack)
                // Continuation grace: suppress correction during noisy re-lock period
                continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
                enqueueLateDropGraceUntilUs = nowLocalUs() + (2_000L * 1000L)
                if (playbackStarted) {
                    playbackStartedAtMs = System.currentTimeMillis()
                }
            }
            if (!playbackActive || playbackThread?.isAlive != true) {
                restartPlaybackThreadForConfiguredTrack()
            }
            pendingContinuityCheck = true
            return
        }

        // Hot-swap: different codec but same audio format (sample rate + channels).
        // Keep AudioTrack alive (hardware buffer bridges the codec switch).
        if (configured && playbackStarted
            && sampleRate == activeSampleRate && channels == activeChannels
            && audioTrack != null && playbackThread?.isAlive == true
        ) {
            val newCodec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)
            val oldCodec: MediaCodec?
            synchronized(codecLock) {
                oldCodec = codec
                codec = newCodec
            }
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}

            // Queue must clear (old codec frames can't decode on new codec)
            frameQueue.clear()
            frameQueueBytes.set(0)
            lastEnqueuedTimestampUs = 0L
            estimatedFrameDurationUs = 20_000L
            presentationTimeUs = 0L
            lateDropCount = 0L
            oversizedDropCount = 0L
            consecutiveDecodeFailures = 0
            configureGeneration++
            enqueueLateDropGraceUntilUs = nowLocalUs() + (5_000L * 1000L)
            lastFrameReceivedMs = System.currentTimeMillis()
            activeCodec = codecName
            activeBitDepth = bitDepth
            pendingContinuityCheck = false

            // Full sync lifecycle reset for hot-swap (codec change is a hard timing boundary)
            clockPreciseForCorrections = false
    
            anchorServerTimestampUs = 0L
            anchorLocalUs = 0L
            anchorLocalEquivalentUs = 0L
            smoothedSyncErrorMs = 0.0
            startupOffsetMs = 0.0
            pendingSampleCorrection = 0
            applyPlaybackRate(1.0f)

            if (isNewStream) {
                if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                    syncStartupReason = SyncStartupReason.NEW_STREAM
                }
                playbackStarted = false
                requiredSyncBufferMs = defaultBufferMs()
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                Log.d(TAG, "configure semantic=NEW_STREAM path=hot-swap codec=$codecName sync=$syncState reason=$syncStartupReason")
            } else {
                if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                    syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
                }
                playbackStarted = false
                requiredSyncBufferMs = defaultBufferMs()
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                Log.d(TAG, "configure semantic=CONTINUATION path=hot-swap codec=$codecName sync=$syncState buf=${bufferDurationMs()}ms reason=$syncStartupReason")
            }
            return
        }

        // Full rebuild: first configure or sample rate/channels changed
        Log.d(TAG, "configure semantic=${if (isNewStream) "NEW_STREAM" else "CONTINUATION"} path=rebuild " +
            "codec=$codecName ${sampleRate}Hz sync=$syncState buf=${bufferDurationMs()}ms")
        release_internal()
        activeCodec = codecName
        activeBitDepth = bitDepth
        activeSampleRate = sampleRate
        activeChannels = channels
        enqueueLateDropGraceUntilUs = nowLocalUs() + (STARTUP_ENQUEUE_GRACE_MS * 1000L)

        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = minBuf * 2 * 2
        // Output latency estimate: use minBuf (system minimum, ~one mixer period)
        // not inflated bufferSize. Actual pipeline = AudioTrack + AudioFlinger + HAL.
        // minBuf is Android's estimate of the minimum write-to-output latency.
        val bytesPerSecond = sampleRate * channels * 2
        hwBufferLatencyUs = minBuf.toLong() * 1_000_000L / bytesPerSecond
        Log.d(TAG, "AudioTrack buffer: ${bufferSize}B, output latency estimate: ${hwBufferLatencyUs / 1000}ms (minBuf=${minBuf}B)")

        val attrsBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        // Opt into automatic spatial rendering (Android 12+). On premium AAOS
        // cars (Polestar 3 with Bowers & Wilkins, EX90, Hummer EV with AKG)
        // the platform spatializer uses this hint to enable Dolby Atmos /
        // height-channel decoding when the source content supports it. On
        // phones and older devices the flag is a no-op, so it's safe always.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            attrsBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
        }
        val createdAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrsBuilder.build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        codec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)
        createdAudioTrack.addOnRoutingChangedListener({ router ->
            if (suppressRoutingCallbacks) return@addOnRoutingChangedListener
            val device = router.routedDevice
            val deviceType = device?.type ?: -1
            val productName = device?.productName?.toString()
            // Detect both route type changes (speaker<->bt) AND device changes (bt:A -> bt:B)
            if (deviceType == lastRoutedDeviceType && productName == lastRoutedProductName) return@addOnRoutingChangedListener
            lastRoutedDeviceType = deviceType
            lastRoutedProductName = productName
            Log.d(TAG, "AudioTrack route changed: type=$deviceType ($productName)")
            onRoutingChanged?.invoke()
        }, null)
        audioTrack = createdAudioTrack
        lastRoutedDeviceType = createdAudioTrack.routedDevice?.type ?: -1
        lastRoutedProductName = createdAudioTrack.routedDevice?.productName?.toString()
        // Notify controller so it can load acoustic calibration with the actual routed device name
        // (initial load at start() may have seen bt:unknown before AudioTrack existed)
        onRoutingChanged?.invoke()
        configureGeneration++
        configured = true
        playbackActive = true
        playbackStarted = false
        frameCount = 0
        decodedFrameCount = 0
        latencyModel.onRouteChanged()  // new AudioTrack = new hardware pipeline

        dacValidator.resetForNewTrack()
        presentationTimeUs = 0L
        rateCorrectionSupported = true
        currentPlaybackRate = 1.0f

        if (isNewStream) {
            if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                syncStartupReason = SyncStartupReason.NEW_STREAM
            }
            clockPreciseForCorrections = false

            requiredSyncBufferMs = defaultBufferMs()
            continuationGraceUntilMs = 0L
        } else {
            if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
            }
            // CONTINUATION rebuild: full AudioTrack rebuild is a hard timing boundary
            requiredSyncBufferMs = defaultBufferMs()
            continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
        }
        startPlaybackThread(createdAudioTrack)
        Log.d(TAG, "configure semantic=${if (isNewStream) "NEW_STREAM" else "CONTINUATION"} path=rebuild " +
            "codec=$codecName ${sampleRate}Hz threshold=${requiredSyncBufferMs}ms reason=$syncStartupReason")
    }

    // ── WS callback: just enqueue encoded frame (fast, no decode) ──

    override fun currentConfigureGeneration(): Long = configureGeneration

    override fun onBinaryMessage(data: ByteArray, generation: Long) {
        if (!configured || !playbackActive) return
        if (generation != configureGeneration) return
        if (streamGeneration != acceptGeneration) {
            generationDropCount++
            if (generationDropCount <= 3 || generationDropCount % 200 == 0L) {
                Log.d(TAG, "Stale frame dropped (gen=$acceptGeneration/$streamGeneration) #$generationDropCount")
            }
            return
        }
        if (data.size < HEADER_SIZE) return

        frameCount++
        val serverTimestampUs = parseTimestampUs(data)
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        if (payload.isEmpty()) return

        // Continuity check on stream restart
        if (pendingContinuityCheck) {
            pendingContinuityCheck = false
            handleContinuityCheck(serverTimestampUs)
        }

        if (discardUntilTimestampUs > 0L) {
            if (serverTimestampUs < discardUntilTimestampUs) return
            Log.d(TAG, "Holdover overlap discard complete at frame #$frameCount, buf=${bufferDurationMs()}ms")
            discardUntilTimestampUs = 0L
        }

        lastFrameReceivedMs = System.currentTimeMillis()

        if (isSyncMode && shouldDropLateFrameOnEnqueue(serverTimestampUs)) {
            logLateDrop(serverTimestampUs, "enqueue")
            return
        }
        // Enqueue encoded frame (no decode, no blocking)
        if (frameQueueBytes.get() < MAX_ENCODED_BUFFER_BYTES) {
            val spacingUs = serverTimestampUs - lastEnqueuedTimestampUs
            if (lastEnqueuedTimestampUs > 0L && spacingUs in 5_000L..500_000L) {
                estimatedFrameDurationUs = spacingUs
            }
            frameQueue.offer(EncodedFrame(serverTimestampUs, payload, acceptGeneration))
            frameQueueBytes.addAndGet(payload.size.toLong())
            lastEnqueuedTimestampUs = serverTimestampUs
        }

        if (frameCount <= 5 || frameCount % 500 == 0) {
            Log.d(TAG, "Frame #$frameCount ($activeCodec): ${payload.size}B, buf=${bufferDurationMs()}ms")
        }
    }

    private fun handleContinuityCheck(serverTimestampUs: Long) {
        val forceDiscontinuity = forceDiscontinuityUntilMs > System.currentTimeMillis()
        if (forceDiscontinuity) {
            Log.d(TAG, "Reconnect: explicit-discontinuity(${forceDiscontinuityReason}), flushing buffer")
            forceDiscontinuityUntilMs = 0L
            forceDiscontinuityReason = ""
            flushForRebuffer()
            return
        }
        forceDiscontinuityUntilMs = 0L
        forceDiscontinuityReason = ""

        val lastTs = lastEnqueuedTimestampUs
        val gap = serverTimestampUs - lastTs
        val bufMs = bufferDurationMs()
        Log.d(
            DBG,
            "continuity codec=$activeCodec gapMs=${gap / 1000} buf=${bufMs}ms sync=$syncState " +
                "discardUntil=${discardUntilTimestampUs / 1000}ms"
        )
        when {
            lastTs == 0L || syncState == SyncState.SYNC_ERROR_REBUFFERING -> {
                val prevPlayed = lastPlayedServerTimestampUs
                val deltaMs = if (prevPlayed > 0) (serverTimestampUs - prevPlayed) / 1000L else -1L
                // Large delta = server timeline rebase -> upgrade to hard relock
                if (isSyncMode && deltaMs != -1L && kotlin.math.abs(deltaMs) > 200 &&
                    syncStartupReason == SyncStartupReason.SOFT_CONTINUATION) {
                    syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
                    Log.d(TAG, "Reconnect: large delta ${deltaMs}ms, upgrading to RELOCK")
                }
                Log.d(TAG, "Reconnect: first-frame (lastTs=${lastTs / 1000}ms, sync=$syncState), accepting " +
                    "[prevPlayed=${prevPlayed / 1000}ms newTs=${serverTimestampUs / 1000}ms delta=${deltaMs}ms reason=$syncStartupReason]")
            }
            gap < 0 && syncState == SyncState.HOLDOVER_PLAYING_FROM_BUFFER && bufMs > HOLDOVER_MIN_BUFFER_MS
                    && gap > -5_000_000 -> {
                discardUntilTimestampUs = lastTs
                Log.d(TAG, "Reconnect: HOLDOVER_OVERLAP(${gap / 1000}ms), buf=${bufMs}ms, discarding until tail")
            }
            gap in -5_000_000..5_000_000 -> {
                Log.d(TAG, "Reconnect: CONTINUOUS (${gap / 1000}ms), buf=${bufMs}ms kept")
            }
            else -> {
                if (isSyncMode) {
                    // Sync mode: full flush for deterministic relock
                    Log.d(TAG, "Reconnect: DISCONTINUITY(${gap / 1000}ms), buf=${bufMs}ms, full flush (sync)")
                    flushForRebuffer()
                } else {
                    // Direct mode: soft flush, keep AudioTrack (hardware buffer bridges gap)
                    Log.d(TAG, "Reconnect: DISCONTINUITY(${gap / 1000}ms), buf=${bufMs}ms, soft flush")
                    frameQueue.clear()
                    frameQueueBytes.set(0)
                    lastEnqueuedTimestampUs = 0L
                    estimatedFrameDurationUs = 20_000L
                    presentationTimeUs = 0L
                    lateDropCount = 0L
                    playbackStarted = false
                    requiredSyncBufferMs = recoveryBufferMs()
                    transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                }
            }
        }
    }

    private fun flushForRebuffer() {
        // 1. pause() FIRST: Android docs guarantee that a blocking write() on
        //    another thread returns a short count when pause() is called.
        //    This breaks the playback thread out of track.write() immediately.
        // 2. Then mute + set flags so no further writes happen.
        // Suppress routing callbacks: pause+flush can trigger spurious route change
        // that would reset acoustic calibration to 0.
        withRoutingSuppressed {
            audioTrack?.pause()
            playbackStarted = false
            syncMuted = isSyncMode
            audioTrack?.setVolume(0f)
            Log.d(
                DBG,
                "flushForRebuffer codec=$activeCodec sync=$syncState " +
                    "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
            )
            hardBoundaryPending = true
            streamGeneration++
            syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
            clockPreciseForCorrections = false

            applyPlaybackRate(1.0f)
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            synchronized(codecLock) {
                audioTrack?.flush()
                codec?.flush()
            }
            frameQueue.clear()
            frameQueueBytes.set(0)
            lastEnqueuedTimestampUs = 0L
            estimatedFrameDurationUs = 20_000L
            presentationTimeUs = 0L

            enqueueLateDropGraceUntilUs = 0L
            lateDropCount = 0L
            oversizedDropCount = 0L
            consecutiveDecodeFailures = 0
            discardUntilTimestampUs = 0L
            dacValidator.resetTimeline(audioTrack)
            if (measuredOutputLatencyUs > 50_000L) {
                // BT: pipeline latency can shift after pause+flush.
                // Force full re-measurement so pre-cal and startup median
                // get fresh values instead of keeping stale EMA.
                latencyModel.resetMeasurementForSeek()
                dacValidator.clearCalibrations()
            }
            pendingContinuityCheck = false
            forceDiscontinuityUntilMs = 0L
            forceDiscontinuityReason = ""
            gaplessGraceUntilMs = 0L
            resetSyncState()
        }
    }

    private fun stopPlaybackThreadForHardBoundary() {
        val threadToJoin = synchronized(playbackThreadLock) {
            if (playbackThread == null) return
            playbackGeneration++
            playbackActive = false
            playbackStarted = false
            playbackThread?.interrupt()
            playbackThread.also { playbackThread = null }
        }
        if (threadToJoin != Thread.currentThread()) {
            try { threadToJoin?.join(1000) } catch (_: Exception) {}
        }
        Log.d(DBG, "hard boundary playback thread stopped codec=$activeCodec")
    }

    private fun restartPlaybackThreadForConfiguredTrack() {
        playbackActive = true
        audioTrack?.let { startPlaybackThread(it) }
    }

    /**
     * Two-stage starvation: at 1s silence transition to HOLDOVER (AudioTrack hardware buffer
     * keeps playing decoded PCM). At 2s silence fully rebuffer (pause + flush track).
     */
    private fun checkStarvation(track: AudioTrack) {
        if (!playbackStarted) return
        if (syncState != SyncState.SYNCHRONIZED && syncState != SyncState.HOLDOVER_PLAYING_FROM_BUFFER) return
        if (!frameQueue.isEmpty()) return
        // Extended grace during gapless track transitions (server may take >1s)
        if (System.currentTimeMillis() < gaplessGraceUntilMs) return
        // Grace after fresh sync in group mode: server may still be filling buffer
        if (playbackStartedAtMs > 0 && System.currentTimeMillis() - playbackStartedAtMs < 5000) return
        val silenceMs = System.currentTimeMillis() - lastFrameReceivedMs
        if (silenceMs <= 1000) return

        if (syncState == SyncState.SYNCHRONIZED) {
            // Stage 1: transition to holdover, let hardware buffer drain naturally
            Log.d(DBG, "starvation stage1 codec=$activeCodec silence=${silenceMs}ms, entering holdover")
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
        }

        if (silenceMs > 2000) {
            // Stage 2: hardware buffer likely exhausted, full rebuffer
            Log.d(DBG, "starvation stage2 codec=$activeCodec silence=${silenceMs}ms, rebuffering")
            pendingAudioTrackFlush = false // cancel deferred flush, startup path handles it
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            playbackStarted = false
            synchronized(codecLock) {
                try { track.setVolume(0f) } catch (_: Exception) {} // mute before flush
                try { track.pause(); track.flush() } catch (_: Exception) {}
                try { codec?.flush() } catch (_: Exception) {}
            }
            presentationTimeUs = 0L

        }
    }

    // ── Drift correction ──

    /**
     * Shift anchor so the sync engine treats the new static delay as the correction
     * target (gradual correction via samples).
     *
     * Positive delta (user added delay): we want audio to play LATER. To make the
     * engine insert silence/samples and actually delay playback, we need syncError
     * to become negative ("too fast"). Moving anchorLocalUs LATER shrinks actualElapsed
     * and yields the needed negative error, triggering sample insertion.
     *
     * Negative delta (advance): symmetric, anchor moves earlier → positive error →
     * sample removal → audio speeds up to advance.
     */
    override fun shiftAnchorForDelayChange(deltaMs: Int) {
        if (anchorLocalUs == 0L) return
        anchorLocalUs += deltaMs.toLong() * 1000L
        smoothedSyncErrorMs = 0.0
        pendingSampleCorrection = 0
        pendingSampleCount = 1
        Log.d(TAG, "Anchor shifted by ${deltaMs}ms for delay change")
    }

    private fun resetSyncState() {
        smoothedSyncErrorMs = 0.0
        startupOffsetMs = 0.0
        resyncCount = 0
        pendingSampleCorrection = 0
        pendingSampleCount = 1
        playbackStartedAtMs = 0L
        clockWaitStartMs = 0L
        anchorServerTimestampUs = 0L
        anchorLocalUs = 0L
        anchorLocalEquivalentUs = 0L
        latencyModel.resetForBoundary()
    }

    /**
     * Anchor-based sync error: compare elapsed wall-clock time vs expected
     * server time elapsed. Positive = playing too slow, negative = too fast.
     */
    private fun computeSyncErrorMs(serverTimestampUs: Long): Double {
        val sync = clockSynchronizer ?: return 0.0
        if (anchorLocalEquivalentUs == 0L || anchorLocalUs == 0L) return 0.0
        val expectedElapsedUs = sync.serverToLocalUs(serverTimestampUs) - anchorLocalEquivalentUs
        val actualElapsedUs = nowLocalUs() - anchorLocalUs
        return (actualElapsedUs - expectedElapsedUs).toDouble() / 1000.0
    }

    /**
     * Periodic sync check per sendspin spec.
     * Two actions only: sample correction (small errors) or hard resync (large errors).
     * Kalman filter handles clock offset/drift; this handles AudioTrack pipeline drift.
     */
    private fun checkSync(serverTimestampUs: Long, track: AudioTrack) {
        // Always emit current state for UI graph (even when not correcting)
        val sync = clockSynchronizer
        if (sync != null && sync.isSynced()) {
            val displayError = startupOffsetMs + smoothedSyncErrorMs
            onSyncSample?.invoke(
                displayError.toFloat(),
                (measuredOutputLatencyUs / 1000f),
                (sync.errorUs() / 1000f),
                dacValidator.absoluteErrorMs(0)?.toFloat()
            )
        }
        if (syncState != SyncState.SYNCHRONIZED) return
        if (sync == null || !sync.isSynced()) return

        val now = System.currentTimeMillis()
        val dacWarmed = playbackStartedAtMs > 0 && now - playbackStartedAtMs >= DAC_WARMUP_MS

        // Early unmute for low-latency routes: startup alignment is enough.
        // BT-like outputs can shift their DAC/output baseline after seek, so keep them
        // muted until the post-startup anchor is established below.
        if (syncMuted) {
            val absTotal = kotlin.math.abs(startupOffsetMs + smoothedSyncErrorMs)
            if (!isBtLikeOutput() && absTotal < 20.0) {
                startSyncFadeIn(absTotal, "startup")
            }
        }

        // Grace period after startup/track change (buffer fill transient)
        if (playbackStartedAtMs > 0 && now - playbackStartedAtMs < SYNC_STARTUP_GRACE_MS) return
        // Continuation grace: suppress correction during re-lock after soft stream/start
        if (continuationGraceUntilMs > 0 && now < continuationGraceUntilMs) return

        // "Play first, correct later": defer corrections until clock is precise
        if (!clockPreciseForCorrections) {
            val filterError = sync.errorUs()
            val playbackDurationMs = if (playbackStartedAtMs > 0) now - playbackStartedAtMs else 0L
            val timeoutElapsed = playbackDurationMs > CLOCK_PRECISION_TIMEOUT_MS
            if (filterError <= CLOCK_PRECISION_THRESHOLD_US) {
                clockPreciseForCorrections = true
                // Fresh anchor from precise clock, discard imprecise startup offset
                startupOffsetMs = 0.0
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                Log.d(TAG, "Clock precise, fresh anchor + corrections enabled (filterErr=${filterError}us, after ${playbackDurationMs}ms)")
            } else if (timeoutElapsed) {
                clockPreciseForCorrections = true
                startupOffsetMs = 0.0
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                Log.d(TAG, "Clock precision timeout, fresh anchor + corrections enabled (filterErr=${filterError}us)")
            } else {
                return
            }
        }

        // Set anchor after grace period
        if (anchorServerTimestampUs == 0L) {
            anchorServerTimestampUs = serverTimestampUs
            anchorLocalUs = nowLocalUs()
            anchorLocalEquivalentUs = sync.serverToLocalUs(serverTimestampUs)
            Log.d(TAG, "Sync anchor set, filterError=${sync.errorUs()}us")
            return
        }

        // Anchor-based error (primary: absolute position)
        val anchorErrorMs = computeSyncErrorMs(serverTimestampUs)
        // DAC drift: diagnostic only, not used for correction.
        // With acoustic calibration active, anchor + calibration handle compensation.
        // DAC raw is too noisy (±20ms jumps, baseline shifts after seek) to feed
        // into the correction loop without risking audible artifacts.
        val dacDriftUs = if (dacWarmed) {
            dacValidator.measureDriftUs(track, sync, activeSampleRate, decodedFrameCount)
        } else {
            null
        }
        val rawErrorMs = anchorErrorMs

        // EMA smoothing
        smoothedSyncErrorMs = if (smoothedSyncErrorMs == 0.0) {
            rawErrorMs
        } else {
            SYNC_ERROR_EMA_ALPHA * rawErrorMs + (1 - SYNC_ERROR_EMA_ALPHA) * smoothedSyncErrorMs
        }

        // DAC divergence validator: detect persistent anchor-vs-DAC disagreement
        val dacAbsMs = if (dacWarmed) dacValidator.absoluteErrorMs(DAC_DIVERGENCE_MATURITY) else null
        if (dacAbsMs != null) {
            val divergence = dacAbsMs - anchorErrorMs
            dacValidator.divergenceEma = DAC_DIVERGENCE_EMA_ALPHA * divergence + (1 - DAC_DIVERGENCE_EMA_ALPHA) * dacValidator.divergenceEma
            val sign = if (dacValidator.divergenceEma > 1.0) 1 else if (dacValidator.divergenceEma < -1.0) -1 else 0
            if (sign != 0 && sign == dacValidator.divergenceLastSign) {
                dacValidator.divergenceSameSignCount++
            } else {
                dacValidator.divergenceSameSignCount = if (sign != 0) 1 else 0
                dacValidator.divergenceLastSign = sign
            }
            // Re-seat anchor if DAC consistently disagrees, with safety gates.
            // Disabled for now: logs show raw DAC absolute bias on phone speaker
            // (30-140ms) while anchor error stays near zero.
            if (DAC_DIVERGENCE_RESEAT_ENABLED
                && measuredOutputLatencyUs <= 50_000L
                && kotlin.math.abs(dacValidator.divergenceEma) > DAC_DIVERGENCE_THRESHOLD_MS
                && dacValidator.divergenceSameSignCount >= DAC_DIVERGENCE_SIGN_COUNT
                && now - dacValidator.lastAnchorReseatMs > DAC_RESEAT_COOLDOWN_MS
                && clockPreciseForCorrections
                && measuredOutputLatencyUs > 0
                && continuationGraceUntilMs < now
                && now - latencyModel.outputRouteChangedAtMs > 5000
                && syncState == SyncState.SYNCHRONIZED
            ) {
                Log.d(TAG, "Anchor re-seat: DAC divergence=${"%.1f".format(dacValidator.divergenceEma)}ms " +
                    "sameSign=${dacValidator.divergenceSameSignCount} anchor=${"%.1f".format(anchorErrorMs)}ms dacAbs=${"%.1f".format(dacAbsMs)}ms")
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                startupOffsetMs = 0.0
                dacValidator.divergenceEma = 0.0
                dacValidator.divergenceSameSignCount = 0
                dacValidator.lastAnchorReseatMs = now
                return
            }
        }

        // Log less frequently
        if (now - lastSyncLogMs > 2000) {
            lastSyncLogMs = now
            Log.d(TAG, "Sync: error=${"%.1f".format(smoothedSyncErrorMs)}ms " +
                "outLat=${measuredOutputLatencyUs / 1000}ms " +
                "raw=${"%.1f".format(rawErrorMs)}ms anchor=${"%.1f".format(anchorErrorMs)}ms " +
                "dacDrift=${dacDriftUs?.let { it / 1000 } ?: "n/a"} " +
                "dacDiv=${"%.1f".format(dacValidator.divergenceEma)}ms(${dacValidator.divergenceSameSignCount}) " +
                "resyncs=$resyncCount filterErr=${sync.errorUs()}us")
        }

        // Don't correct if Kalman hasn't settled
        if (sync.errorUs() > 15_000) return

        // Correction target: anchor-based + DAC drift
        val absoluteSyncMs = startupOffsetMs + smoothedSyncErrorMs
        val absTotal = kotlin.math.abs(absoluteSyncMs)

        // Fade in once DAC sync converges after hard boundary.
        // Volume ramp is applied per-write in decodeAndWrite() to avoid blocking the playback thread.
        if (syncMuted && absTotal < 20.0) {
            startSyncFadeIn(absTotal, "validated")
        }

        // Tier 4: Hard resync for large errors
        if (absTotal > SYNC_RESYNC_MS && now - lastResyncAtMs > SYNC_RESYNC_COOLDOWN_MS) {
            resyncCount++
            lastResyncAtMs = now
            applyPlaybackRate(1.0f)
            pendingSampleCorrection = 0
            Log.d(TAG, "Sync RESYNC: abs=${"%.1f".format(absoluteSyncMs)}ms (#$resyncCount)")
            flushForRebuffer()
            return
        }

        // Tier 3: Rate correction for medium errors (8-100ms), if device supports
        if (absTotal > SYNC_SAMPLE_CORRECTION_MS && rateCorrectionSupported) {
            val rateAdjust = if (absTotal > SYNC_RATE_GENTLE_MS) RATE_STRONG else RATE_GENTLE
            val targetRate = if (absoluteSyncMs > 0) 1.0f + rateAdjust else 1.0f - rateAdjust
            applyPlaybackRate(targetRate)
            pendingSampleCorrection = 0
            pendingSampleCount = 1
        }
        // Tier 2: Sample correction (all errors above deadband; also fallback for unsupported rate)
        else if (absTotal > SYNC_DEADBAND_MS) {
            applyPlaybackRate(1.0f)
            pendingSampleCorrection = if (absoluteSyncMs > 0) 1 else -1
            pendingSampleCount = sampleCountForError(absTotal)
        }
        // Tier 1: Deadband
        else {
            applyPlaybackRate(1.0f)
            pendingSampleCorrection = 0
            pendingSampleCount = 1
        }

    }

    @Volatile private var rateCorrectionSupported = true

    private fun applyPlaybackRate(rate: Float) {
        if (rate == currentPlaybackRate) return
        if (!rateCorrectionSupported && rate != 1.0f) return
        val prev = currentPlaybackRate
        currentPlaybackRate = rate
        val track = audioTrack ?: return
        try {
            track.playbackParams = android.media.PlaybackParams()
                .setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
                .setSpeed(rate)
                .setPitch(1.0f)
        } catch (e: Exception) {
            if (rateCorrectionSupported) {
                Log.w(TAG, "Rate correction not supported: ${e.message}, falling back to samples-only")
            }
            rateCorrectionSupported = false
            currentPlaybackRate = 1.0f
        }
    }


    // ── Sample correction for steady-state convergence ──

    @Volatile private var pendingSampleCorrection = 0  // +1 = remove samples, -1 = insert samples, 0 = none
    @Volatile private var pendingSampleCount = 1       // how many samples to correct per frame

    /**
     * Scale sample correction count with error magnitude.
     * At 48kHz, 1 sample/frame ≈ 0.02ms correction per 20ms frame.
     * 5ms error  -> 1 sample  -> converges in ~5s
     * 10ms error -> 2 samples -> converges in ~5s
     * 20ms error -> 4 samples -> converges in ~5s
     * 50ms error -> 8 samples -> converges in ~6s
     * Cap at 8 to keep artifacts inaudible (8 samples = 0.17ms gap in 960-sample frame).
     */
    private fun sampleCountForError(absErrorMs: Double): Int {
        // More aggressive while muted (artifacts inaudible); conservative when audible
        return if (syncMuted) {
            when {
                absErrorMs < 15.0  -> 4
                absErrorMs < 50.0  -> 16
                absErrorMs < 200.0 -> 32
                else               -> 48  // ~1ms/frame at 48kHz, converge 300ms in ~6s
            }
        } else {
            when {
                absErrorMs < 8.0  -> 1
                absErrorMs < 15.0 -> 2
                absErrorMs < 30.0 -> 4
                else              -> 8
            }
        }
    }

    private fun applySampleCorrection(pcm: ByteArray): ByteArray {
        val direction = pendingSampleCorrection
        if (direction == 0) return pcm
        val bytesPerSample = activeChannels * 2
        val totalSamples = pcm.size / bytesPerSample
        // Need headroom: at least 4x the correction count
        val count = pendingSampleCount.coerceAtMost(totalSamples / 4)
        if (count <= 0) return pcm
        return if (direction > 0) {
            removeSamples(pcm, bytesPerSample, count)
        } else {
            insertSamples(pcm, bytesPerSample, count)
        }
    }

    /** Read one 16-bit sample for a given channel at sample index. */
    private fun readSample16(pcm: ByteArray, sampleIdx: Int, channel: Int, channels: Int): Short {
        val byteIdx = (sampleIdx * channels + channel) * 2
        if (byteIdx + 1 >= pcm.size) return 0
        return ((pcm[byteIdx + 1].toInt() shl 8) or (pcm[byteIdx].toInt() and 0xFF)).toShort()
    }

    /** Write one 16-bit sample for a given channel at byte position. */
    private fun writeSample16(out: ByteArray, pos: Int, value: Short) {
        out[pos] = (value.toInt() and 0xFF).toByte()
        out[pos + 1] = (value.toInt() shr 8).toByte()
    }

    /** Interpolate between two samples (0.0 = a, 1.0 = b). */
    private fun lerp16(a: Short, b: Short, t: Float): Short =
        (a + ((b - a) * t)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun removeSamples(pcm: ByteArray, bytesPerSample: Int, count: Int): ByteArray {
        if (pcm.size < bytesPerSample * (count + 4)) return pcm
        val out = ByteArray(pcm.size - bytesPerSample * count)
        val totalSamples = pcm.size / bytesPerSample
        val step = totalSamples / (count + 1)
        var srcPos = 0
        var dstPos = 0
        var removed = 0
        for (i in 0 until totalSamples) {
            if (removed < count && i == step * (removed + 1)) {
                // Instead of hard skip, crossfade: blend previous output with next source sample
                if (dstPos >= bytesPerSample && srcPos + bytesPerSample < pcm.size) {
                    for (ch in 0 until activeChannels) {
                        val prev = readSample16(out, (dstPos / bytesPerSample) - 1, ch, activeChannels)
                        val next = readSample16(pcm, i + 1, ch, activeChannels)
                        val blended = lerp16(prev, next, 0.5f)
                        writeSample16(out, dstPos - bytesPerSample + ch * 2, blended)
                    }
                }
                srcPos += bytesPerSample
                removed++
            } else {
                System.arraycopy(pcm, srcPos, out, dstPos, bytesPerSample)
                srcPos += bytesPerSample
                dstPos += bytesPerSample
            }
        }
        return out
    }

    private fun insertSamples(pcm: ByteArray, bytesPerSample: Int, count: Int): ByteArray {
        if (pcm.size < bytesPerSample * 2) return pcm
        val out = ByteArray(pcm.size + bytesPerSample * count)
        val totalSamples = pcm.size / bytesPerSample
        val step = totalSamples / (count + 1)
        var srcPos = 0
        var dstPos = 0
        var inserted = 0
        for (i in 0 until totalSamples) {
            System.arraycopy(pcm, srcPos, out, dstPos, bytesPerSample)
            srcPos += bytesPerSample
            dstPos += bytesPerSample
            if (inserted < count && i == step * (inserted + 1)) {
                // Interpolate between current and next sample instead of raw duplicate
                for (ch in 0 until activeChannels) {
                    val curr = readSample16(pcm, i, ch, activeChannels)
                    val next = if (i + 1 < totalSamples) readSample16(pcm, i + 1, ch, activeChannels) else curr
                    val interp = lerp16(curr, next, 0.5f)
                    writeSample16(out, dstPos + ch * 2, interp)
                }
                dstPos += bytesPerSample
                inserted++
            }
        }
        return out
    }

    /**
     * Measure actual output pipeline latency via AudioTrack.getTimestamp().
     * Called periodically after track.write(). EMA smoothed like JS client.
     * framesWritten = total PCM frames submitted to AudioTrack.
     * framesPlayed = AudioTimestamp.framePosition (frames output to DAC).
     * Difference = frames in pipeline = pipeline latency.
     */
    /** Delegate latency measurement to OutputLatencyModel, handle results in engine. */
    private fun handleLatencyMeasurement(track: AudioTrack) {
        val result = latencyModel.measure(track, activeSampleRate)
        when (result) {
            OutputLatencyModel.MeasureResult.NeedsRealign -> softRealignForLatencyCorrection()
            OutputLatencyModel.MeasureResult.NoChange -> {
                // Check for one-shot latency drift correction
                val shiftUs = latencyModel.checkDriftCorrection(
                    isSyncMode, anchorLocalUs
                )
                if (shiftUs != null) {
                    anchorLocalUs -= shiftUs
                    smoothedSyncErrorMs = 0.0
                    pendingSampleCorrection = 0
                    pendingSampleCount = 1
                }
            }
        }
    }

    // ── Playback thread: decode + write (blocking pacing) ──

    private fun startPlaybackThread(track: AudioTrack) {
        synchronized(playbackThreadLock) {
            val existing = playbackThread
            if (existing?.isAlive == true) {
                Log.d(DBG, "startPlaybackThread skipped existingAlive=true codec=$activeCodec")
                return
            }
            val generation = playbackGeneration
            Log.d(
                DBG,
                "startPlaybackThread launch codec=$activeCodec playbackActive=$playbackActive " +
                    "started=$playbackStarted sync=$syncState buf=${bufferDurationMs()}ms"
            )
            playbackThread = Thread({
            try {
            while ((playbackActive || frameQueue.isNotEmpty()) && generation == playbackGeneration) {
                // Wait for enough encoded buffer before starting
                if (!playbackStarted || syncState == SyncState.SYNC_ERROR_REBUFFERING || syncState == SyncState.IDLE) {
                    if (awaitingStreamStartAfterDiscontinuity) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }
                    if (isSyncMode && dropLateHeadFramesForStartup()) {
                        continue
                    }
                    if (isStartupBufferReady()) {
                        if (generation != playbackGeneration || track !== audioTrack || !playbackActive) break
                        val startupConfigureGeneration = configureGeneration
                        // Pre-calibrate only when we do not have a route latency measurement.
                        // DAC timestamp stability is rebuilt from real audio writes and must not
                        // block next-track startup once route latency/acoustic calibration is known.
                        if (isSyncMode && !hasOutputLatencyMeasurement()) {
                            suppressRoutingCallbacks = true
                            try {
                                val silenceFrames = activeSampleRate / 20  // 50ms per write
                                val silenceBytes = silenceFrames * activeChannels * 2
                                val silence = ByteArray(silenceBytes)
                                track.setVolume(0f)
                                track.play()
                                val maxAttempts = 40  // 40 * 50ms = 2s max
                                val preCalTimestamp = android.media.AudioTimestamp()
                                val preCalSamples = mutableListOf<Long>()
                                for (attempt in 1..maxAttempts) {
                                    if (generation != playbackGeneration || !playbackActive) break
                                    track.write(silence, 0, silenceBytes)
                                    dacValidator.collectCalibration(track)
                                    if (track.getTimestamp(preCalTimestamp)) {
                                        val framesAtDac = preCalTimestamp.framePosition
                                        if (framesAtDac > 0) {
                                            val framesConsumed = track.playbackHeadPosition.toLong()
                                            val framesInPipeline = framesConsumed - framesAtDac
                                            if (framesInPipeline >= 0) {
                                                val tsAgeUs = (System.nanoTime() - preCalTimestamp.nanoTime) / 1000L
                                                val pipelineUs = framesInPipeline * 1_000_000L / activeSampleRate.toLong()
                                                val latencyUs = tsAgeUs + pipelineUs
                                                if (latencyUs in 1..500_000) {
                                                    preCalSamples.add(latencyUs)
                                                    if (preCalSamples.size >= 5) {
                                                        val sorted = preCalSamples.sorted()
                                                        val median = sorted[sorted.size / 2]
                                                        latencyModel.seedFromPreCal(median)
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                                }
                                track.pause()
                                track.flush()
                                dacValidator.frameBaseline = track.playbackHeadPosition.toLong()
                                dacValidator.clearCalibrations()
                                if (!hasOutputLatencyMeasurement()) {
                                    Log.d(TAG, "Pre-cal: no measurement after $maxAttempts attempts, proceeding")
                                }
                            } finally {
                                suppressRoutingCallbacks = false
                            }
                            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                            // Don't continue: fall through to precision wait + startup
                        }
                        // Bounded precision wait: use isReadyForPlaybackStart (count>=8, error<=5ms)
                        // to ensure the clock has enough fresh NTP samples for reliable alignment.
                        val precisionWaitMs = syncPrecisionWaitMs()
                        if (isSyncMode && precisionWaitMs > 0 && !clockPreciseForCorrections) {
                            val precise = clockSynchronizer?.isReadyForPlaybackStart() == true
                            val waitingMs = if (clockWaitStartMs > 0) {
                                System.currentTimeMillis() - clockWaitStartMs
                            } else {
                                clockWaitStartMs = System.currentTimeMillis()
                                0L
                            }
                            if (!precise && waitingMs < precisionWaitMs) {
                                dropLateHeadFramesForStartup()
                                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                                continue
                            }
                            clockWaitStartMs = 0L
                            val readyErr = clockSynchronizer?.errorUs() ?: -1
                            val readyCount = clockSynchronizer?.currentSampleCount() ?: 0
                            if (precise) {
                                Log.d(TAG, "Precision wait done: ready after ${waitingMs}ms (reason=$syncStartupReason samples=$readyCount err=${readyErr}us)")
                            } else {
                                Log.d(TAG, "Precision wait timeout: ${waitingMs}ms (reason=$syncStartupReason samples=$readyCount err=${readyErr}us)")
                            }
                        }
                        if (startupConfigureGeneration != configureGeneration) continue
                        if (dropLateHeadFramesForStartup()) {
                            continue
                        }
                        if (startupConfigureGeneration != configureGeneration) continue
                        if (!isStartupBufferReady()) continue
                        if (!playbackStarted) {
                            var firstFrame = frameQueue.peek() ?: continue
                            val filterErr = clockSynchronizer?.errorUs() ?: Long.MAX_VALUE
                            val clockPrecise = isSyncMode && filterErr <= CLOCK_PRECISION_THRESHOLD_US
                            if (clockPrecise) {
                                // Clock is precise: align to server timeline.
                                // Threshold includes acoustic extra so the surviving
                                // frame has enough buffering lead for the acoustic
                                // target (which is acousticExtra ms earlier).
                                // Extra margin so the surviving frame has comfortable
                                // lead after acoustic adjustment (avoids 0ms edge cases)
                                val staleThresholdMs = acousticExtraUs() / 1000L + 100L
                                var skipped = 0
                                while (frameQueue.size > 1) {
                                    val head = frameQueue.peek() ?: break
                                    if (leadToLocalNowMs(head.serverTimestampUs) >= staleThresholdMs) break
                                    frameQueue.poll()
                                    frameQueueBytes.addAndGet(-head.payload.size.toLong())
                                    skipped++
                                }
                                if (skipped > 0) {
                                    Log.d(TAG, "Startup: skipped $skipped stale frames, buf=${bufferDurationMs()}ms")
                                    if (!isStartupBufferReady()) continue
                                }
                                if (startupConfigureGeneration != configureGeneration) continue
                                firstFrame = frameQueue.peek() ?: continue
                                val targetUs = targetLocalPlayUs(firstFrame.serverTimestampUs)
                                val leadMs = (targetUs - nowLocalUs()) / 1000L
                                val waitUs = (targetUs - nowLocalUs()).coerceIn(0, 2_000_000)
                                val waitMs = waitUs / 1000L
                                if (waitMs > 15) {
                                    try { Thread.sleep(waitMs - 10) } catch (_: InterruptedException) { break }
                                }
                                val spinDeadline = nowLocalUs() + 50_000L
                                while (nowLocalUs() < targetUs && nowLocalUs() < spinDeadline) { /* spin */ }
                                if (startupConfigureGeneration != configureGeneration || track !== audioTrack || !playbackActive) continue
                                val overshootUs = nowLocalUs() - targetUs
                                startupOffsetMs = if (leadMs < 0) -leadMs.toDouble() else -(overshootUs / 1000.0)
                                val s2l = clockSynchronizer?.serverToLocalUs(firstFrame.serverTimestampUs) ?: firstFrame.serverTimestampUs
                                Log.d(TAG, "Startup align (precise): lead=${leadMs}ms " +
                                    "absOffset=${"%.1f".format(startupOffsetMs)}ms " +
                                    "filterErr=${clockSynchronizer?.errorUs() ?: -1}us " +
                                    "[s2l=${s2l / 1000}ms static=${staticDelayMs}ms " +
                                    "pipeline=${measuredOutputLatencyUs / 1000}ms " +
                                    "routeExtra=${routeAcousticExtraUs / 1000}ms " +
                                    "acousticExtra=${acousticExtraUs() / 1000}ms " +
                                    "totalComp=${latencyModel.totalCompensationUs() / 1000}ms " +
                                    "target=${targetUs / 1000}ms now=${nowLocalUs() / 1000}ms]")
                                clockPreciseForCorrections = true
                                latencyModel.startupAlignLatencyUs = measuredOutputLatencyUs
                                latencyModel.latencyDriftCorrected = false
                            } else if (isSyncMode) {
                                // Clock imprecise: just play, no alignment, no startup offset
                                startupOffsetMs = 0.0
                                latencyModel.startupAlignLatencyUs = measuredOutputLatencyUs
                                latencyModel.latencyDriftCorrected = false
                                Log.d(TAG, "Startup (imprecise clock): playing without alignment, " +
                                    "filterErr=${clockSynchronizer?.errorUs() ?: -1}us")
                            }
                            if (startupConfigureGeneration != configureGeneration ||
                                !isStartupBufferReady() ||
                                frameQueue.peek() == null
                            ) continue
                            Log.d(
                                DBG,
                                "playbackThread track.play codec=$activeCodec buf=${bufferDurationMs()}ms " +
                                    "required=${requiredSyncBufferMs}ms sync=$syncState"
                            )
                            track.setVolume(if (isMuted || syncMuted) 0f else currentVolume)
                            track.play()
                            playbackStarted = true
                            playbackStartedAtMs = System.currentTimeMillis()
                        }
                        transitionSyncState(SyncState.SYNCHRONIZED)

                        Log.d(TAG, "Synchronized (${frameQueueBytes.get() / 1000}KB, ~${bufferDurationMs()}ms, threshold=${requiredSyncBufferMs}ms)")
                    } else {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }
                }

                val frame = frameQueue.peek()
                if (frame != null) {
                    frameQueue.poll()
                    frameQueueBytes.addAndGet(-frame.payload.size.toLong())
                    if (frame.generation != acceptGeneration) continue
                    if (pendingAudioTrackFlush) {
                        // Reconnect alignment: drop frames already played by holdover.
                        // Compare against holdoverEndTimestampUs (exact, no clock heuristics).
                        if (holdoverEndTimestampUs > 0 && frame.serverTimestampUs < holdoverEndTimestampUs) {
                            logLateDrop(frame.serverTimestampUs, "reconnect-align")
                            continue
                        }
                        pendingAudioTrackFlush = false
                        holdoverEndTimestampUs = 0L
                        transitionSyncState(SyncState.SYNCHRONIZED)
                        val lead = leadToLocalNowMs(frame.serverTimestampUs)
                        Log.d(TAG, "Reconnect aligned: lead=${lead}ms, ts=${frame.serverTimestampUs}us, buf=${bufferDurationMs()}ms")
                    } else if (isSyncMode && shouldDropLateFrame(frame.serverTimestampUs)) {
                        logLateDrop(frame.serverTimestampUs, "playout")
                        continue
                    }
                    lastPlayedServerTimestampUs = frame.serverTimestampUs
                    if (!decodeAndWrite(frame, track, generation)) {
                        consecutiveDecodeFailures++
                        Log.d(
                            DBG,
                            "decode failure codec=$activeCodec failures=$consecutiveDecodeFailures " +
                                "buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
                        )
                        // Tolerate a few failures (stale frames after hot-swap).
                        // Only rebuffer after 3+ consecutive failures.
                        if (consecutiveDecodeFailures >= 3) {
                            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                            playbackStarted = false
                            synchronized(codecLock) {
                                try { track.pause(); track.flush() } catch (_: Exception) {}
                                try { codec?.flush() } catch (_: Exception) {}
                            }
                            presentationTimeUs = 0L

                        }
                        if (consecutiveDecodeFailures >= 8) {
                            Log.e(
                                DBG,
                                "decode failure threshold hit codec=$activeCodec failures=$consecutiveDecodeFailures, rebuilding pipeline"
                            )
                            rebuildPipelineAfterFailure()
                            break
                        }
                        continue
                    }
                    consecutiveDecodeFailures = 0
                    maybeLogFlacLowBuffer("post-write")
                    checkStarvation(track)
                } else if (!playbackActive) {
                    break
                } else {
                    checkStarvation(track)
                    if (syncState != SyncState.SYNC_ERROR_REBUFFERING) {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    }
                }
            }
            } catch (e: Exception) {
                Log.e(TAG, "Playback thread crashed: ${e::class.java.simpleName}: ${e.message}")
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                playbackStarted = false
            }
            }, "AudioPlayback").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private fun decodeAndWrite(frame: EncodedFrame, track: AudioTrack, generation: Long): Boolean {
        val encodedData = frame.payload
        if (activeCodec == "pcm") {
            if (generation != playbackGeneration || track !== audioTrack || !playbackActive || !playbackStarted) return false
            val pcm = if (activeBitDepth == 24) convertPcm24To16(encodedData) else encodedData
            val finalPcm = if (isSyncMode) applySampleCorrection(pcm) else pcm
            track.write(finalPcm, 0, finalPcm.size)
            applyFadeInStep(track)
            val framesWritten = finalPcm.size / (activeChannels * 2)
            // Track written-end timestamp (not frame-start) so DAC expected position
            // aligns with what was actually written. Without this, measureDriftUs()
            // sees a systematic ~20ms offset (one frame duration).
            // NOTE: framesWritten includes inserted/removed samples from applySampleCorrection().
            // This is intentional: the DAC timeline should match the corrected stream actually
            // written to AudioTrack, not the source stream. DAC drift validates the corrected
            // output, so embedding the correction in the expected timeline avoids the DAC signal
            // "chasing" the same correction the anchor loop is applying.
            val writeDurationUs = framesWritten.toLong() * 1_000_000L / activeSampleRate.toLong()
            dacValidator.onFrameWritten(frame.serverTimestampUs + writeDurationUs, framesWritten.toLong())
            handleLatencyMeasurement(track)
            if (isSyncMode) {
                dacValidator.collectCalibration(track)
            }
            decodedFrameCount++
            if (isSyncMode && decodedFrameCount % SYNC_CHECK_INTERVAL_FRAMES == 0) {
                checkSync(frame.serverTimestampUs, track)
            }
            return true
        }

        val mc = codec ?: return false
        try {
            synchronized(codecLock) {
                if (generation != playbackGeneration || mc !== codec || track !== audioTrack || !playbackActive) return false
                val inputIndex = mc.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    val inputBuffer = mc.getInputBuffer(inputIndex) ?: return false
                    inputBuffer.clear()
                    if (encodedData.size > inputBuffer.remaining()) {
                        oversizedDropCount++
                        if (oversizedDropCount <= 5 || oversizedDropCount % 100 == 0L) {
                            Log.w(
                                DBG,
                                "drop oversized frame codec=$activeCodec size=${encodedData.size} inputCap=${inputBuffer.remaining()}"
                            )
                        }
                        return true
                    }
                    inputBuffer.put(encodedData)
                    mc.queueInputBuffer(inputIndex, 0, encodedData.size, presentationTimeUs, 0)
                    presentationTimeUs += 20_000
                }
            }

            // Drain decoded PCM outside codecLock so codec flush/release on hot-swap
            // is not blocked by paced AudioTrack writes.
            val bufferInfo = MediaCodec.BufferInfo()
            var framesWrittenThisCall = 0L
            while (true) {
                val pcmToWrite: ByteArray? = synchronized(codecLock) {
                    if (generation != playbackGeneration || mc !== codec || track !== audioTrack || !playbackActive) return@synchronized null
                    val outputIndex = mc.dequeueOutputBuffer(bufferInfo, 1000)
                    when {
                        outputIndex >= 0 -> {
                            val pcm = if (bufferInfo.size > 0) {
                                val outputBuffer = mc.getOutputBuffer(outputIndex)
                                ByteArray(bufferInfo.size).also { out ->
                                    if (out.isNotEmpty() && outputBuffer != null) outputBuffer.get(out)
                                }
                            } else {
                                null
                            }
                            mc.releaseOutputBuffer(outputIndex, false)
                            pcm
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = mc.outputFormat
                            Log.d(TAG, "Output: ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                            ByteArray(0)
                        }
                        else -> null
                    }
                }

                when {
                    pcmToWrite == null -> break
                    pcmToWrite.isEmpty() -> continue
                    else -> {
                        if (generation != playbackGeneration || track !== audioTrack || mc !== codec || !playbackActive || !playbackStarted) break
                        val finalPcm = if (isSyncMode) applySampleCorrection(pcmToWrite) else pcmToWrite
                        track.write(finalPcm, 0, finalPcm.size) // blocks when AudioTrack full = pacing
                        applyFadeInStep(track)
                        val fwCodec = finalPcm.size / (activeChannels * 2)
                        framesWrittenThisCall += fwCodec
                        // Written-end timestamp: see PCM path comment about sample correction embedding
                        val endTsUs = frame.serverTimestampUs +
                            (framesWrittenThisCall * 1_000_000L / activeSampleRate.toLong())
                        dacValidator.onFrameWritten(endTsUs, fwCodec.toLong())
                        handleLatencyMeasurement(track)
                        if (isSyncMode) {
                            dacValidator.collectCalibration(track)
                        }
                        decodedFrameCount++
                        if (isSyncMode && decodedFrameCount % SYNC_CHECK_INTERVAL_FRAMES == 0) {
                            checkSync(frame.serverTimestampUs, track)
                        }
                    }
                }
            }
            return true
        } catch (_: BufferOverflowException) {
            oversizedDropCount++
            if (oversizedDropCount <= 5 || oversizedDropCount % 100 == 0L) {
                Log.w(
                    DBG,
                    "drop oversized frame codec=$activeCodec size=${encodedData.size} exception=BufferOverflowException"
                )
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e::class.java.simpleName}: ${e.message}")
            return false
        }
    }

    // ── Codec factories ──

    private fun createCodec(codecName: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec? {
        return when (codecName) {
            "opus" -> createOpusDecoder(sampleRate, channels, codecHeader)
            "flac" -> createFlacDecoder(sampleRate, channels, bitDepth, codecHeader)
            "pcm" -> null
            else -> {
                Log.w(TAG, "Unknown codec $codecName, falling back to opus")
                createOpusDecoder(sampleRate, channels, codecHeader)
            }
        }
    }

    private fun recreateCodecForStream(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
    ) {
        if (codecName == "pcm") {
            synchronized(codecLock) { codec = null }
            return
        }
        try {
            val newCodec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)
            val oldCodec = synchronized(codecLock) {
                val old = codec
                codec = newCodec
                old
            }
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
            Log.d(TAG, "Codec recreated for NEW_STREAM boundary: codec=$codecName")
        } catch (e: Exception) {
            Log.e(TAG, "Codec recreate failed, falling back to flush: ${e::class.java.simpleName}: ${e.message}")
            synchronized(codecLock) {
                try { codec?.flush() } catch (_: Exception) {}
            }
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OPUS_MAX_INPUT_SIZE)
        val csd0 = if (codecHeader != null) {
            try { Base64.decode(codecHeader, Base64.DEFAULT) }
            catch (_: Exception) { createOpusHeader(channels, sampleRate) }
        } else createOpusHeader(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(preSkipNs); rewind() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); rewind() })
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply { configure(format, null, null, 0); start() }
    }

    private fun createFlacDecoder(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MAX_INPUT_SIZE)
        format.setInteger("bit-depth", bitDepth)
        if (codecHeader != null) {
            try { format.setByteBuffer("csd-0", ByteBuffer.wrap(Base64.decode(codecHeader, Base64.DEFAULT))) }
            catch (_: Exception) {}
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply { configure(format, null, null, 0); start() }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray {
        return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray()); put(1); put(channels.toByte())
            putShort(3840.toShort()); putInt(sampleRate); putShort(0); put(0)
        }.array()
    }

    private fun convertPcm24To16(data: ByteArray): ByteArray {
        val out = ByteArray(data.size / 3 * 2)
        for (i in 0 until data.size / 3) { out[i * 2] = data[i * 3 + 1]; out[i * 2 + 1] = data[i * 3 + 2] }
        return out
    }

    private fun parseTimestampUs(data: ByteArray): Long {
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (data[i].toLong() and 0xFF)
        return ts
    }

    // ── Controls ──

    override fun setPaused(paused: Boolean) {
        if (!configured) return
        if (paused) {
            val threadToJoin = synchronized(playbackThreadLock) {
                if (!playbackActive && playbackThread == null) return
                playbackGeneration++
                playbackActive = false
                playbackThread?.interrupt()
                playbackThread.also { playbackThread = null }
            }
            if (threadToJoin != Thread.currentThread()) {
                try { threadToJoin?.join(1000) } catch (_: Exception) {}
            }
            frameQueue.clear(); frameQueueBytes.set(0)
            try { audioTrack?.stop(); audioTrack?.flush() } catch (_: Exception) {}
            playbackStarted = false
            // Reset drift anchor so a fresh one is set after resume.
            // Old anchor is invalid: local time advanced during pause, server timestamps didn't.
            anchorServerTimestampUs = 0L
            anchorLocalUs = 0L
            smoothedSyncErrorMs = 0.0
    
        } else {
            playbackActive = true; playbackStarted = false
    
            audioTrack?.let { startPlaybackThread(it) }
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(if (isMuted || syncMuted) 0f else currentVolume)
    }
    override fun setMuted(muted: Boolean) {
        isMuted = muted
        audioTrack?.setVolume(if (muted || syncMuted) 0f else currentVolume)
    }

    override fun clearBuffer() {
        hardBoundaryPending = true
        streamGeneration++
        configureGeneration++
        syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
        clockPreciseForCorrections = false

        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
        withRoutingSuppressed {
            if (playbackActive) {
                synchronized(codecLock) {
                    audioTrack?.setVolume(0f)
                    audioTrack?.pause()
                    audioTrack?.flush()
                    codec?.flush()
                }
            } else {
                synchronized(codecLock) {
                    codec?.flush()
                }
            }
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            frameQueue.clear()
            frameQueueBytes.set(0)
            playbackStarted = false
            lastEnqueuedTimestampUs = 0L
            discardUntilTimestampUs = 0L
            pendingContinuityCheck = false
            forceDiscontinuityUntilMs = 0L
            forceDiscontinuityReason = ""
            requiredSyncBufferMs = defaultBufferMs()
            estimatedFrameDurationUs = 20_000L
            presentationTimeUs = 0L

            enqueueLateDropGraceUntilUs = 0L
            lateDropCount = 0L
            dacValidator.resetTimeline(audioTrack)
            if (measuredOutputLatencyUs > 50_000L) {
                dacValidator.clearCalibrations()
            }
            resetSyncState()

            Log.d(TAG, "Buffer cleared")
        }
    }

    override fun onStreamEnd() {
        Log.d(
            DBG,
            "onStreamEnd codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
        )
        clockPreciseForCorrections = false
        if (isSyncMode) syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
        // Full stop + IDLE (matches main branch behavior).
        hardBoundaryPending = true
        awaitingStreamStartAfterDiscontinuity = true
        streamGeneration++
        configureGeneration++
        withRoutingSuppressed {
            synchronized(codecLock) {
                try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
                try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
                try { codec?.flush() } catch (_: Exception) {}
            }
        }
        transitionSyncState(SyncState.IDLE)
        frameQueue.clear()
        frameQueueBytes.set(0)
        playbackStarted = false
        lastEnqueuedTimestampUs = 0L
        discardUntilTimestampUs = 0L
        pendingContinuityCheck = false
        forceDiscontinuityUntilMs = 0L
        forceDiscontinuityReason = ""
        requiredSyncBufferMs = defaultBufferMs()
        estimatedFrameDurationUs = 20_000L
        presentationTimeUs = 0L

        enqueueLateDropGraceUntilUs = 0L
        lateDropCount = 0L
        resetSyncState()
        Log.d(TAG, "Stream ended, buffer cleared")
        stopPlaybackThreadForHardBoundary()
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason, flushing")
        awaitingStreamStartAfterDiscontinuity = true
        flushForRebuffer()
        stopPlaybackThreadForHardBoundary()
    }

    override fun onTransportFailure() {
        val bufMs = bufferDurationMs()
        Log.d(
            DBG,
            "onTransportFailure codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "buf=${bufMs}ms queueBytes=${frameQueueBytes.get()}"
        )
        if (bufMs <= HOLDOVER_MIN_BUFFER_MS) {
            requiredSyncBufferMs = defaultBufferMs()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            Log.d(TAG, "Transport failure, insufficient holdover (${bufMs}ms), recovery threshold=${requiredSyncBufferMs}ms")
        } else {
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            Log.d(TAG, "Transport failure, holdover active (${bufMs}ms)")
        }
    }

    override fun getRoutedDeviceType(): Int? = audioTrack?.routedDevice?.type
    override fun getRoutedDeviceProductName(): String? = audioTrack?.routedDevice?.productName?.toString()

    override fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route change: reason=$reason latency=${measuredOutputLatencyUs / 1000}ms acoustic=${routeAcousticExtraUs / 1000}ms sync=$syncState started=$playbackStarted")
        // Reset route-sensitive calibration (new device = new latency).
        // routeAcousticExtraUs is NOT reset here: controller sets it atomically
        // before calling onOutputRouteChanged to prevent relock with correction=0.
        latencyModel.onRouteChanged()
        clockPreciseForCorrections = false
        applyPlaybackRate(1.0f)
        // Route change: audio content unchanged, only output device changed.
        // Flush AudioTrack (new hardware pipeline) but KEEP frame queue intact.
        // Queue head is at current playback position; flushing it would cause server
        // to refill from its send-ahead position (25-30s in future) triggering RESYNC spiral.
        if (playbackStarted) {
            syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
            syncMuted = isSyncMode  // mute during route-change relock, fade-in after convergence
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            synchronized(codecLock) {
                playbackStarted = false
                audioTrack?.setVolume(0f)
                audioTrack?.pause()
                audioTrack?.flush()
                // Keep codec: decoded PCM is device-independent
                // Reset DAC counters inside lock: new hardware pipeline, prevent race with playback thread
                dacValidator.resetTimeline(audioTrack)
            }
            // Keep frameQueue/frameQueueBytes/lastEnqueuedTimestampUs intact
            presentationTimeUs = 0L
            anchorServerTimestampUs = 0L
            anchorLocalUs = 0L
            anchorLocalEquivalentUs = 0L
            smoothedSyncErrorMs = 0.0
            startupOffsetMs = 0.0
            pendingSampleCorrection = 0
            pendingSampleCount = 1
            resyncCount = 0
            lastResyncAtMs = 0L
            dacValidator.clearCalibrations()  // old route's DAC-to-system-time mapping is invalid
            requiredSyncBufferMs = if (isSyncMode) SYNC_RELOCK_BUFFER_MS else recoveryBufferMs()
            Log.d(TAG, "Route change: flushed AudioTrack, kept queue (${bufferDurationMs()}ms), awaiting re-sync")
        }
    }

    /**
     * Light re-align: flush AudioTrack, keep queue, re-enter startup with current measured latency.
     * Called when steady-state measurement reveals the output latency used at startup was wrong
     * (e.g., BT pipeline latency became visible only after real audio started flowing).
     */
    private fun softRealignForLatencyCorrection() {
        if (!playbackStarted || !isSyncMode) return
        Log.d(TAG, "Latency re-align: outLat=${measuredOutputLatencyUs / 1000}ms buf=${bufferDurationMs()}ms")
        syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        synchronized(codecLock) {
            playbackStarted = false
            audioTrack?.setVolume(0f)
            audioTrack?.pause()
            audioTrack?.flush()
            dacValidator.resetTimeline(audioTrack)
        }
        // Keep frameQueue intact, keep measuredOutputLatencyUs (it's now correct)
        presentationTimeUs = 0L
        anchorServerTimestampUs = 0L
        anchorLocalUs = 0L
        anchorLocalEquivalentUs = 0L
        smoothedSyncErrorMs = 0.0
        startupOffsetMs = 0.0
        pendingSampleCorrection = 0
        pendingSampleCount = 1
        resyncCount = 0
        lastResyncAtMs = 0L
        dacValidator.clearCalibrations()
        clockPreciseForCorrections = false
        latencyModel.outputRouteChangedAtMs = 0L  // prevent re-trigger
        requiredSyncBufferMs = SYNC_CONTINUATION_BUFFER_MS  // low threshold, queue has data
    }

    override fun release() { release_internal() }

    /** Internal recovery after decode failures. Rebuilds codec/audio preserving original protocol semantic + codec header. */
    private fun rebuildPipelineAfterFailure() {
        val savedCodec = activeCodec
        val savedRate = activeSampleRate
        val savedChannels = activeChannels
        val savedBitDepth = activeBitDepth
        val savedHeader = lastCodecHeader
        val savedStartType = lastProtocolStartType
        Log.d(TAG, "rebuildPipelineAfterFailure codec=$savedCodec, semantic=$savedStartType, hasHeader=${savedHeader != null}")
        release_internal()
        configure(savedCodec, savedRate, savedChannels, savedBitDepth, savedHeader, savedStartType)
    }

    private fun release_internal() {
        Log.d(
            DBG,
            "release_internal codec=$activeCodec configured=$configured sync=$syncState " +
                "started=$playbackStarted playbackActive=$playbackActive buf=${bufferDurationMs()}ms"
        )
        playbackGeneration++
        playbackActive = false; configured = false; playbackStarted = false
        awaitingStreamStartAfterDiscontinuity = false
        val oldTrack = audioTrack
        val oldCodec = codec
        audioTrack = null
        codec = null
        val threadToJoin = synchronized(playbackThreadLock) {
            playbackThread?.interrupt()
            playbackThread.also { playbackThread = null }
        }
        try { oldTrack?.pause(); oldTrack?.flush() } catch (_: Exception) {}
        if (threadToJoin != Thread.currentThread()) {
            try { threadToJoin?.join(1000) } catch (_: Exception) {}
        }
        frameQueue.clear(); frameQueueBytes.set(0)
        enqueueLateDropGraceUntilUs = 0L
        lateDropCount = 0L
        resetSyncState()

        synchronized(codecLock) {
            try { oldTrack?.release() } catch (_: Exception) {}
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
        }
    }
}
