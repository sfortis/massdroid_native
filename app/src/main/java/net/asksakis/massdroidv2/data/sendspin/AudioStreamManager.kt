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
 * Encoded frame buffer architecture:
 * WS callback -> enqueue encoded frame (fast, no decode, no blocking)
 * Playback thread -> dequeue -> decode -> track.write (blocking = smooth pacing)
 *
 * Buffer holds encoded frames (~300KB for 30s opus, ~6MB for 30s flac).
 * Fills instantly during server burst. Drains at real-time via track.write blocking.
 *
 * Codec switch: hot-swap MediaCodec while AudioTrack stays alive. The hardware
 * buffer (~300ms decoded PCM) bridges the gap during codec rebuild. No pending
 * queue needed: new frames flow directly into the main queue with the new codec.
 */
class AudioStreamManager {

    companion object {
        private const val TAG = "AudioStream"
        private const val DBG = "AudioSwitchDbg"
        private const val HEADER_SIZE = 9
        private const val MAX_ENCODED_BUFFER_BYTES = 8_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024
        private const val MIN_SYNC_BUFFER_MS = 200L  // absolute minimum before play
        private const val HOLDOVER_MIN_BUFFER_MS = 750L
        private const val LATE_CHUNK_DROP_GRACE_MS = 200L
        private const val STARTUP_ENQUEUE_GRACE_MS = 2000L

        // Drift correction tiers
        private const val DEADBAND_MS = 5.0               // no correction below this
        private const val SAMPLE_CORRECTION_MAX_MS = 25.0  // sample correction up to this
        private const val RATE_CORRECTION_MAX_MS = 100.0   // gentle rate correction up to this
        private const val HARD_RESYNC_MS = 100.0           // hard resync above this
        private const val DRIFT_CHECK_INTERVAL_FRAMES = 10 // check every ~200ms
        private const val DRIFT_STARTUP_GRACE_MS = 2000L   // no drift check for 2s after startup
        private const val DRIFT_RESYNC_COOLDOWN_MS = 3000L  // min time between hard resyncs
        private const val STABILITY_COUNT_REQUIRED = 3      // consecutive same-sign checks before correcting
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
    @Volatile private var requiredSyncBufferMs = MIN_SYNC_BUFFER_MS
    @Volatile private var lateDropCount = 0L
    @Volatile private var enqueueLateDropGraceUntilUs = 0L
    @Volatile private var lastFlacLowBufferLogMs = 0L
    private var presentationTimeUs = 0L

    // Sync state per spec
    @Volatile var syncState = SyncState.IDLE; private set
    var onSyncStateChanged: ((SyncState) -> Unit)? = null

    // Volume
    @Volatile private var currentVolume = 1f
    @Volatile private var isMuted = false

    // Clock sync
    @Volatile var clockSynchronizer: ClockSynchronizer? = null
    @Volatile var staticDelayMs: Int = 0

    // Drift correction state (anchor-based for stream API)
    private var smoothedSyncErrorMs = 0.0            // filtered sync error
    private val syncErrorWindow = mutableListOf<Double>() // median filter window
    private var lastDriftLogMs = 0L
    private var resyncCount = 0
    private var sameSignCount = 0                    // consecutive checks with same error sign
    private var lastErrorSign = 0                    // -1, 0, +1
    private var rateSupported: Boolean? = null        // null = untested, true/false = probed at runtime
    private var currentCorrectionMode = "none"       // for logging
    private var playbackStartedAtMs = 0L             // wall clock when playback started
    private var clockWaitStartMs = 0L                // wall clock when we started waiting for clock convergence
    private var lastResyncAtMs = 0L                  // wall clock of last hard resync
    private var anchorServerTimestampUs = 0L          // server ts of first played frame
    private var anchorLocalUs = 0L                    // local time when first frame was written (after blocking)
    private var anchorLocalEquivalentUs = 0L          // cached serverToLocalUs(anchorTs) at anchor time
    private var initialOffsetMs = 0.0                // absolute offset at startup (from targetLocalPlayUs)

    fun bufferDurationMs(): Long {
        val headTs = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tailTs = lastEnqueuedTimestampUs
        if (tailTs <= 0L || tailTs < headTs) return 0L
        return ((tailTs - headTs + estimatedFrameDurationUs).coerceAtLeast(0L)) / 1000L
    }

    fun bufferedBytes(): Long = frameQueueBytes.get()


    private fun nowLocalUs(): Long = System.nanoTime() / 1000L

    @Volatile private var hwBufferLatencyUs = 0L

    /**
     * When this audio chunk should EXIT the speaker (local time, microseconds).
     * Per spec: client_time = serverToLocal(serverTs) - static_delay_ms
     * We also subtract hwBufferLatencyUs because track.write() puts data into
     * the AudioTrack buffer, which takes hwBufferLatencyUs to drain to the DAC.
     * This matches JS reference: targetTime - outputLatencySec
     */
    private fun targetLocalPlayUs(serverTimestampUs: Long): Long {
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
            ?: serverTimestampUs
        return localUs - (staticDelayMs.toLong() * 1000L) - hwBufferLatencyUs
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
            requiredSyncBufferMs = defaultSyncBufferMs()
        }
        onSyncStateChanged?.invoke(newState)
    }

