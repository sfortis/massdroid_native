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
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

enum class SyncState {
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
 */
class AudioStreamManager {

    companion object {
        private const val TAG = "AudioStream"
        private const val HEADER_SIZE = 9
        private const val MAX_ENCODED_BUFFER_BYTES = 8_000_000L // ~30s flac or ~minutes of opus
        private const val NORMAL_SYNC_BUFFER_MS_LOSSLESS = 2_000L
        private const val NORMAL_SYNC_BUFFER_MS_OPUS = 1_000L
        private const val RECOVERY_SYNC_BUFFER_MS = 5_000L
        private const val HOLDOVER_MIN_BUFFER_MS = 750L
        private const val HARD_MAX_LIVE_BUFFER_MS = 6_000L
        private const val LATE_CHUNK_DROP_GRACE_MS = 120L
    }

    private data class EncodedFrame(val serverTimestampUs: Long, val payload: ByteArray) :
        Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    // Encoded frame queue (filled by WS thread, consumed by playback thread)
    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private val codecLock = Any()

    // Audio output
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val playbackThreadLock = Any()

    // State
    private var configured = false
    @Volatile private var playbackActive = true
    @Volatile private var playbackStarted = false
    private var activeCodec = "opus"
    private var activeBitDepth = 16
    private var activeSampleRate = 0
    private var activeChannels = 0
    private var frameCount = 0
    private var decodedFrameCount = 0
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var pendingContinuityCheck = false
    @Volatile private var discardUntilTimestampUs = 0L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile private var forceDiscontinuityUntilMs = 0L
    @Volatile private var forceDiscontinuityReason = ""
    @Volatile private var requiredSyncBufferMs = NORMAL_SYNC_BUFFER_MS_LOSSLESS
    @Volatile private var lateDropCount = 0L
    private var presentationTimeUs = 0L

    // Sync state per spec
    @Volatile var syncState = SyncState.SYNC_ERROR_REBUFFERING; private set
    var onSyncStateChanged: ((SyncState) -> Unit)? = null

    // Clock sync
    @Volatile var clockOffsetUs: Long = 0L
    @Volatile var staticDelayMs: Int = 0

    fun bufferDurationMs(): Long {
        val headTs = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tailTs = lastEnqueuedTimestampUs
        if (tailTs <= 0L || tailTs < headTs) return 0L
        return ((tailTs - headTs + estimatedFrameDurationUs).coerceAtLeast(0L)) / 1000L
    }

    private fun nowLocalUs(): Long = System.nanoTime() / 1000L

    private fun targetLocalPlayUs(serverTimestampUs: Long): Long =
        serverTimestampUs - clockOffsetUs - (staticDelayMs.toLong() * 1000L)

    private fun leadToLocalNowMs(serverTimestampUs: Long): Long =
        (targetLocalPlayUs(serverTimestampUs) - nowLocalUs()) / 1000L

    private fun shouldDropLateFrame(serverTimestampUs: Long): Boolean {
        return leadToLocalNowMs(serverTimestampUs) < -LATE_CHUNK_DROP_GRACE_MS
    }

    private fun logLateDrop(serverTimestampUs: Long, source: String) {
        lateDropCount++
        if (lateDropCount <= 5 || lateDropCount % 100 == 0L) {
            Log.d(
                TAG,
                "Dropped late frame #$lateDropCount from $source: lead=${leadToLocalNowMs(serverTimestampUs)}ms buf=${bufferDurationMs()}ms"
            )
        }
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
            if (!playbackActive || playbackThread?.isAlive != true) {
                playbackActive = true
                playbackStarted = false
                audioTrack?.let { startPlaybackThread(it) }
            }
            pendingContinuityCheck = true
            Log.d(TAG, "Same codec stream/start, buf=${bufferDurationMs()}ms ($codecName)")
            return
        }

        // Different codec or first configure: full rebuild
        // Encoded frames from old codec can't be decoded with new codec, so clear queue
        release_internal()
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

        val createdCodec = when (codecName) {
            "opus" -> createOpusDecoder(sampleRate, channels, codecHeader)
            "flac" -> createFlacDecoder(sampleRate, channels, bitDepth, codecHeader)
            "pcm" -> null
            else -> {
                Log.w(TAG, "Unknown codec $codecName, falling back to opus")
                activeCodec = "opus"
                createOpusDecoder(sampleRate, channels, codecHeader)
            }
        }

