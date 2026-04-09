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
        private const val SYNC_RELOCK_BUFFER_MS = 1000L
        private const val SYNC_CONTINUATION_BUFFER_MS = 400L
        // Precision wait caps (bounded, not infinite)
        private const val SYNC_NEW_STREAM_PRECISION_WAIT_MS = 1200L
        private const val SYNC_RELOCK_PRECISION_WAIT_MS = 1500L

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
    @Volatile override var correctionMode = CorrectionMode.DIRECT; private set
    private val isSyncMode get() = correctionMode == CorrectionMode.SYNC

    // Stream ownership (epoch invalidation, both modes)
    @Volatile private var streamGeneration = 0L
    @Volatile private var acceptGeneration = 0L
    @Volatile private var generationDropCount = 0L
    @Volatile private var hardBoundaryPending = true

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
    // "Play first, correct later": defer corrections until clock converges
    @Volatile private var clockPreciseForCorrections = false
    // Sync startup reason: controls buffer threshold and precision wait
    private enum class SyncStartupReason { NEW_STREAM, RELOCK_AFTER_SEEK, SOFT_CONTINUATION }
    @Volatile private var syncStartupReason = SyncStartupReason.NEW_STREAM
    // Rate correction state
    @Volatile private var currentPlaybackRate = 1.0f
    // Output route change tracking
    @Volatile private var outputRouteChangedAtMs = 0L
    // Sync-mute: mute audio until DAC sync error converges after hard boundary
    @Volatile private var syncMuted = false

    // DAC calibration: maps hardware DAC clock to system monotonic clock
    private data class DacCalibration(val dacTimeUs: Long, val loopTimeUs: Long)
    private val dacCalibrations = ArrayDeque<DacCalibration>()
    private var lastDacCalibrationTimeUs = 0L
    private var dacTimestampsStable = false
    private var consecutiveValidTimestamps = 0
    // Server timeline tracking for DAC-position-based sync
    @Volatile private var lastWrittenServerTimestampUs = 0L
    private val totalFramesWritten = AtomicLong(0)
    // Baseline offset: AudioTrack.playbackHeadPosition at last reset (flush doesn't reset it)
    private var dacFrameBaseline = 0L
    // DAC drift tracking: previous raw error for delta-based drift detection
    private var dacPrevRawErrorUs = Long.MIN_VALUE

    // Device bias calibration: learned steady-state offset
    @Volatile var deviceBiasCorrectionUs = 0L; private set
    private var biasAccumMs = 0.0
    private var biasSampleCount = 0
    private var biasWindowStartMs = 0L
    private var lastBiasUpdateMs = 0L
    var onDeviceBiasMeasured: ((Long) -> Unit)? = null

    fun seedDeviceBias(persistedUs: Long) {
        // No longer seeded globally: bias is route-specific, learned per session
        Log.d(TAG, "Device bias seed ignored: ${persistedUs}us (route-specific)")
    }

    private fun resetBiasLearningWindow() {
        biasWindowStartMs = 0L
        biasSampleCount = 0
        biasAccumMs = 0.0
        biasSumSquaresMs = 0.0
        biasSameSignCount = 0
    }

    // Callbacks
    override var onOutputLatencyMeasured: ((Long) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float) -> Unit)? = null

    // Continuation grace: after soft stream/start, suppress correction briefly
    @Volatile private var continuationGraceUntilMs = 0L

    // Sync correction state
    @Volatile var smoothedSyncErrorMs = 0.0; private set  // EMA-filtered sync error (DAC-based)
    private var startupOffsetMs = 0.0                // absolute offset captured at startup (positive = late)
    private var lastSyncLogMs = 0L
    private var resyncCount = 0
    private var playbackStartedAtMs = 0L             // wall clock when playback started
    private var clockWaitStartMs = 0L                // wall clock when we started waiting for clock convergence
    private var lastResyncAtMs = 0L                  // wall clock of last hard resync
    private var anchorServerTimestampUs = 0L          // server ts of first played frame after grace
    private var anchorLocalUs = 0L                    // local time when anchor was set
    private var anchorLocalEquivalentUs = 0L          // cached serverToLocalUs(anchorTs) at anchor time

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
        resetBiasLearningWindow()
        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
        Log.d(TAG, "CorrectionMode: $old -> $mode")
        // Reset sync-specific state, keep buffer intact
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
        if (mode == CorrectionMode.SYNC && playbackStarted) {
            playbackStartedAtMs = System.currentTimeMillis()
            continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
        }
    }

    override fun setCellularTransport(cellular: Boolean) {
        isCellular = cellular
    }

    private fun nowLocalUs(): Long = System.nanoTime() / 1000L

    @Volatile private var hwBufferLatencyUs = 0L

    // Output latency measurement (like JS client's outputLatency EMA + persist)
    @Volatile override var measuredOutputLatencyUs = 0L  // runtime-measured pipeline latency
        private set
    private var outputLatencyMeasureCount = 0
    private var outputLatencyPersistCount = 0
    private val outputLatencyTimestamp = android.media.AudioTimestamp()

    /** Persisted latency is no longer seeded (route-blind). Median bootstrap measures within ~1s. */
    override fun seedOutputLatency(persistedUs: Long) {
        // Intentionally not seeding: different route may have different latency.
        // Median bootstrap provides accurate value within ~1s of playback start.
        if (persistedUs > 0) {
            Log.d(TAG, "Output latency seed ignored: ${persistedUs / 1000}ms (median bootstrap preferred)")
        }
    }

    /**
     * Target local time for this audio chunk.
     * Used for startup alignment only. DAC-based sync handles steady-state corrections.
     * outputLatency is rough estimate for initial alignment; DAC measurement refines.
     */
    private fun targetLocalPlayUs(serverTimestampUs: Long): Long {
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
            ?: serverTimestampUs
        return localUs + (staticDelayMs.toLong() * 1000L) - measuredOutputLatencyUs
    }

    private fun leadToLocalNowMs(serverTimestampUs: Long): Long =
        (targetLocalPlayUs(serverTimestampUs) - nowLocalUs()) / 1000L

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
        resetBiasLearningWindow()
                applyPlaybackRate(1.0f)
                configureGeneration++
                lastEnqueuedTimestampUs = 0L
                frameQueue.clear()
                frameQueueBytes.set(0)
                synchronized(codecLock) {
                    try { codec?.flush() } catch (_: Exception) {}
                }
                presentationTimeUs = 0L
                lateDropCount = 0L
                dacFrameBaseline = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
                totalFramesWritten.set(0)
                lastWrittenServerTimestampUs = 0L
                dacPrevRawErrorUs = Long.MIN_VALUE
                // Keep DAC calibrations: same AudioTrack, hardware mapping still valid
                lastFrameReceivedMs = System.currentTimeMillis()
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                pendingSampleCorrection = 0
                pendingSampleCount = 1
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
                    syncStartupReason = SyncStartupReason.SOFT_CONTINUATION
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
                // Continuation grace: suppress correction during noisy re-lock period
                continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
                enqueueLateDropGraceUntilUs = nowLocalUs() + (2_000L * 1000L)
                if (playbackStarted) {
                    playbackStartedAtMs = System.currentTimeMillis()
                }
            }
            if (!playbackActive || playbackThread?.isAlive != true) {
                playbackActive = true
                audioTrack?.let { startPlaybackThread(it) }
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
            resetBiasLearningWindow()
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
                    syncStartupReason = SyncStartupReason.SOFT_CONTINUATION
                }
                playbackStarted = false
                requiredSyncBufferMs = defaultBufferMs()
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                Log.d(TAG, "configure semantic=CONTINUATION path=hot-swap codec=$codecName sync=$syncState buf=${bufferDurationMs()}ms")
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

        val createdAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
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
        audioTrack = createdAudioTrack
        configureGeneration++
        configured = true
        playbackActive = true
        playbackStarted = false
        frameCount = 0
        decodedFrameCount = 0
        outputLatencyMeasureCount = 0
        latencyPhaseStartup = true
        startupLatencySamples.clear()
        clearDacCalibrations()
        totalFramesWritten.set(0)
        lastWrittenServerTimestampUs = 0L
        dacFrameBaseline = 0L  // new AudioTrack, frame position starts from 0
        dacPrevRawErrorUs = Long.MIN_VALUE
        presentationTimeUs = 0L
        rateCorrectionSupported = true
        currentPlaybackRate = 1.0f

        if (isNewStream) {
            if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                syncStartupReason = SyncStartupReason.NEW_STREAM
            }
            clockPreciseForCorrections = false
        resetBiasLearningWindow()
            requiredSyncBufferMs = defaultBufferMs()
            continuationGraceUntilMs = 0L
        } else {
            if (syncStartupReason != SyncStartupReason.RELOCK_AFTER_SEEK) {
                syncStartupReason = SyncStartupReason.SOFT_CONTINUATION
            }
            // CONTINUATION rebuild: fast re-lock, not cold start
            requiredSyncBufferMs = defaultBufferMs()
            continuationGraceUntilMs = System.currentTimeMillis() + CONTINUATION_GRACE_MS
        }
        startPlaybackThread(createdAudioTrack)
        Log.d(TAG, "configure semantic=${if (isNewStream) "NEW_STREAM" else "CONTINUATION"} path=rebuild " +
            "codec=$codecName ${sampleRate}Hz threshold=${requiredSyncBufferMs}ms")
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
        syncMuted = isSyncMode  // mute until DAC sync converges
        Log.d(
            DBG,
            "flushForRebuffer codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
        )
        hardBoundaryPending = true
        streamGeneration++
        syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
        clockPreciseForCorrections = false
        resetBiasLearningWindow()
        applyPlaybackRate(1.0f)
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        synchronized(codecLock) {
            playbackStarted = false  // BEFORE lock release to prevent decode+write race
            audioTrack?.setVolume(0f)  // mute before flush to hide click/pop
            audioTrack?.pause()
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
        // Capture current AudioTrack position as baseline for DAC sync (flush doesn't reset it)
        dacFrameBaseline = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
        totalFramesWritten.set(0)
        lastWrittenServerTimestampUs = 0L
        dacPrevRawErrorUs = Long.MIN_VALUE
        // Keep DAC calibrations: same AudioTrack, hardware mapping still valid
        pendingContinuityCheck = false
        forceDiscontinuityUntilMs = 0L
        forceDiscontinuityReason = ""
        gaplessGraceUntilMs = 0L
        resetSyncState()
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

    /** Shift anchor for static delay change (gradual correction via samples). */
    override fun shiftAnchorForDelayChange(deltaMs: Int) {
        if (anchorLocalUs == 0L) return
        anchorLocalUs -= deltaMs.toLong() * 1000L
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
                (sync.errorUs() / 1000f)
            )
        }
        if (syncState != SyncState.SYNCHRONIZED) return
        if (sync == null || !sync.isSynced()) return

        val now = System.currentTimeMillis()

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
        // DAC drift detection (secondary: corrects for pipeline changes the anchor can't see)
        val dacDriftUs = measureDacSyncErrorUs(track)
        val rawErrorMs = if (dacDriftUs != null && kotlin.math.abs(dacDriftUs) > 500) {
            // Significant DAC drift detected: incorporate into error
            anchorErrorMs + (dacDriftUs / 1000.0)
        } else {
            anchorErrorMs
        }

        // EMA smoothing
        smoothedSyncErrorMs = if (smoothedSyncErrorMs == 0.0) {
            rawErrorMs
        } else {
            SYNC_ERROR_EMA_ALPHA * rawErrorMs + (1 - SYNC_ERROR_EMA_ALPHA) * smoothedSyncErrorMs
        }

        // Log less frequently
        if (now - lastSyncLogMs > 2000) {
            lastSyncLogMs = now
            Log.d(TAG, "Sync: error=${"%.1f".format(smoothedSyncErrorMs)}ms " +
                "outLat=${measuredOutputLatencyUs / 1000}ms " +
                "raw=${"%.1f".format(rawErrorMs)}ms anchor=${"%.1f".format(anchorErrorMs)}ms " +
                "dacDrift=${dacDriftUs?.let { it / 1000 } ?: "n/a"} " +
                "resyncs=$resyncCount filterErr=${sync.errorUs()}us")
        }

        // Don't correct if Kalman hasn't settled
        if (sync.errorUs() > 15_000) return

        // Correction target: anchor-based + DAC drift
        val absoluteSyncMs = startupOffsetMs + smoothedSyncErrorMs
        val absTotal = kotlin.math.abs(absoluteSyncMs)

        // Unmute once DAC sync converges after hard boundary
        if (syncMuted && absTotal < 10.0) {
            syncMuted = false
            audioTrack?.setVolume(if (isMuted) 0f else currentVolume)
            Log.d(TAG, "Sync converged, unmuting (error=${"%.1f".format(absTotal)}ms)")
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

        // Bias learning: only in truly stable steady-state
        learnDeviceBias(absoluteSyncMs, now)
    }

    private var biasSumSquaresMs = 0.0
    private var biasSameSignCount = 0

    private fun learnDeviceBias(absoluteSyncMs: Double, now: Long) {
        // Only learn when all corrections are inactive and state is stable
        if (pendingSampleCorrection != 0) return
        if (currentPlaybackRate != 1.0f) return
        if (now - lastResyncAtMs < 10_000) return
        if (continuationGraceUntilMs > 0 && now < continuationGraceUntilMs) return
        if (now - lastBiasUpdateMs < 30_000) return

        if (biasWindowStartMs == 0L) {
            biasWindowStartMs = now
            biasAccumMs = 0.0
            biasSumSquaresMs = 0.0
            biasSampleCount = 0
            biasSameSignCount = 0
        }

        biasAccumMs += absoluteSyncMs
        biasSumSquaresMs += absoluteSyncMs * absoluteSyncMs
        biasSampleCount++
        // Track sign consistency: same sign as running mean
        if (biasSampleCount > 1) {
            val runningMean = biasAccumMs / biasSampleCount
            if ((absoluteSyncMs > 0 && runningMean > 0) || (absoluteSyncMs < 0 && runningMean < 0)) {
                biasSameSignCount++
            }
        }

        val windowDurationMs = now - biasWindowStartMs
        if (windowDurationMs >= 20_000 && biasSampleCount >= 20) {
            val meanMs = biasAccumMs / biasSampleCount
            val absMean = kotlin.math.abs(meanMs)
            val variance = (biasSumSquaresMs / biasSampleCount) - (meanMs * meanMs)
            val stdDevMs = kotlin.math.sqrt(variance.coerceAtLeast(0.0))
            val signRatio = biasSameSignCount.toFloat() / (biasSampleCount - 1)
            // Persist only if: meaningful bias, low variance, consistent sign direction
            if (absMean in 2.0..10.0 && stdDevMs < 3.0 && signRatio > 0.75f) {
                val learnedUs = (meanMs * 1000).toLong().coerceIn(-10_000L, 10_000L)
                val smoothedUs = (0.2 * learnedUs + 0.8 * deviceBiasCorrectionUs).toLong()
                deviceBiasCorrectionUs = smoothedUs
                lastBiasUpdateMs = now
                Log.d(TAG, "Device bias learned: mean=${"%.1f".format(meanMs)}ms stdDev=${"%.1f".format(stdDevMs)}ms " +
                    "signRatio=${"%.0f".format(signRatio * 100)}% -> correction=${smoothedUs}us (n=$biasSampleCount)")
                onDeviceBiasMeasured?.invoke(smoothedUs)
            }
            resetBiasLearningWindow()
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

    // ── DAC calibration: collect pairs + interpolation ──

    private fun collectDacCalibration(track: AudioTrack) {
        if (!track.getTimestamp(outputLatencyTimestamp)) {
            consecutiveValidTimestamps = 0
            return
        }
        val dacTimeUs = outputLatencyTimestamp.nanoTime / 1000
        val loopTimeUs = System.nanoTime() / 1000
        if (dacTimeUs <= 0) {
            consecutiveValidTimestamps = 0
            return
        }
        consecutiveValidTimestamps++
        if (consecutiveValidTimestamps >= 3) dacTimestampsStable = true

        // Store calibration pair (min 10ms interval)
        if (dacTimeUs - lastDacCalibrationTimeUs > 10_000) {
            lastDacCalibrationTimeUs = dacTimeUs
            synchronized(dacCalibrations) {
                dacCalibrations.addLast(DacCalibration(dacTimeUs, loopTimeUs))
                while (dacCalibrations.size > 50) dacCalibrations.removeFirst()
            }
        }
    }

    /** Convert DAC hardware timestamp to system monotonic time via calibration interpolation. */
    private fun dacTimeToLoopTimeUs(dacTimeUs: Long): Long {
        synchronized(dacCalibrations) {
            if (dacCalibrations.isEmpty()) return dacTimeUs // no calibration yet
            if (dacCalibrations.size == 1) {
                val c = dacCalibrations.first()
                return dacTimeUs - c.dacTimeUs + c.loopTimeUs
            }
            // Find surrounding pair for interpolation
            val last = dacCalibrations.last()
            if (dacTimeUs >= last.dacTimeUs) {
                // Extrapolate from last pair
                return dacTimeUs - last.dacTimeUs + last.loopTimeUs
            }
            for (i in dacCalibrations.size - 2 downTo 0) {
                val prev = dacCalibrations[i]
                val next = dacCalibrations[i + 1]
                if (dacTimeUs >= prev.dacTimeUs) {
                    val dacDelta = next.dacTimeUs - prev.dacTimeUs
                    if (dacDelta <= 0) continue
                    val t = (dacTimeUs - prev.dacTimeUs).toDouble() / dacDelta
                    return (prev.loopTimeUs + t * (next.loopTimeUs - prev.loopTimeUs)).toLong()
                }
            }
            // Before first calibration
            val first = dacCalibrations.first()
            return dacTimeUs - first.dacTimeUs + first.loopTimeUs
        }
    }

    /** Measure sync error using DAC position: where IS the DAC vs where SHOULD it be (in server time). */
    private fun measureDacSyncErrorUs(track: AudioTrack): Long? {
        val sync = clockSynchronizer ?: return null
        if (!dacTimestampsStable) return null

        if (!track.getTimestamp(outputLatencyTimestamp)) return null
        val dacTimeUs = outputLatencyTimestamp.nanoTime / 1000
        val dacFramePosition = outputLatencyTimestamp.framePosition
        if (dacTimeUs <= 0 || dacFramePosition <= 0) return null

        // Where IS the DAC in server time
        val dacLoopTimeUs = dacTimeToLoopTimeUs(dacTimeUs)
        val dacServerTimeUs = sync.localToServerUs(dacLoopTimeUs)

        // Where SHOULD the DAC be: last written server timestamp minus pending frames
        val written = totalFramesWritten.get()
        val adjustedDacPos = dacFramePosition - dacFrameBaseline
        val pending = (written - adjustedDacPos).coerceAtLeast(0)
        val pendingDurationUs = pending * 1_000_000L / activeSampleRate.toLong()
        val expectedDacServerTimeUs = lastWrittenServerTimestampUs - pendingDurationUs

        if (expectedDacServerTimeUs <= 0L) return null

        val rawErrorUs = dacServerTimeUs - expectedDacServerTimeUs

        // First measurement: seed previous, no drift yet
        if (dacPrevRawErrorUs == Long.MIN_VALUE) {
            dacPrevRawErrorUs = rawErrorUs
            Log.d(TAG, "DacSync: initial raw=${rawErrorUs / 1000}ms pending=$pending")
            return null
        }

        // Drift = change in raw error since last measurement
        val driftUs = rawErrorUs - dacPrevRawErrorUs
        dacPrevRawErrorUs = rawErrorUs

        if (decodedFrameCount < 500 || decodedFrameCount % 500 == 0) {
            Log.d(TAG, "DacSync: raw=${rawErrorUs / 1000}ms drift=${driftUs / 1000}ms pending=$pending")
        }
        return driftUs
    }

    private fun clearDacCalibrations() {
        synchronized(dacCalibrations) { dacCalibrations.clear() }
        lastDacCalibrationTimeUs = 0L
        dacTimestampsStable = false
        consecutiveValidTimestamps = 0
    }

    /**
     * Measure actual output pipeline latency via AudioTrack.getTimestamp().
     * Called periodically after track.write(). EMA smoothed like JS client.
     * framesWritten = total PCM frames submitted to AudioTrack.
     * framesPlayed = AudioTimestamp.framePosition (frames output to DAC).
     * Difference = frames in pipeline = pipeline latency.
     */
    // Two-phase output latency: median startup (robust), then EMA steady-state
    private val startupLatencySamples = mutableListOf<Long>()
    private var latencyPhaseStartup = true

    private fun measureOutputLatency(track: AudioTrack, pcmBytes: Int) {
        outputLatencyMeasureCount++
        // Earlier first measurement after startup (40 frames ~0.8s), then every 20 frames (~0.4s) during startup, every 50 (~1s) steady
        val minFrames = if (latencyPhaseStartup) 40 else 150
        val interval = if (latencyPhaseStartup) 20 else 50
        if (decodedFrameCount < minFrames) return
        if (outputLatencyMeasureCount < interval) return
        outputLatencyMeasureCount = 0

        if (!track.getTimestamp(outputLatencyTimestamp)) return
        val framesAtDac = outputLatencyTimestamp.framePosition
        if (framesAtDac <= 0) return

        val framesConsumed = track.playbackHeadPosition.toLong()
        val framesInPipeline = framesConsumed - framesAtDac
        if (framesInPipeline < 0) return

        val tsAgeUs = (System.nanoTime() - outputLatencyTimestamp.nanoTime) / 1000L
        val pipelineUs = framesInPipeline * 1_000_000L / activeSampleRate.toLong()
        val latencyUs = tsAgeUs + pipelineUs
        if (latencyUs <= 0 || latencyUs > 500_000) return
        if (tsAgeUs > 50_000) return  // timestamp too stale

        // Outlier rejection: ignore if >30ms away from current estimate (unless startup/route change)
        val recentRouteChange = outputRouteChangedAtMs > 0 &&
            System.currentTimeMillis() - outputRouteChangedAtMs < 5000
        if (!latencyPhaseStartup && !recentRouteChange && measuredOutputLatencyUs > 0L) {
            val deltaUs = kotlin.math.abs(latencyUs - measuredOutputLatencyUs)
            if (deltaUs > 30_000) return
        }

        if (latencyPhaseStartup) {
            // Phase 1: collect samples, then take median
            startupLatencySamples.add(latencyUs)
            if (startupLatencySamples.size >= 5) {
                val sorted = startupLatencySamples.sorted()
                measuredOutputLatencyUs = sorted[sorted.size / 2]
                latencyPhaseStartup = false
                startupLatencySamples.clear()
                Log.d(TAG, "OutputLatency: startup median=${measuredOutputLatencyUs / 1000}ms (from ${sorted.size} samples)")
            }
        } else {
            // Phase 2: EMA (faster after route change)
            val alpha = if (recentRouteChange) 0.3 else 0.05
            measuredOutputLatencyUs = (alpha * latencyUs + (1.0 - alpha) * measuredOutputLatencyUs).toLong()
        }

        // Persist periodically
        outputLatencyPersistCount++
        if (outputLatencyPersistCount >= 10) {
            outputLatencyPersistCount = 0
            onOutputLatencyMeasured?.invoke(measuredOutputLatencyUs)
        }
        if (decodedFrameCount < 500 || decodedFrameCount % 500 == 0) {
            Log.d(TAG, "OutputLatency: raw=${latencyUs / 1000}ms ema=${measuredOutputLatencyUs / 1000}ms " +
                "tsAge=${tsAgeUs / 1000}ms pipeline=${pipelineUs / 1000}ms phase=${if (latencyPhaseStartup) "startup" else "steady"}")
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
                    if (isSyncMode && dropLateHeadFramesForStartup()) {
                        continue
                    }
                    if (isStartupBufferReady()) {
                        if (generation != playbackGeneration || track !== audioTrack || !playbackActive) break
                        // Bounded precision wait for sync mode (not for SOFT_CONTINUATION)
                        val precisionWaitMs = syncPrecisionWaitMs()
                        if (isSyncMode && precisionWaitMs > 0 && !clockPreciseForCorrections) {
                            val filterErr = clockSynchronizer?.errorUs() ?: Long.MAX_VALUE
                            val precise = filterErr <= CLOCK_PRECISION_THRESHOLD_US
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
                            if (precise) {
                                Log.d(TAG, "Precision wait done: precise after ${waitingMs}ms (reason=$syncStartupReason)")
                            } else {
                                Log.d(TAG, "Precision wait timeout: ${waitingMs}ms (reason=$syncStartupReason, filterErr=${filterErr}us)")
                            }
                        }
                        if (dropLateHeadFramesForStartup()) {
                            continue
                        }
                        if (!isStartupBufferReady()) continue
                        if (!playbackStarted) {
                                val filterErr = clockSynchronizer?.errorUs() ?: Long.MAX_VALUE
                                val clockPrecise = isSyncMode && filterErr <= CLOCK_PRECISION_THRESHOLD_US
                                if (clockPrecise) {
                                    // Clock is precise: align to server timeline
                                    var skipped = 0
                                    while (frameQueue.size > 1) {
                                        val head = frameQueue.peek() ?: break
                                        if (leadToLocalNowMs(head.serverTimestampUs) >= 0L) break
                                        frameQueue.poll()
                                        frameQueueBytes.addAndGet(-head.payload.size.toLong())
                                        skipped++
                                    }
                                    if (skipped > 0) {
                                        Log.d(TAG, "Startup: skipped $skipped stale frames, buf=${bufferDurationMs()}ms")
                                        if (!isStartupBufferReady()) continue
                                    }
                                    val firstFrame = frameQueue.peek()
                                    if (firstFrame != null) {
                                        val targetUs = targetLocalPlayUs(firstFrame.serverTimestampUs)
                                        val leadMs = (targetUs - nowLocalUs()) / 1000L
                                        val waitUs = (targetUs - nowLocalUs()).coerceIn(0, 2_000_000)
                                        val waitMs = waitUs / 1000L
                                        if (waitMs > 15) {
                                            try { Thread.sleep(waitMs - 10) } catch (_: InterruptedException) { break }
                                        }
                                        val spinDeadline = nowLocalUs() + 50_000L
                                        while (nowLocalUs() < targetUs && nowLocalUs() < spinDeadline) { /* spin */ }
                                        val overshootUs = nowLocalUs() - targetUs
                                        startupOffsetMs = if (leadMs < 0) -leadMs.toDouble() else -(overshootUs / 1000.0)
                                        val s2l = clockSynchronizer?.serverToLocalUs(firstFrame.serverTimestampUs) ?: firstFrame.serverTimestampUs
                                        Log.d(TAG, "Startup align (precise): lead=${leadMs}ms " +
                                            "absOffset=${"%.1f".format(startupOffsetMs)}ms " +
                                            "filterErr=${clockSynchronizer?.errorUs() ?: -1}us " +
                                            "[s2l=${s2l / 1000}ms static=${staticDelayMs}ms outLat=${measuredOutputLatencyUs / 1000}ms " +
                                            "bias=${deviceBiasCorrectionUs}us target=${targetUs / 1000}ms now=${nowLocalUs() / 1000}ms]")
                                    }
                                    clockPreciseForCorrections = true
                                } else if (isSyncMode) {
                                    // Clock imprecise: just play, no alignment, no startup offset
                                    startupOffsetMs = 0.0
                                    Log.d(TAG, "Startup (imprecise clock): playing without alignment, " +
                                        "filterErr=${clockSynchronizer?.errorUs() ?: -1}us")
                                }
                            Log.d(
                                DBG,
                                "playbackThread track.play codec=$activeCodec buf=${bufferDurationMs()}ms " +
                                    "required=${requiredSyncBufferMs}ms sync=$syncState"
                            )
                            track.play()
                            track.setVolume(if (isMuted || syncMuted) 0f else currentVolume)
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
            val framesWritten = finalPcm.size / (activeChannels * 2)
            totalFramesWritten.addAndGet(framesWritten.toLong())
            lastWrittenServerTimestampUs = frame.serverTimestampUs
            if (isSyncMode) {
                collectDacCalibration(track)
                measureOutputLatency(track, finalPcm.size)
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
                        val fwCodec = finalPcm.size / (activeChannels * 2)
                        totalFramesWritten.addAndGet(fwCodec.toLong())
                        lastWrittenServerTimestampUs = frame.serverTimestampUs
                        if (isSyncMode) {
                            collectDacCalibration(track)
                            measureOutputLatency(track, finalPcm.size)
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
        if (!isMuted) audioTrack?.setVolume(currentVolume)
    }
    override fun setMuted(muted: Boolean) {
        isMuted = muted
        audioTrack?.setVolume(if (muted) 0f else currentVolume)
    }

    override fun clearBuffer() {
        hardBoundaryPending = true
        streamGeneration++
        configureGeneration++
        syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
        clockPreciseForCorrections = false
        resetBiasLearningWindow()
        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
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
        resetSyncState()

        Log.d(TAG, "Buffer cleared")
    }

    override fun onStreamEnd() {
        Log.d(
            DBG,
            "onStreamEnd codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
        )
        clockPreciseForCorrections = false
        resetBiasLearningWindow()
        rateCorrectionSupported = true
        applyPlaybackRate(1.0f)
        // Full stop + IDLE (matches main branch behavior).
        hardBoundaryPending = true
        streamGeneration++
        configureGeneration++
        if (syncState != SyncState.SYNC_ERROR_REBUFFERING && syncState != SyncState.IDLE) {
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
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason, flushing")
        flushForRebuffer()
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

    override fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route change: reason=$reason latency=${measuredOutputLatencyUs / 1000}ms bias=${deviceBiasCorrectionUs}us sync=$syncState started=$playbackStarted")
        // Reset route-sensitive calibration
        measuredOutputLatencyUs = 0L
        outputLatencyMeasureCount = 0
        outputLatencyPersistCount = 0
        latencyPhaseStartup = true
        startupLatencySamples.clear()
        outputRouteChangedAtMs = System.currentTimeMillis()
        // Reset bias (different route = different bias)
        deviceBiasCorrectionUs = 0L
        // Re-enter precision gate
        clockPreciseForCorrections = false
        resetBiasLearningWindow()
        applyPlaybackRate(1.0f)
        // Hard relock in sync mode: flush queue + AudioTrack but keep stream generation
        // (server is still sending the same stream, no configure needed)
        if (isSyncMode && playbackActive) {
            syncStartupReason = SyncStartupReason.RELOCK_AFTER_SEEK
            playbackGeneration++  // invalidate in-flight decoded frames
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            synchronized(codecLock) {
                playbackStarted = false
                audioTrack?.setVolume(0f)
                audioTrack?.pause()
                audioTrack?.flush()
                codec?.flush()
            }
            frameQueue.clear()
            frameQueueBytes.set(0)
            lastEnqueuedTimestampUs = 0L
            anchorServerTimestampUs = 0L
            anchorLocalUs = 0L
            anchorLocalEquivalentUs = 0L
            smoothedSyncErrorMs = 0.0
            startupOffsetMs = 0.0
            pendingSampleCorrection = 0
            presentationTimeUs = 0L
            requiredSyncBufferMs = recoveryBufferMs()
            Log.d(TAG, "Route change relock: flushed audio, awaiting rebuffer (threshold=${requiredSyncBufferMs}ms)")
        }
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
