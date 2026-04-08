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

        // Sync mode thresholds
        private const val MIN_SYNC_BUFFER_MS = 200L
        private const val SYNC_DEADBAND_MS = 3.0
        private const val SYNC_RESYNC_MS = 100.0
        private const val SYNC_CHECK_INTERVAL_FRAMES = 10
        private const val SYNC_STARTUP_GRACE_MS = 3000L
        private const val SYNC_RESYNC_COOLDOWN_MS = 3000L
        private const val SYNC_ERROR_EMA_ALPHA = 0.10
        private const val CONTINUATION_GRACE_MS = 1000L

        // Direct mode thresholds
        private const val DIRECT_STARTUP_MS_OPUS = 300L
        private const val DIRECT_STARTUP_MS_LOSSLESS = 500L
        private const val DIRECT_RECOVERY_MS_OPUS = 1500L
        private const val DIRECT_RECOVERY_MS_LOSSLESS = 2000L
        private const val DIRECT_CELLULAR_EXTRA_MS = 500L
    }

    private data class EncodedFrame(val serverTimestampUs: Long, val payload: ByteArray) :
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
    @Volatile override var correctionMode = CorrectionMode.SYNC; private set
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

    // Clock sync
    @Volatile override var clockSynchronizer: ClockSynchronizer? = null
    @Volatile override var staticDelayMs: Int = 0

    // Callbacks
    override var onOutputLatencyMeasured: ((Long) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float) -> Unit)? = null

    // Continuation grace: after soft stream/start, suppress correction briefly
    @Volatile private var continuationGraceUntilMs = 0L

    // Sync correction state
    private var smoothedSyncErrorMs = 0.0            // EMA-filtered drift error (relative to anchor)
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

    /** Called from outside to seed with persisted value (like JS localStorage). */
    override fun seedOutputLatency(persistedUs: Long) {
        if (persistedUs > 0) {
            measuredOutputLatencyUs = persistedUs
            Log.d(TAG, "Output latency seeded: ${persistedUs / 1000}ms")
        }
    }

    /**
     * Target local time for this audio chunk.
     * Per spec: client_time = serverToLocal(serverTs) - static_delay_ms - outputLatency
     * outputLatency = measured pipeline latency (AudioTrack buffer → DAC → speaker).
     * Matches JS reference: targetTime - syncDelaySec - outputLatencySec
     */
    private fun targetLocalPlayUs(serverTimestampUs: Long): Long {
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
            ?: serverTimestampUs
        return localUs - (staticDelayMs.toLong() * 1000L) - measuredOutputLatencyUs
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
        CorrectionMode.SYNC -> {
            val hwMs = hwBufferLatencyUs / 1000L
            (hwMs + MIN_SYNC_BUFFER_MS).coerceIn(MIN_SYNC_BUFFER_MS, 2000L)
        }
        CorrectionMode.DIRECT -> {
            if (activeCodec == "opus") DIRECT_STARTUP_MS_OPUS else DIRECT_STARTUP_MS_LOSSLESS
        }
    }

    private fun recoveryBufferMs(): Long = when (correctionMode) {
        CorrectionMode.SYNC -> 2000L
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
                configureGeneration++
                lastEnqueuedTimestampUs = 0L
                frameQueue.clear()
                frameQueueBytes.set(0)
                synchronized(codecLock) {
                    try { codec?.flush() } catch (_: Exception) {}
                }
                presentationTimeUs = 0L
                lateDropCount = 0L
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
                Log.d(TAG, "configure semantic=CONTINUATION path=same-codec action=timing-reset codec=$codecName buf=${bufferDurationMs()}ms")
                configureGeneration++
                lastFrameReceivedMs = System.currentTimeMillis()
                lateDropCount = 0L
                // Full timing state reset (fresh lock, not fresh stream)
                anchorServerTimestampUs = 0L
                anchorLocalUs = 0L
                anchorLocalEquivalentUs = 0L
                smoothedSyncErrorMs = 0.0
                startupOffsetMs = 0.0
                clockWaitStartMs = 0L
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

            if (isNewStream) {
                playbackStarted = false
                requiredSyncBufferMs = defaultBufferMs()
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                Log.d(TAG, "configure semantic=NEW_STREAM path=hot-swap codec=$codecName sync=$syncState")
            } else {
                // CONTINUATION: hw buffer bridges, shorter resume
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
        presentationTimeUs = 0L

        if (isNewStream) {
            requiredSyncBufferMs = defaultBufferMs()
            continuationGraceUntilMs = 0L
        } else {
            // CONTINUATION rebuild: fast re-lock, not cold start
            requiredSyncBufferMs = MIN_SYNC_BUFFER_MS
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
            frameQueue.offer(EncodedFrame(serverTimestampUs, payload))
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
                Log.d(TAG, "Reconnect: first-frame (lastTs=${lastTs / 1000}ms, sync=$syncState), accepting")
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
                // Large gap: clear encoded queue (wrong timestamps) but keep AudioTrack
                // playing (hardware buffer bridges ~300-640ms). Small rebuffer only.
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

    private fun flushForRebuffer() {
        Log.d(
            DBG,
            "flushForRebuffer codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
        )
        hardBoundaryPending = true
        streamGeneration++
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
            val absoluteSyncMs = startupOffsetMs + smoothedSyncErrorMs
            onSyncSample?.invoke(
                absoluteSyncMs.toFloat(),
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

        // Set anchor after grace period
        if (anchorServerTimestampUs == 0L) {
            anchorServerTimestampUs = serverTimestampUs
            anchorLocalUs = nowLocalUs()
            anchorLocalEquivalentUs = sync.serverToLocalUs(serverTimestampUs)
            Log.d(TAG, "Sync anchor set, filterError=${sync.errorUs()}us")
            return
        }

        val rawErrorMs = computeSyncErrorMs(serverTimestampUs)

        // EMA smoothing (replaces median filter)
        smoothedSyncErrorMs = if (smoothedSyncErrorMs == 0.0) {
            rawErrorMs
        } else {
            SYNC_ERROR_EMA_ALPHA * rawErrorMs + (1 - SYNC_ERROR_EMA_ALPHA) * smoothedSyncErrorMs
        }
        val absError = kotlin.math.abs(smoothedSyncErrorMs)

        // Log less frequently
        if (now - lastSyncLogMs > 2000) {
            lastSyncLogMs = now
            val absLeadMs = (sync.serverToLocalUs(serverTimestampUs) - nowLocalUs()) / 1000.0
            Log.d(TAG, "Sync: error=${"%.1f".format(smoothedSyncErrorMs)}ms " +
                "abs=${"%.1f".format(absLeadMs)}ms " +
                "outLat=${measuredOutputLatencyUs / 1000}ms " +
                "raw=${"%.1f".format(rawErrorMs)}ms " +
                "resyncs=$resyncCount filterErr=${sync.errorUs()}us")
        }

        // Don't correct if Kalman hasn't settled
        if (sync.errorUs() > 15_000) return

        // Correction target: absolute sync (startup offset + drift)
        val absoluteSyncMs = startupOffsetMs + smoothedSyncErrorMs
        val absTotal = kotlin.math.abs(absoluteSyncMs)

        // Hard resync for large errors
        if (absTotal > SYNC_RESYNC_MS && now - lastResyncAtMs > SYNC_RESYNC_COOLDOWN_MS) {
            resyncCount++
            lastResyncAtMs = now
            Log.d(TAG, "Sync RESYNC: abs=${"%.1f".format(absoluteSyncMs)}ms (#$resyncCount)")
            flushForRebuffer()
            return
        }

        // Sample correction for errors above deadband
        if (absTotal > SYNC_DEADBAND_MS) {
            pendingSampleCorrection = if (absoluteSyncMs > 0) 1 else -1
            pendingSampleCount = sampleCountForError(absTotal)
        } else {
            pendingSampleCorrection = 0
            pendingSampleCount = 1
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
        return when {
            absErrorMs < 8.0  -> 1
            absErrorMs < 15.0 -> 2
            absErrorMs < 30.0 -> 4
            else              -> 8
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

    private fun removeSamples(pcm: ByteArray, bytesPerSample: Int, count: Int): ByteArray {
        if (pcm.size < bytesPerSample * (count + 2)) return pcm
        val out = ByteArray(pcm.size - bytesPerSample * count)
        // Remove evenly spaced samples to minimize audible artifacts
        val totalSamples = pcm.size / bytesPerSample
        val step = totalSamples / (count + 1)
        var srcPos = 0
        var dstPos = 0
        var removed = 0
        for (i in 0 until totalSamples) {
            if (removed < count && i == step * (removed + 1)) {
                // Skip this sample
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
                // Duplicate this sample
                System.arraycopy(pcm, srcPos - bytesPerSample, out, dstPos, bytesPerSample)
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
    private fun measureOutputLatency(track: AudioTrack, pcmBytes: Int) {
        // Measure every ~1s (50 decoded frames at 20ms each)
        // Skip first 150 frames (~3s) after startup: buffer fill transient
        outputLatencyMeasureCount++
        if (decodedFrameCount < 150) return
        if (outputLatencyMeasureCount < 50) return
        outputLatencyMeasureCount = 0

        if (!track.getTimestamp(outputLatencyTimestamp)) return
        val framesAtDac = outputLatencyTimestamp.framePosition
        if (framesAtDac <= 0) return

        // Pipeline latency: mixer → speaker (AudioFlinger + HAL + DAC).
        // playbackHeadPosition = frames consumed by mixer (reliable, resets after flush)
        // framesAtDac = frames output to speaker (from getTimestamp)
        val framesConsumed = track.playbackHeadPosition.toLong()
        val framesInPipeline = framesConsumed - framesAtDac
        if (framesInPipeline < 0) return

        val tsAgeUs = (System.nanoTime() - outputLatencyTimestamp.nanoTime) / 1000L
        val pipelineUs = framesInPipeline * 1_000_000L / activeSampleRate.toLong()
        val latencyUs = tsAgeUs + pipelineUs
        if (latencyUs <= 0 || latencyUs > 500_000) return  // sanity

        // EMA with alpha=0.05
        measuredOutputLatencyUs = if (measuredOutputLatencyUs == 0L) {
            latencyUs
        } else {
            ((0.05 * latencyUs + 0.95 * measuredOutputLatencyUs).toLong())
        }
        // Persist every ~10 measurements (~10s) so next startup has good seed
        outputLatencyPersistCount++
        if (outputLatencyPersistCount >= 10) {
            outputLatencyPersistCount = 0
            onOutputLatencyMeasured?.invoke(measuredOutputLatencyUs)
        }
        Log.d(TAG, "OutputLatency: raw=${latencyUs / 1000}ms ema=${measuredOutputLatencyUs / 1000}ms " +
            "tsAge=${tsAgeUs / 1000}ms pipeline=${pipelineUs / 1000}ms")
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
                        // Startup precision gate: only in SYNC mode
                        val clockReady = if (isSyncMode) clockSynchronizer?.isReadyForPlaybackStart() ?: false else true
                        if (!clockReady) {
                            val waitingMs = if (clockWaitStartMs > 0) {
                                System.currentTimeMillis() - clockWaitStartMs
                            } else {
                                clockWaitStartMs = System.currentTimeMillis()
                                0L
                            }
                            if (waitingMs < 4000) {
                                // Drain stale frames while waiting (prevents 30s accumulation)
                                dropLateHeadFramesForStartup()
                                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                                continue
                            }
                            Log.d(TAG, "Clock sync timeout after ${waitingMs}ms, starting anyway " +
                                "(error=${clockSynchronizer?.errorUs() ?: -1}us)")
                            clockWaitStartMs = 0L  // reset so next startup gets fresh timeout
                        }
                        // Clock is ready (or timed out): drain any stale frames from wait period
                        if (dropLateHeadFramesForStartup()) {
                            continue
                        }
                        if (!isStartupBufferReady()) continue  // re-check after drain
                        if (!playbackStarted) {
                                // Skip stale frames until lead >= 0 (SYNC mode only)
                                var skipped = 0
                                if (isSyncMode) while (frameQueue.size > 1) {
                                    val head = frameQueue.peek() ?: break
                                    val headLead = leadToLocalNowMs(head.serverTimestampUs)
                                    if (headLead >= 0L) break
                                    frameQueue.poll()
                                    frameQueueBytes.addAndGet(-head.payload.size.toLong())
                                    skipped++
                                }
                                if (skipped > 0) {
                                    Log.d(TAG, "Startup: skipped $skipped stale frames, buf=${bufferDurationMs()}ms")
                                    if (!isStartupBufferReady()) continue
                                }

                                // Busy-wait alignment to server timeline
                                val firstFrame = frameQueue.peek()
                                if (firstFrame != null) {
                                    val targetUs = targetLocalPlayUs(firstFrame.serverTimestampUs)
                                    val leadBeforeWaitMs = (targetUs - nowLocalUs()) / 1000L
                                    val waitUs = (targetUs - nowLocalUs()).coerceIn(0, 2_000_000)
                                    val waitMs = waitUs / 1000L
                                    if (waitMs > 15) {
                                        try { Thread.sleep(waitMs - 10) } catch (_: InterruptedException) { break }
                                    }
                                    val spinDeadline = nowLocalUs() + 50_000L
                                    while (nowLocalUs() < targetUs && nowLocalUs() < spinDeadline) { /* spin */ }
                                    val overshootUs = nowLocalUs() - targetUs
                                    startupOffsetMs = if (leadBeforeWaitMs < 0) -leadBeforeWaitMs.toDouble() else -(overshootUs / 1000.0)
                                    Log.d(TAG, "Startup align: lead=${leadBeforeWaitMs}ms " +
                                        "waited=${waitMs}ms overshoot=${overshootUs / 1000}ms " +
                                        "absOffset=${"%.1f".format(startupOffsetMs)}ms " +
                                        "outLat=${measuredOutputLatencyUs / 1000}ms " +
                                        "filterErr=${clockSynchronizer?.errorUs() ?: -1}us")
                                }
                            Log.d(
                                DBG,
                                "playbackThread track.play codec=$activeCodec buf=${bufferDurationMs()}ms " +
                                    "required=${requiredSyncBufferMs}ms sync=$syncState"
                            )
                            track.play()
                            track.setVolume(if (isMuted) 0f else currentVolume)
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
            if (isSyncMode) measureOutputLatency(track, finalPcm.size)
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
                        if (isSyncMode) measureOutputLatency(track, finalPcm.size)
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

    override fun release() { release_internal() }

    /** Internal recovery after decode failures. Rebuilds codec/audio without changing protocol semantic. */
    private fun rebuildPipelineAfterFailure() {
        Log.d(TAG, "rebuildPipelineAfterFailure codec=$activeCodec, preserving semantic=${lastProtocolStartType}")
        val savedCodec = activeCodec
        val savedRate = activeSampleRate
        val savedChannels = activeChannels
        val savedBitDepth = activeBitDepth
        release_internal()
        // Rebuild with continuation semantic: fast re-lock, not fresh stream startup
        configure(savedCodec, savedRate, savedChannels, savedBitDepth, startType = ProtocolStartType.CONTINUATION)
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
