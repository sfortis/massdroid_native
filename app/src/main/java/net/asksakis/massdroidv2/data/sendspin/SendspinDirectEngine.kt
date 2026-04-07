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

/**
 * Solo playback engine: buffer, decode, play.
 * No clock sync, no drift correction, no timestamp scheduling.
 * Stream ownership via explicit generation counter (matches JS client model).
 */
class SendspinDirectEngine : SendspinAudioEngine {

    companion object {
        private const val TAG = "DirectEngine"
        private const val HEADER_SIZE = 9
        private const val MAX_ENCODED_BUFFER_BYTES = 8_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024
        // Fast startup thresholds (first play / new stream)
        private const val STARTUP_BUFFER_MS_LOSSLESS = 500L
        private const val STARTUP_BUFFER_MS_OPUS = 300L
        // Recovery after trouble: more buffer for stability
        private const val RECOVERY_BUFFER_MS_LOSSLESS = 2000L
        private const val RECOVERY_BUFFER_MS_OPUS = 1500L
        // Cellular recovery: even more headroom
        private const val CELLULAR_RECOVERY_EXTRA_MS = 500L
        private const val HOLDOVER_MIN_BUFFER_MS = 750L
    }

    private data class EncodedFrame(val serverTimestampUs: Long, val payload: ByteArray) :
        Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    // Encoded frame queue
    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
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
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile private var lastPlayedServerTimestampUs = 0L
    @Volatile private var requiredBufferMs = STARTUP_BUFFER_MS_OPUS
    private var presentationTimeUs = 0L
    private var consecutiveDecodeFailures = 0
    private var oversizedDropCount = 0L

    // Stream ownership: generation incremented on every stream boundary,
    // frames from old generation rejected before enqueue and after dequeue.
    @Volatile private var streamGeneration = 0L
    @Volatile private var acceptGeneration = 0L
    @Volatile private var generationDropCount = 0L

    // Stream boundary intent: true = next configure is a hard reset.
    // Set by flushForRebuffer/clearBuffer/onStreamEnd. Cleared by configure.
    @Volatile private var hardBoundaryPending = true

    // Holdover reconnect (transport failure recovery)
    @Volatile private var pendingAudioTrackFlush = false
    @Volatile private var holdoverEndTimestampUs = 0L
    @Volatile private var discardUntilTimestampUs = 0L

    // Network-adaptive recovery buffering
    @Volatile private var isCellular = false