    /** Sync buffer = hw output latency + small margin. No hardcoded delays. */
    private fun defaultSyncBufferMs(): Long {
        val hwMs = hwBufferLatencyUs / 1000L
        return (hwMs + MIN_SYNC_BUFFER_MS).coerceIn(MIN_SYNC_BUFFER_MS, 2000L)
    }

    fun configure(
        codecName: String = "opus",
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: String? = null
    ) {
        // Same codec + same params: keep buffer, just check continuity on next frame
        if (configured && codecName == activeCodec && sampleRate == activeSampleRate
            && channels == activeChannels && bitDepth == activeBitDepth
        ) {
            Log.d(
                DBG,
                "configure same-codec codec=$codecName playbackActive=$playbackActive started=$playbackStarted " +
                    "threadAlive=${playbackThread?.isAlive == true} sync=$syncState buf=${bufferDurationMs()}ms"
            )
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

            if (syncState == SyncState.HOLDOVER_PLAYING_FROM_BUFFER) {
                // Holdover reconnect: adaptive alignment using timestamp comparison.
                // AudioTrack keeps playing, late frames dropped, on-time frames resume.
                holdoverEndTimestampUs = lastPlayedServerTimestampUs
                pendingAudioTrackFlush = true
            } else {
                // IDLE/REBUFFERING reconnect: standard startup path.
                holdoverEndTimestampUs = 0L
                pendingAudioTrackFlush = false
                playbackStarted = false
                requiredSyncBufferMs = defaultSyncBufferMs()
            }
            // Keep playbackStarted for holdover so playback thread continues decode loop
            // late-drop naturally.
            if (!playbackActive || playbackThread?.isAlive != true) {
                playbackActive = true
                audioTrack?.let { startPlaybackThread(it) }
            }
            pendingContinuityCheck = true
            Log.d(TAG, "Same codec stream/start, buf=${bufferDurationMs()}ms ($codecName)")
            return
        }

        // Hot-swap: different codec but same audio format (sample rate + channels).
        // Keep AudioTrack alive (hardware buffer bridges the codec switch), keep playback
        // thread running. Only swap the MediaCodec and clear the encoded frame queue.
        if (configured && playbackStarted
            && sampleRate == activeSampleRate && channels == activeChannels
            && audioTrack != null && playbackThread?.isAlive == true
        ) {
            Log.d(
                DBG,
                "configure hot-swap oldCodec=$activeCodec newCodec=$codecName " +
                    "sync=$syncState buf=${bufferDurationMs()}ms"
            )

            val newCodec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)

            // Swap codec under lock (playback thread checks mc !== codec)
            val oldCodec: MediaCodec?
            synchronized(codecLock) {
                oldCodec = codec
                codec = newCodec
            }
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}

