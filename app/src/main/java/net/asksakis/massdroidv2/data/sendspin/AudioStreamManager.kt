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
        private const val NORMAL_SYNC_BUFFER_MS_LOSSLESS = 2_000L
        private const val NORMAL_SYNC_BUFFER_MS_OPUS = 1_500L
        private const val RECOVERY_SYNC_BUFFER_MS = 5_000L
        private const val HOLDOVER_MIN_BUFFER_MS = 750L
        private const val LATE_CHUNK_DROP_GRACE_MS = 200L
        private const val STARTUP_ENQUEUE_GRACE_MS = 500L
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
    @Volatile private var requiredSyncBufferMs = NORMAL_SYNC_BUFFER_MS_LOSSLESS
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
    @Volatile var clockOffsetUs: Long = 0L
    @Volatile var staticDelayMs: Int = 0

    fun bufferDurationMs(): Long {
        val headTs = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tailTs = lastEnqueuedTimestampUs
        if (tailTs <= 0L || tailTs < headTs) return 0L
        return ((tailTs - headTs + estimatedFrameDurationUs).coerceAtLeast(0L)) / 1000L
    }

    fun bufferedBytes(): Long = frameQueueBytes.get()

    private fun nowLocalUs(): Long = System.nanoTime() / 1000L

    private fun targetLocalPlayUs(serverTimestampUs: Long): Long =
        serverTimestampUs - clockOffsetUs - (staticDelayMs.toLong() * 1000L)

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

    private fun defaultSyncBufferMs(): Long {
        return if (activeCodec == "opus") NORMAL_SYNC_BUFFER_MS_OPUS else NORMAL_SYNC_BUFFER_MS_LOSSLESS
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
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding) * 16

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
                        if (!playbackStarted) {
                            Log.d(
                                DBG,
                                "playbackThread track.play codec=$activeCodec buf=${bufferDurationMs()}ms " +
                                    "required=${requiredSyncBufferMs}ms sync=$syncState"
                            )
                            track.play()
                            track.setVolume(if (isMuted) 0f else currentVolume) // restore after flush mute
                            playbackStarted = true
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
                            release_internal()
                            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
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
            track.write(pcm, 0, pcm.size)
            decodedFrameCount++
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
                        track.write(pcmToWrite, 0, pcmToWrite.size) // blocks when AudioTrack full = pacing
                        decodedFrameCount++
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

        synchronized(codecLock) {
            try { oldTrack?.release() } catch (_: Exception) {}
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
        }
    }
}