    // Interface
    @Volatile override var syncState = SyncState.IDLE; private set
    override var onSyncStateChanged: ((SyncState) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float) -> Unit)? = null
    override var onOutputLatencyMeasured: ((Long) -> Unit)? = null
    @Volatile override var clockSynchronizer: ClockSynchronizer? = null
    @Volatile override var staticDelayMs: Int = 0
    @Volatile override var measuredOutputLatencyUs = 0L; private set

    // Volume
    @Volatile private var currentVolume = 1f
    @Volatile private var isMuted = false

    // ── Buffer info ──

    override fun bufferDurationMs(): Long {
        val headTs = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tailTs = lastEnqueuedTimestampUs
        if (tailTs <= 0L || tailTs < headTs) return 0L
        return ((tailTs - headTs + estimatedFrameDurationUs).coerceAtLeast(0L)) / 1000L
    }

    override fun bufferedBytes(): Long = frameQueueBytes.get()

    override fun currentConfigureGeneration(): Long = configureGeneration

    // ── State transitions ──

    private fun transitionSyncState(newState: SyncState) {
        if (syncState == newState) return
        val old = syncState
        syncState = newState
        Log.d(TAG, "State: $old -> $newState (buf=${bufferDurationMs()}ms)")
        if (newState == SyncState.SYNCHRONIZED) {
            requiredBufferMs = defaultBufferMs()
        }
        onSyncStateChanged?.invoke(newState)
    }

    /** Set by SendspinManager based on ConnectivityManager TRANSPORT_CELLULAR. */
    fun setCellularTransport(cellular: Boolean) {
        isCellular = cellular
    }

    /** Fast startup: low threshold for responsive first play. */
    private fun defaultBufferMs(): Long {
        return if (activeCodec == "opus") STARTUP_BUFFER_MS_OPUS else STARTUP_BUFFER_MS_LOSSLESS
    }

    /** Recovery after trouble: higher threshold for stability. */
    private fun recoveryBufferMs(): Long {
        val base = if (activeCodec == "opus") RECOVERY_BUFFER_MS_OPUS else RECOVERY_BUFFER_MS_LOSSLESS
        return base + if (isCellular) CELLULAR_RECOVERY_EXTRA_MS else 0L
    }

    // ── Stream boundary: full flush ──

    private fun flushForRebuffer() {
        hardBoundaryPending = true
        streamGeneration++
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        synchronized(codecLock) {
            audioTrack?.setVolume(0f)
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
        oversizedDropCount = 0L
        consecutiveDecodeFailures = 0
        discardUntilTimestampUs = 0L
        pendingAudioTrackFlush = false
    }

    // ── Configure (stream/start) ──

    override fun configure(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        startType: ProtocolStartType
    ) {
        hardBoundaryPending = false
        acceptGeneration = streamGeneration
        generationDropCount = 0
        val isNewStream = startType == ProtocolStartType.NEW_STREAM

        // Step A: semantic classification (protocol decides)
        // Step B: implementation strategy (engine decides based on codec/runtime)

        // Same codec + same params
        if (configured && codecName == activeCodec && sampleRate == activeSampleRate
            && channels == activeChannels && bitDepth == activeBitDepth
        ) {
            if (isNewStream) {
                configureGeneration++
                lastEnqueuedTimestampUs = 0L
                frameQueue.clear()
                frameQueueBytes.set(0)
                synchronized(codecLock) {
                    try { codec?.flush() } catch (_: Exception) {}
                }
                presentationTimeUs = 0L
                lastFrameReceivedMs = System.currentTimeMillis()
                holdoverEndTimestampUs = 0L
                pendingAudioTrackFlush = false
                playbackStarted = false
                requiredBufferMs = defaultBufferMs()
                if (!playbackActive || playbackThread?.isAlive != true) {
                    playbackActive = true
                    audioTrack?.let { startPlaybackThread(it) }
                }
                Log.d(TAG, "NEW_STREAM same-codec ($codecName), threshold=${requiredBufferMs}ms")
            } else {
                configureGeneration++
                lastFrameReceivedMs = System.currentTimeMillis()
                if (!playbackActive || playbackThread?.isAlive != true) {
                    playbackActive = true
                    audioTrack?.let { startPlaybackThread(it) }
                }
                Log.d(TAG, "CONTINUATION same-codec ($codecName), buf=${bufferDurationMs()}ms")
            }
            return
        }

        // Hot-swap: different codec, same audio format
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

            if (isNewStream) {
                frameQueue.clear()
                frameQueueBytes.set(0)
                lastEnqueuedTimestampUs = 0L
                presentationTimeUs = 0L
                playbackStarted = false
                requiredBufferMs = defaultBufferMs()
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                Log.d(TAG, "NEW_STREAM hot-swap: $codecName ${sampleRate}Hz/${bitDepth}bit")
            } else {
                presentationTimeUs = 0L
                Log.d(TAG, "CONTINUATION hot-swap: $codecName ${sampleRate}Hz/${bitDepth}bit, buf=${bufferDurationMs()}ms")
            }

            estimatedFrameDurationUs = 20_000L
            oversizedDropCount = 0L
            consecutiveDecodeFailures = 0
            configureGeneration++
            lastFrameReceivedMs = System.currentTimeMillis()
            activeCodec = codecName
            activeBitDepth = bitDepth
            return
        }

        // Full rebuild: sample rate or channels changed, need new AudioTrack
        val wasPlaying = configured && playbackStarted
        releaseInternal()
        activeCodec = codecName
        activeBitDepth = bitDepth
        activeSampleRate = sampleRate
        activeChannels = channels

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
        presentationTimeUs = 0L
        if (isNewStream || !wasPlaying) {
            requiredBufferMs = defaultBufferMs()
            Log.d(TAG, "NEW_STREAM rebuild: $codecName ${sampleRate}Hz/${bitDepth}bit ${channels}ch, threshold=${requiredBufferMs}ms")
        } else {
            requiredBufferMs = 100L
            Log.d(TAG, "CONTINUATION rebuild: $codecName ${sampleRate}Hz/${bitDepth}bit ${channels}ch, quick resume")
        }
        startPlaybackThread(createdAudioTrack)
    }

    // ── Binary frame input ──

    override fun onBinaryMessage(data: ByteArray, generation: Long) {
        if (!configured || !playbackActive) return
        if (generation != configureGeneration) return
        // Stream ownership: reject frames from old stream epoch
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

        if (discardUntilTimestampUs > 0L) {
            if (serverTimestampUs < discardUntilTimestampUs) return
            Log.d(TAG, "Holdover overlap discard complete at frame #$frameCount")
            discardUntilTimestampUs = 0L
        }

        lastFrameReceivedMs = System.currentTimeMillis()

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

    // ── Playback thread: dequeue, decode, write ──

    private fun startPlaybackThread(track: AudioTrack) {
        synchronized(playbackThreadLock) {
            if (playbackThread?.isAlive == true) return
            val generation = playbackGeneration
            playbackThread = Thread({
                try {
                    while ((playbackActive || frameQueue.isNotEmpty()) && generation == playbackGeneration) {
                        // Startup: wait for buffer threshold
                        if (!playbackStarted || syncState == SyncState.SYNC_ERROR_REBUFFERING || syncState == SyncState.IDLE) {
                            if (bufferDurationMs() >= requiredBufferMs) {
                                if (generation != playbackGeneration || track !== audioTrack || !playbackActive) break
                                if (!playbackStarted) {
                                    track.play()
                                    track.setVolume(if (isMuted) 0f else currentVolume)
                                    playbackStarted = true
                                }
                                transitionSyncState(SyncState.SYNCHRONIZED)
                                Log.d(TAG, "Synchronized (buf=${bufferDurationMs()}ms, threshold=${requiredBufferMs}ms)")
                            } else {
                                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                                continue
                            }
                        }

                        val frame = frameQueue.peek()
                        if (frame != null) {
                            frameQueue.poll()
                            frameQueueBytes.addAndGet(-frame.payload.size.toLong())
                            // Post-dequeue generation check
                            if (streamGeneration != acceptGeneration) continue
                            if (pendingAudioTrackFlush) {
                                if (holdoverEndTimestampUs > 0 && frame.serverTimestampUs < holdoverEndTimestampUs) {
                                    continue
                                }
                                pendingAudioTrackFlush = false
                                holdoverEndTimestampUs = 0L
                                transitionSyncState(SyncState.SYNCHRONIZED)
                                Log.d(TAG, "Reconnect aligned at frame ts=${frame.serverTimestampUs}")
                            }
                            lastPlayedServerTimestampUs = frame.serverTimestampUs
                            if (!decodeAndWrite(frame, track, generation)) {
                                consecutiveDecodeFailures++
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
                                    releaseInternal()
                                    transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                                    break
                                }
                                continue
                            }
                            consecutiveDecodeFailures = 0
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
                    Log.e(TAG, "Playback crashed: ${e::class.java.simpleName}: ${e.message}")
                    transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                    playbackStarted = false
                }
            }, "DirectPlayback").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    /**
     * Two-stage starvation:
     * 1s silence -> HOLDOVER (AudioTrack hw buffer keeps playing)
     * 2s silence -> REBUFFERING (pause + flush)
     */
    private fun checkStarvation(track: AudioTrack) {
        if (!playbackStarted) return
        if (syncState != SyncState.SYNCHRONIZED && syncState != SyncState.HOLDOVER_PLAYING_FROM_BUFFER) return
        if (!frameQueue.isEmpty()) return
        val silenceMs = System.currentTimeMillis() - lastFrameReceivedMs
        if (silenceMs <= 1000) return

        if (syncState == SyncState.SYNCHRONIZED) {
            Log.d(TAG, "Starvation stage1: silence=${silenceMs}ms, holdover")
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
        }

        if (silenceMs > 2000) {
            Log.d(TAG, "Starvation stage2: silence=${silenceMs}ms, rebuffering")
            pendingAudioTrackFlush = false
            requiredBufferMs = recoveryBufferMs()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            playbackStarted = false
            synchronized(codecLock) {
                try { track.setVolume(0f) } catch (_: Exception) {}
                try { track.pause(); track.flush() } catch (_: Exception) {}
                try { codec?.flush() } catch (_: Exception) {}
            }
            presentationTimeUs = 0L
        }
    }

    // ── Decode + write ──

    private fun decodeAndWrite(frame: EncodedFrame, track: AudioTrack, generation: Long): Boolean {
        val encodedData = frame.payload
        if (activeCodec == "pcm") {
            if (generation != playbackGeneration || track !== audioTrack || !playbackActive) return false
            val pcm = if (activeBitDepth == 24) convertPcm24To16(encodedData) else encodedData
            track.write(pcm, 0, pcm.size)
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
                        return true
                    }
                    inputBuffer.put(encodedData)
                    mc.queueInputBuffer(inputIndex, 0, encodedData.size, presentationTimeUs, 0)
                    presentationTimeUs += 20_000
                }
            }

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
                            } else null
                            mc.releaseOutputBuffer(outputIndex, false)
                            pcm
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ByteArray(0)
                        else -> null
                    }
                }
                when {
                    pcmToWrite == null -> break
                    pcmToWrite.isEmpty() -> continue
                    else -> {
                        if (generation != playbackGeneration || track !== audioTrack || mc !== codec || !playbackActive) break
                        track.write(pcmToWrite, 0, pcmToWrite.size)
                    }
                }
            }
            return true
        } catch (_: BufferOverflowException) {
            oversizedDropCount++
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
            else -> createOpusDecoder(sampleRate, channels, codecHeader)
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
        pendingAudioTrackFlush = false
        requiredBufferMs = defaultBufferMs()
        estimatedFrameDurationUs = 20_000L
        presentationTimeUs = 0L
        Log.d(TAG, "Buffer cleared")
    }

    override fun onStreamEnd() {
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
        pendingAudioTrackFlush = false
        requiredBufferMs = defaultBufferMs()
        estimatedFrameDurationUs = 20_000L
        presentationTimeUs = 0L
        Log.d(TAG, "Stream ended")
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason, flushing")
        flushForRebuffer()
    }

    override fun onTransportFailure() {
        val bufMs = bufferDurationMs()
        if (bufMs <= HOLDOVER_MIN_BUFFER_MS) {
            requiredBufferMs = recoveryBufferMs()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            Log.d(TAG, "Transport failure, rebuffering (buf=${bufMs}ms)")
        } else {
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            Log.d(TAG, "Transport failure, holdover (buf=${bufMs}ms)")
        }
    }

    override fun seedOutputLatency(persistedUs: Long) {
        if (persistedUs > 0) measuredOutputLatencyUs = persistedUs
    }

    override fun shiftAnchorForDelayChange(deltaMs: Int) {
        // No-op in solo mode
    }

    override fun release() { releaseInternal() }

    private fun releaseInternal() {
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
        synchronized(codecLock) {
            try { oldTrack?.release() } catch (_: Exception) {}
            try { oldCodec?.stop() } catch (_: Exception) {}
            try { oldCodec?.release() } catch (_: Exception) {}
        }
    }
}