        codec = createdCodec
        audioTrack = createdAudioTrack
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

    fun onBinaryMessage(data: ByteArray) {
        if (!configured || !playbackActive) return
        if (data.size < HEADER_SIZE) return

        frameCount++
        lastFrameReceivedMs = System.currentTimeMillis()
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

        if (shouldDropLateFrame(serverTimestampUs)) {
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
        when {
            lastTs == 0L -> {
                Log.d(TAG, "Reconnect: first-stream")
                transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            }
            gap in -5_000_000..5_000_000 -> {
                // Continuous: small overlap or forward gap. Keep buffer.
                Log.d(TAG, "Reconnect: CONTINUOUS (${gap / 1000}ms), buf=${bufMs}ms kept")
            }
            gap > 5_000_000 -> {
                // Large forward jump (new track/seek)
                Log.d(TAG, "Reconnect: FORWARD(${gap / 1000}ms), buf=${bufMs}ms flushed")
                flushForRebuffer()
            }
            gap < 0 && syncState == SyncState.HOLDOVER_PLAYING_FROM_BUFFER && bufMs > HOLDOVER_MIN_BUFFER_MS -> {
                discardUntilTimestampUs = lastTs
                Log.d(TAG, "Reconnect: HOLDOVER_OVERLAP(${gap / 1000}ms), buf=${bufMs}ms, discarding until tail")
            }
            else -> {
                // Protocol baseline: reconnect should deliver future chunks only.
                // Keep the queue and let playout-time late-drop discard anything stale.
                Log.d(TAG, "Reconnect: OVERLAP(${gap / 1000}ms), buf=${bufMs}ms kept")
            }
        }
    }

    private fun flushForRebuffer() {
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        frameQueue.clear()
        frameQueueBytes.set(0)
        playbackStarted = false
        lastEnqueuedTimestampUs = 0L
        discardUntilTimestampUs = 0L
        synchronized(codecLock) {
            audioTrack?.pause()
            audioTrack?.flush()
            codec?.flush()
        }
        presentationTimeUs = 0L
        estimatedFrameDurationUs = 20_000L
    }

    // ── Playback thread: decode + write (blocking pacing) ──

    private fun startPlaybackThread(track: AudioTrack) {
        synchronized(playbackThreadLock) {
            val existing = playbackThread
            if (existing?.isAlive == true) return
            playbackThread = Thread({
            while (playbackActive || frameQueue.isNotEmpty()) {
                // Wait for enough encoded buffer before starting
                if (!playbackStarted || syncState == SyncState.SYNC_ERROR_REBUFFERING) {
                    if (bufferDurationMs() >= requiredSyncBufferMs) {
                        if (!playbackStarted) {
                            track.play()
                            playbackStarted = true
                        }
                        transitionSyncState(SyncState.SYNCHRONIZED)
                        Log.d(TAG, "Synchronized (${frameQueueBytes.get() / 1000}KB, ~${bufferDurationMs()}ms, threshold=${requiredSyncBufferMs}ms)")
                    } else {
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        continue
                    }
                }

                val frame = frameQueue.peek()
                if (frame != null) {
                    frameQueue.poll()
                    frameQueueBytes.addAndGet(-frame.payload.size.toLong())
                    if (shouldDropLateFrame(frame.serverTimestampUs)) {
                        logLateDrop(frame.serverTimestampUs, "playout")
                        continue
                    }
                    decodeAndWrite(frame.payload, track)

                    // Starvation check: no frames for >1s AND queue empty
                    val silenceMs = System.currentTimeMillis() - lastFrameReceivedMs
                    if (playbackStarted &&
                        (syncState == SyncState.SYNCHRONIZED || syncState == SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
                        && frameQueue.isEmpty() && silenceMs > 1000
                    ) {
                        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
                        playbackStarted = false
                        synchronized(codecLock) {
                            try { track.pause(); track.flush() } catch (_: Exception) {}
                            try { codec?.flush() } catch (_: Exception) {}
                        }
                        presentationTimeUs = 0L
                        Log.d(TAG, "Starvation (${silenceMs}ms silence), rebuffering")
                    }
                } else if (!playbackActive) {
                    break
                } else {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                }
            }
            }, "AudioPlayback").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private fun decodeAndWrite(encodedData: ByteArray, track: AudioTrack) {
        if (activeCodec == "pcm") {
            val pcm = if (activeBitDepth == 24) convertPcm24To16(encodedData) else encodedData
            track.write(pcm, 0, pcm.size)
            decodedFrameCount++
            return
        }

        val mc = codec ?: return
        try {
            synchronized(codecLock) {
                val inputIndex = mc.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                    inputBuffer.clear()
                    inputBuffer.put(encodedData)
                    mc.queueInputBuffer(inputIndex, 0, encodedData.size, presentationTimeUs, 0)
                    presentationTimeUs += 20_000
                }

                // Drain all decoded PCM to AudioTrack
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    val outputIndex = mc.dequeueOutputBuffer(bufferInfo, 1000)
                    when {
                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = mc.getOutputBuffer(outputIndex) ?: break
                                val pcm = ByteArray(bufferInfo.size)
                                outputBuffer.get(pcm)
                                track.write(pcm, 0, pcm.size) // blocks when AudioTrack full = pacing
                                decodedFrameCount++
                            }
                            mc.releaseOutputBuffer(outputIndex, false)
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = mc.outputFormat
                            Log.d(TAG, "Output: ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                        }
                        else -> break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    // ── Codec factories ──

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
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
                playbackActive = false
                playbackThread?.interrupt()
                playbackThread.also { playbackThread = null }
            }
            try { threadToJoin?.join(500) } catch (_: Exception) {}
            frameQueue.clear(); frameQueueBytes.set(0)
            try { audioTrack?.stop(); audioTrack?.flush() } catch (_: Exception) {}
            playbackStarted = false
        } else {
            playbackActive = true; playbackStarted = false
            audioTrack?.let { startPlaybackThread(it) }
        }
    }

    fun setVolume(volume: Float) { audioTrack?.setVolume(volume.coerceIn(0f, 1f)) }
    fun setMuted(muted: Boolean) { audioTrack?.setVolume(if (muted) 0f else 1f) }

    fun clearBuffer() {
        frameQueue.clear(); frameQueueBytes.set(0)
        playbackStarted = false
        lastEnqueuedTimestampUs = 0L
        discardUntilTimestampUs = 0L
        pendingContinuityCheck = false
        forceDiscontinuityUntilMs = 0L
        forceDiscontinuityReason = ""
        requiredSyncBufferMs = defaultSyncBufferMs()
        estimatedFrameDurationUs = 20_000L
        if (playbackActive) {
            synchronized(codecLock) {
                audioTrack?.pause()
                audioTrack?.flush()
                codec?.flush()
            }
        } else {
            synchronized(codecLock) {
                codec?.flush()
            }
        }
        presentationTimeUs = 0L
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        Log.d(TAG, "Buffer cleared")
    }

    fun onStreamEnd() {
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
        synchronized(codecLock) {
            try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
            try { codec?.flush() } catch (_: Exception) {}
        }
        presentationTimeUs = 0L
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
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
        requiredSyncBufferMs = RECOVERY_SYNC_BUFFER_MS
        val bufMs = bufferDurationMs()
        if (bufMs <= HOLDOVER_MIN_BUFFER_MS) {
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            Log.d(TAG, "Transport failure, insufficient holdover (${bufMs}ms), recovery threshold=${requiredSyncBufferMs}ms")
        } else {
            transitionSyncState(SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            Log.d(TAG, "Transport failure, holdover active (${bufMs}ms), recovery threshold=${requiredSyncBufferMs}ms")
        }
    }

    fun release() { release_internal() }

    private fun release_internal() {
        playbackActive = false; configured = false; playbackStarted = false
        val threadToJoin = synchronized(playbackThreadLock) {
            playbackThread?.interrupt()
            playbackThread.also { playbackThread = null }
        }
        try { threadToJoin?.join(500) } catch (_: Exception) {}
        frameQueue.clear(); frameQueueBytes.set(0)
        synchronized(codecLock) {
            try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
        audioTrack = null; codec = null
    }
}