            // Clear queue (old codec frames can't be decoded with new codec)
            frameQueue.clear()
            frameQueueBytes.set(0)
            lastEnqueuedTimestampUs = 0L
            estimatedFrameDurationUs = 20_000L
            presentationTimeUs = 0L
            lateDropCount = 0L
            oversizedDropCount = 0L
            consecutiveDecodeFailures = 0
            configureGeneration++

            // Brief late-drop grace: after hot-swap, initial frames may appear "late"
            // relative to the old holdover buffer position.
            enqueueLateDropGraceUntilUs = nowLocalUs() + (5_000L * 1000L)
            lastFrameReceivedMs = System.currentTimeMillis()

            activeCodec = codecName
            activeBitDepth = bitDepth

            pendingContinuityCheck = false

            // Report state:"error" to trigger server buffer tracker reset and burst
            // refill. AudioTrack stays in play state: hardware buffer (~300-640ms PCM)
            // bridges the codec switch while the server burst-fills new frames.
            playbackStarted = false
            requiredSyncBufferMs = defaultSyncBufferMs()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)

            Log.d(TAG, "Hot-swap: $activeCodec ${sampleRate}Hz/${bitDepth}bit, track alive")
            return
        }

        // Full rebuild: first configure or sample rate/channels changed
        Log.d(
            DBG,
            "configure rebuild oldCodec=${if (configured) activeCodec else "none"} newCodec=$codecName " +
                "playbackActive=$playbackActive started=$playbackStarted sync=$syncState buf=${bufferDurationMs()}ms"
        )
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
        presentationTimeUs = 0L
        requiredSyncBufferMs = defaultSyncBufferMs()
        startPlaybackThread(createdAudioTrack)
        Log.d(TAG, "Pipeline: $codecName ${sampleRate}Hz/${bitDepth}bit ${channels}ch, trackBuf=$bufferSize")
    }

    // ── WS callback: just enqueue encoded frame (fast, no decode) ──

    fun currentConfigureGeneration(): Long = configureGeneration

    fun onBinaryMessage(data: ByteArray, generation: Long) {
        if (!configured || !playbackActive) return
        if (generation != configureGeneration) return
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

        if (shouldDropLateFrameOnEnqueue(serverTimestampUs)) {
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
                requiredSyncBufferMs = defaultSyncBufferMs()
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
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        synchronized(codecLock) {
            audioTrack?.setVolume(0f)  // mute before flush to hide click/pop
            audioTrack?.pause()
            audioTrack?.flush()
            codec?.flush()
        }
        frameQueue.clear()
        frameQueueBytes.set(0)
        playbackStarted = false
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
        resetDriftState()
    }

    /**
     * Two-stage starvation: at 1s silence transition to HOLDOVER (AudioTrack hardware buffer
     * keeps playing decoded PCM). At 2s silence fully rebuffer (pause + flush track).
     */
    private fun checkStarvation(track: AudioTrack) {
        if (!playbackStarted) return
        if (syncState != SyncState.SYNCHRONIZED && syncState != SyncState.HOLDOVER_PLAYING_FROM_BUFFER) return
        if (!frameQueue.isEmpty()) return
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
     * Shift the drift anchor to account for a static delay change.
     * By moving anchorLocalUs, the drift corrector sees an error and
     * gradually corrects via sample insertion/deletion (no flush, no gap).
     *
     * Positive deltaMs (increased delay = play earlier) -> need to remove audio -> shift anchor earlier
     * Negative deltaMs (decreased delay = play later) -> need to add audio -> shift anchor later
     */
    fun shiftAnchorForDelayChange(deltaMs: Int) {
        if (anchorLocalUs == 0L) return  // not yet playing
        anchorLocalUs -= deltaMs.toLong() * 1000L
        // Full correction state reset so new error builds cleanly
        smoothedSyncErrorMs = 0.0
        syncErrorWindow.clear()
        sameSignCount = 0
        lastErrorSign = 0
        pendingSampleCorrection = 0
        currentCorrectionMode = "none"
        Log.d(TAG, "Anchor shifted by ${deltaMs}ms for delay change")
    }

    private fun resetDriftState() {
        smoothedSyncErrorMs = 0.0
        syncErrorWindow.clear()
        resyncCount = 0
        sameSignCount = 0
        lastErrorSign = 0
        pendingSampleCorrection = 0
        currentCorrectionMode = "none"
        rateSupported = null  // re-probe on next AudioTrack
        playbackStartedAtMs = 0L
        clockWaitStartMs = 0L
        anchorServerTimestampUs = 0L
        anchorLocalUs = 0L
        anchorLocalEquivalentUs = 0L
        initialOffsetMs = 0.0
    }

    /**
     * Drift measurement via AudioTrack.getTimestamp().
     *
     * Uses hardware-reported playback position (immune to write-blocking jitter)
     * instead of wall-clock measurement which has ±20ms frame-sized noise.
     *
     * Method: compare "where the hardware IS playing" vs "where it SHOULD be"
     * based on server timestamps and clock sync.
     *
     * Positive = hardware ahead (playing too fast) → slow down
     * Negative = hardware behind (playing too slow) → speed up
     */
    /**
     * Wall-clock drift measurement. Immune to getTimestamp coarseness.
     * track.write() blocking paces the loop at hardware clock rate.
     */
    /**
     * Sync error = initial absolute offset + accumulated drift.
     * initialOffsetMs captures the startup alignment error (±15ms jitter).
     * The anchor-based delta tracks drift since anchor was set.
     */
    private fun computeSyncErrorMs(serverTimestampUs: Long): Double {
        val sync = clockSynchronizer ?: return 0.0
        if (anchorLocalEquivalentUs == 0L || anchorLocalUs == 0L) return 0.0

        val expectedElapsedUs = sync.serverToLocalUs(serverTimestampUs) - anchorLocalEquivalentUs
        val actualElapsedUs = nowLocalUs() - anchorLocalUs
        return (actualElapsedUs - expectedElapsedUs).toDouble() / 1000.0
    }

    /**
     * Drift correction via AudioTrack.setPlaybackRate().
     * No sample manipulation, no allocations, no artifacts.
     *
     * Tiers (matching JS reference "sync" mode):
     *   < 2ms:   rate = 1.0 (deadband, no correction)
     *   2-8ms:   rate ± 0.5% (gentle)
     *   8-35ms:  rate ± 1% (moderate)
     *   35-200ms: rate ± 2% (aggressive)
     *   > 200ms: hard resync (flush + rebuffer)
     */
    private fun updateDriftCorrection(
        serverTimestampUs: Long,
        track: AudioTrack
    ) {
        if (syncState != SyncState.SYNCHRONIZED) return
        if (clockSynchronizer?.isSynced() != true) return

        val now = System.currentTimeMillis()

        // Grace period after startup (buffer fill transient)
        if (playbackStartedAtMs > 0 && now - playbackStartedAtMs < DRIFT_STARTUP_GRACE_MS) {
            return
        }

        // Set anchor after grace period (writes are blocking = steady-state pacing)
        val sync = clockSynchronizer ?: return
        if (anchorServerTimestampUs == 0L) {
            anchorServerTimestampUs = serverTimestampUs
            anchorLocalUs = nowLocalUs()
            anchorLocalEquivalentUs = sync.serverToLocalUs(serverTimestampUs)
            initialOffsetMs = 0.0
            Log.d(TAG, "Anchor set, filterError=${sync.errorUs()}us")
            return
        }

        val rawErrorMs = computeSyncErrorMs(serverTimestampUs)
        // Median filter: immune to bimodal jitter spikes (Xiaomi alternates -20ms / -1ms)
        syncErrorWindow.add(rawErrorMs)
        if (syncErrorWindow.size > 7) syncErrorWindow.removeAt(0)
        smoothedSyncErrorMs = if (syncErrorWindow.size >= 3) {
            syncErrorWindow.sorted()[syncErrorWindow.size / 2]
        } else {
            rawErrorMs
        }
        val absError = kotlin.math.abs(smoothedSyncErrorMs)

        // Stability gate: track sign consistency
        val currentSign = if (smoothedSyncErrorMs > 0) 1 else if (smoothedSyncErrorMs < 0) -1 else 0
        if (currentSign == lastErrorSign && currentSign != 0) {
            sameSignCount++
        } else {
            sameSignCount = 1
            lastErrorSign = currentSign
        }
        val stable = sameSignCount >= STABILITY_COUNT_REQUIRED

        // Log periodically
        if (now - lastDriftLogMs > 2000) {
            lastDriftLogMs = now
            Log.d(TAG, "Drift: error=${"%.1f".format(smoothedSyncErrorMs)}ms " +
                "raw=${"%.1f".format(rawErrorMs)}ms mode=$currentCorrectionMode " +
                "sign=$sameSignCount rate=${track.playbackRate} " +
                "resyncs=$resyncCount filterError=${sync.errorUs()}us")
        }

        // Don't correct if time filter hasn't settled
        if (sync.errorUs() > 15_000) return

        // Tier 4: Hard resync for extreme errors
        if (absError > HARD_RESYNC_MS && now - lastResyncAtMs > DRIFT_RESYNC_COOLDOWN_MS) {
            resyncCount++
            lastResyncAtMs = now
            currentCorrectionMode = "resync"
            Log.d(TAG, "Drift RESYNC: error=${"%.1f".format(smoothedSyncErrorMs)}ms (resync #$resyncCount)")
            flushForRebuffer()
            return
        }

        // Tier 3: Gentle rate correction for medium sustained errors (if supported)
        if (absError > SAMPLE_CORRECTION_MAX_MS && absError <= RATE_CORRECTION_MAX_MS && stable) {
            if (rateSupported != false) {
                val targetRate = if (smoothedSyncErrorMs > 0) {
                    (activeSampleRate * 1.005).toInt()  // +0.5% speed up
                } else {
                    (activeSampleRate * 0.995).toInt()  // -0.5% slow down
                }
                val result = track.setPlaybackRate(targetRate)
                if (result == AudioTrack.SUCCESS) {
                    rateSupported = true
                    currentCorrectionMode = "rate"
                    pendingSampleCorrection = 0
                    return
                } else {
                    rateSupported = false
                    Log.d(TAG, "setPlaybackRate not supported, falling back to sample correction")
                }
            }
            // Rate not supported: use aggressive sample correction instead
            pendingSampleCorrection = currentSign
            currentCorrectionMode = "sample"
            return
        }

        // Tier 2: Sample correction for small sustained errors
        if (absError >= DEADBAND_MS && absError <= SAMPLE_CORRECTION_MAX_MS && stable) {
            // Ensure rate is reset before doing sample correction (no stacking)
            if (rateSupported == true && track.playbackRate != activeSampleRate) {
                track.setPlaybackRate(activeSampleRate)
            }
            pendingSampleCorrection = currentSign
            currentCorrectionMode = "sample"
        } else {
            // Tier 1: Deadband or unstable sign
            pendingSampleCorrection = 0
            // Reset rate to normal if we were correcting
            if (rateSupported == true && track.playbackRate != activeSampleRate) {
                track.setPlaybackRate(activeSampleRate)
            }
            currentCorrectionMode = "none"
        }
    }


    // ── Sample correction for steady-state convergence ──

    @Volatile private var pendingSampleCorrection = 0  // +1 = remove sample, -1 = insert sample, 0 = none

    private fun applySampleCorrection(pcm: ByteArray): ByteArray {
        val direction = pendingSampleCorrection
        if (direction == 0) return pcm
        val bytesPerSample = activeChannels * 2
        val totalSamples = pcm.size / bytesPerSample
        if (totalSamples < 20) return pcm
        // Remove or insert 1 sample per correction frame
        return if (direction > 0) {
            removeSample(pcm, bytesPerSample)
        } else {
            insertSample(pcm, bytesPerSample)
        }
    }

    private fun removeSample(pcm: ByteArray, bytesPerSample: Int): ByteArray {
        if (pcm.size < bytesPerSample * 3) return pcm
        val midPos = (pcm.size / 2 / bytesPerSample) * bytesPerSample
        val out = ByteArray(pcm.size - bytesPerSample)
        System.arraycopy(pcm, 0, out, 0, midPos)
        System.arraycopy(pcm, midPos + bytesPerSample, out, midPos,
            pcm.size - midPos - bytesPerSample)
        return out
    }

    private fun insertSample(pcm: ByteArray, bytesPerSample: Int): ByteArray {
        if (pcm.size < bytesPerSample * 2) return pcm
        val midPos = (pcm.size / 2 / bytesPerSample) * bytesPerSample
        val out = ByteArray(pcm.size + bytesPerSample)
        System.arraycopy(pcm, 0, out, 0, midPos)
        // Duplicate sample at midpoint
        System.arraycopy(pcm, midPos, out, midPos, bytesPerSample)
        System.arraycopy(pcm, midPos, out, midPos + bytesPerSample,
            pcm.size - midPos)
        return out
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
                    if (dropLateHeadFramesForStartup()) {
                        continue
                    }
                    if (isStartupBufferReady()) {
                        if (generation != playbackGeneration || track !== audioTrack || !playbackActive) break
                        // Startup precision gate: wait for clock filter to converge.
                        // Without this, playback starts with 500ms+ clock error.
                        // Timeout after 4s so we don't block forever.
                        val clockReady = clockSynchronizer?.isReadyForPlaybackStart() ?: false
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
                        }
                        // Clock is ready (or timed out): drain any stale frames from wait period
                        if (dropLateHeadFramesForStartup()) {
                            continue
                        }
                        if (!isStartupBufferReady()) continue  // re-check after drain
                        if (!playbackStarted) {
                            // Silence-padding startup: instead of imprecise Thread.sleep,
                            // start play() immediately and write calculated silence so the
                            // first audio frame exits the speaker at the right moment.
                            // Precision is sub-sample (no OS scheduling jitter).
                            val firstFrame = frameQueue.peek()
                            if (firstFrame != null) {
                                val targetExitUs = clockSynchronizer?.serverToLocalUs(firstFrame.serverTimestampUs)
                                    ?: firstFrame.serverTimestampUs
                                val targetExitWithDelayUs = targetExitUs - (staticDelayMs.toLong() * 1000L)
                                val nowUs = nowLocalUs()
                                val leadUs = targetExitWithDelayUs - nowUs
                                // leadUs = how far in the future the audio should exit
                                // hwBufferLatencyUs = time for data to traverse hw buffer
                                // silenceUs = leadUs - hwBufferLatencyUs = silence before first frame
                                val silenceUs = (leadUs - hwBufferLatencyUs).coerceIn(0, 2_000_000)
                                val silenceBytes = (silenceUs * activeSampleRate * activeChannels * 2 / 1_000_000).toInt()
                                    .let { it - (it % (activeChannels * 2)) }  // align to sample boundary
                                if (silenceBytes > 0) {
                                    track.write(ByteArray(silenceBytes), 0, silenceBytes)
                                }
                                Log.d(TAG, "Silence padding: ${silenceUs / 1000}ms (${silenceBytes}B), " +
                                    "lead=${leadUs / 1000}ms hwLat=${hwBufferLatencyUs / 1000}ms")
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
                    } else if (shouldDropLateFrame(frame.serverTimestampUs)) {
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
                            // Save config, rebuild pipeline, continue accepting frames
                            val cfg = arrayOf(activeCodec, activeSampleRate, activeChannels, activeBitDepth)
                            release_internal()
                            configure(cfg[0] as String, cfg[1] as Int, cfg[2] as Int, cfg[3] as Int)
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
            if (generation != playbackGeneration || track !== audioTrack || !playbackActive) return false
            val pcm = if (activeBitDepth == 24) convertPcm24To16(encodedData) else encodedData
            val finalPcm = applySampleCorrection(pcm)
            track.write(finalPcm, 0, finalPcm.size)
            decodedFrameCount++
            if (decodedFrameCount % DRIFT_CHECK_INTERVAL_FRAMES == 0) {
                updateDriftCorrection(frame.serverTimestampUs, track)
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
                        if (generation != playbackGeneration || track !== audioTrack || mc !== codec || !playbackActive) break
                        val finalPcm = applySampleCorrection(pcmToWrite)
                        track.write(finalPcm, 0, finalPcm.size) // blocks when AudioTrack full = pacing
                        decodedFrameCount++
                        if (decodedFrameCount % DRIFT_CHECK_INTERVAL_FRAMES == 0) {
                            updateDriftCorrection(frame.serverTimestampUs, track)
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

    fun setPaused(paused: Boolean) {
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

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (!isMuted) audioTrack?.setVolume(currentVolume)
    }
    fun setMuted(muted: Boolean) {
        isMuted = muted
        audioTrack?.setVolume(if (muted) 0f else currentVolume)
    }

    fun clearBuffer() {
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
        requiredSyncBufferMs = defaultSyncBufferMs()
        estimatedFrameDurationUs = 20_000L
        presentationTimeUs = 0L
        enqueueLateDropGraceUntilUs = 0L
        lateDropCount = 0L
        resetDriftState()

        Log.d(TAG, "Buffer cleared")
    }

    fun onStreamEnd() {
        Log.d(
            DBG,
            "onStreamEnd codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "playbackActive=$playbackActive buf=${bufferDurationMs()}ms queueBytes=${frameQueueBytes.get()}"
        )
        configureGeneration++
        // Only flush if not already rebuffering/idle (expectDiscontinuity may have flushed already)
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
        requiredSyncBufferMs = defaultSyncBufferMs()
        estimatedFrameDurationUs = 20_000L
        presentationTimeUs = 0L
        enqueueLateDropGraceUntilUs = 0L
        lateDropCount = 0L
        resetDriftState()
        Log.d(TAG, "Stream ended, buffer cleared")
    }

    fun expectDiscontinuity(reason: String) {
        forceDiscontinuityUntilMs = 0L
        forceDiscontinuityReason = ""
        requiredSyncBufferMs = defaultSyncBufferMs()
        Log.d(TAG, "Discontinuity armed: $reason, flushing immediately")
        flushForRebuffer()
    }

    fun onTransportFailure() {
        val bufMs = bufferDurationMs()
        Log.d(
            DBG,
            "onTransportFailure codec=$activeCodec sync=$syncState started=$playbackStarted " +
                "buf=${bufMs}ms queueBytes=${frameQueueBytes.get()}"
        )
        if (bufMs <= HOLDOVER_MIN_BUFFER_MS) {
            requiredSyncBufferMs = defaultSyncBufferMs()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            Log.d(TAG, "Transport failure, insufficient holdover (${bufMs}ms), recovery threshold=${requiredSyncBufferMs}ms")
        } else {
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            Log.d(TAG, "Transport failure, holdover active (${bufMs}ms)")
        }
    }

    fun release() { release_internal() }

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
        resetDriftState()

        synchronized(codecLock) {
            try { oldTrack?.release() } catch (_: Exception) {}
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
        }
    }
}
