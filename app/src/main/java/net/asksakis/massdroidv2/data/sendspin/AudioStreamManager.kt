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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

class AudioStreamManager {

    companion object {
        private const val TAG = "AudioStream"
        private const val HEADER_SIZE = 9 // 1 byte type + 8 bytes timestamp
        private const val MAX_BUFFER_BYTES = 5_500_000L // ~30s PCM at 48kHz/stereo/16bit
        private const val PRE_FILL_BYTES = 400_000L // ~2s, must exceed AudioTrack buffer to prevent drain
    }

    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var configured = false
    @Volatile private var playbackActive = true
    @Volatile private var playbackStarted = false
    private var frameCount = 0
    private var decodedFrameCount = 0
    private val codecLock = ReentrantLock()
    private var activeCodec = "opus"
    private var activeBitDepth = 16

    private data class TimestampedChunk(val playAtUs: Long, val pcm: ByteArray)

    private val pcmQueue = ConcurrentLinkedQueue<TimestampedChunk>()
    private val pcmQueueBytes = AtomicLong(0)
    private var playbackThread: Thread? = null
    @Volatile var clockOffsetUs: Long = 0L
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var pendingStreamRestart = false

    private var activeSampleRate = 0
    private var activeChannels = 0

    fun configure(
        codecName: String = "opus",
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: String? = null
    ) {
        codecLock.lock()
        try {
            if (configured && codecName == activeCodec && sampleRate == activeSampleRate
                && channels == activeChannels && bitDepth == activeBitDepth
            ) {
                // Same codec: keep pipeline + buffer, check timestamp continuity on next frame
                pendingStreamRestart = true
                codec?.flush()
                frameCount = 0
                decodedFrameCount = 0
                Log.d(TAG, "Same codec stream restart, checking timestamp continuity ($codecName)")
                return
            }
            // Codec change: rebuild decoder only, keep AudioTrack + PCM buffer
            val codecOnly = configured && sampleRate == activeSampleRate && channels == activeChannels
            if (codecOnly) {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                codec = null
                activeCodec = codecName
                activeBitDepth = bitDepth
                pendingStreamRestart = true
                codec = when (codecName) {
                    "opus" -> createOpusDecoder(sampleRate, channels, codecHeader)
                    "flac" -> createFlacDecoder(sampleRate, channels, bitDepth, codecHeader)
                    "pcm" -> null
                    else -> {
                        activeCodec = "opus"
                        createOpusDecoder(sampleRate, channels, codecHeader)
                    }
                }
                frameCount = 0
                decodedFrameCount = 0
                Log.d(TAG, "Codec switch $activeCodec -> $codecName, buffer preserved (${bufferDurationMs()}ms)")
                return
            }

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
            startPlaybackThread(createdAudioTrack)
            Log.d(TAG, "Audio pipeline configured: $codecName ${sampleRate}Hz/${bitDepth}bit ${channels}ch, " +
                    "trackBuffer=$bufferSize, pcmBuffer=${MAX_BUFFER_BYTES / 1_000_000}MB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio pipeline", e)
            release_internal()
            throw e
        } finally {
            codecLock.unlock()
        }
    }

    private fun startPlaybackThread(track: AudioTrack) {
        playbackThread = Thread({
            while (playbackActive || pcmQueue.isNotEmpty()) {
                if (!playbackStarted) {
                    if (pcmQueueBytes.get() >= PRE_FILL_BYTES) {
                        track.play()
                        playbackStarted = true
                        Log.d(TAG, "Pre-fill complete, playback started (${pcmQueueBytes.get() / 1000}KB, ${bufferDurationMs()}ms)")
                    } else {
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        continue
                    }
                }
                val chunk = pcmQueue.poll()
                if (chunk != null) {
                    pcmQueueBytes.addAndGet(-chunk.pcm.size.toLong())
                    try {
                        // AudioTrack.write() blocks when buffer full, providing natural pacing
                        track.write(chunk.pcm, 0, chunk.pcm.size)
                    } catch (_: Exception) { break }
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

    private var currentChunkTimestampUs = 0L

    private fun enqueuePcm(playAtUs: Long, pcmData: ByteArray) {
        if (pcmQueueBytes.get() >= MAX_BUFFER_BYTES) return
        pcmQueue.offer(TimestampedChunk(playAtUs, pcmData))
        pcmQueueBytes.addAndGet(pcmData.size.toLong())
        lastEnqueuedTimestampUs = playAtUs + clockOffsetUs // store as server timestamp
    }

    fun bufferDurationMs(): Long = pcmQueueBytes.get() * 1000 / 192000

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        val csd0 = if (codecHeader != null) {
            try { Base64.decode(codecHeader, Base64.DEFAULT) }
            catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header, using default OpusHead")
                createOpusHeader(channels, sampleRate)
            }
        } else createOpusHeader(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        Log.d(TAG, "CSD-0 OpusHead: ${csd0.size} bytes, first=${csd0.take(8).map { it.toInt() and 0xFF }}")
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd1.putLong(preSkipNs); csd1.rewind()
        format.setByteBuffer("csd-1", csd1)
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd2.putLong(80_000_000L); csd2.rewind()
        format.setByteBuffer("csd-2", csd2)
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun createFlacDecoder(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger("bit-depth", bitDepth)
        if (codecHeader != null) {
            try {
                val headerBytes = Base64.decode(codecHeader, Base64.DEFAULT)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(headerBytes))
                Log.d(TAG, "FLAC CSD-0: ${headerBytes.size} bytes")
            } catch (e: Exception) { Log.w(TAG, "Failed to decode FLAC codec_header: ${e.message}") }
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OpusHead".toByteArray())
        header.put(1); header.put(channels.toByte())
        header.putShort(3840.toShort()); header.putInt(sampleRate)
        header.putShort(0); header.put(0)
        return header.array()
    }

    private fun parseTimestampUs(data: ByteArray): Long {
        // Bytes 1-8: big-endian int64 (server clock microseconds)
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (data[i].toLong() and 0xFF)
        return ts
    }

    fun onBinaryMessage(data: ByteArray) {
        if (!configured || !playbackActive) return
        if (data.size < HEADER_SIZE) return

        frameCount++
        val serverTimestampUs = parseTimestampUs(data)
        val playAtUs = serverTimestampUs - clockOffsetUs
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        if (payload.isEmpty()) return

        // Check timestamp continuity on stream restart
        if (pendingStreamRestart) {
            pendingStreamRestart = false
            val lastTs = lastEnqueuedTimestampUs
            val gap = serverTimestampUs - lastTs
            if (lastTs > 0 && gap in -5_000_000..5_000_000) {
                // Continuous: keep buffer, append new frames
                Log.d(TAG, "Timestamp continuous (gap=${gap / 1000}ms), keeping ${bufferDurationMs()}ms buffer")
            } else {
                // Discontinuous: new position, clear buffer
                Log.d(TAG, "Timestamp discontinuous (gap=${gap / 1000}ms), clearing buffer")
                pcmQueue.clear()
                pcmQueueBytes.set(0)
                playbackStarted = false
                audioTrack?.pause()
                audioTrack?.flush()
            }
        }

        if (frameCount <= 50 || frameCount % 500 == 0) {
            Log.d(TAG, "Frame #$frameCount ($activeCodec): payload=${payload.size}, buf=${bufferDurationMs()}ms")
        }

        if (!codecLock.tryLock()) return
        try {
            currentChunkTimestampUs = playAtUs
            if (activeCodec == "pcm") {
                val output = if (activeBitDepth == 24) convertPcm24To16(payload) else payload
                enqueuePcm(playAtUs, output)
                decodedFrameCount++
            } else {
                decodeAndEnqueue(payload)
            }
        } finally { codecLock.unlock() }
    }

    private fun convertPcm24To16(data: ByteArray): ByteArray {
        val sampleCount = data.size / 3
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            out[i * 2] = data[i * 3 + 1]
            out[i * 2 + 1] = data[i * 3 + 2]
        }
        return out
    }

    private fun decodeAndEnqueue(encodedData: ByteArray) {
        val mc = codec ?: return
        try {
            val inputIndex = mc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear(); inputBuffer.put(encodedData)
                mc.queueInputBuffer(inputIndex, 0, encodedData.size, presentationTimeUs, 0)
                presentationTimeUs += 20_000
            }
            drainDecoder(mc)
        } catch (e: Exception) { Log.e(TAG, "Decode error: ${e.message}") }
    }

    private var presentationTimeUs = 0L

    private fun drainDecoder(mc: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val outputIndex = mc.dequeueOutputBuffer(bufferInfo, 1000)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = mc.getOutputBuffer(outputIndex) ?: break
                            val pcmData = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmData)
                            enqueuePcm(currentChunkTimestampUs, pcmData)
                            decodedFrameCount++
                        }
                        mc.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = mc.outputFormat
                        Log.d(TAG, "Output format changed: sampleRate=${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}, " +
                                "channels=${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}")
                    }
                    else -> break
                }
            }
        } catch (_: IllegalStateException) {}
    }

    fun setPaused(paused: Boolean) {
        if (!configured) return
        if (paused) {
            playbackActive = false
            try { playbackThread?.join(500) } catch (_: Exception) {}
            playbackThread = null
            pcmQueue.clear(); pcmQueueBytes.set(0)
            try { audioTrack?.stop(); audioTrack?.flush() } catch (e: Exception) {
                Log.e(TAG, "setPaused(true) error: ${e.message}")
            }
            playbackStarted = false
            Log.d(TAG, "Playback stopped, buffer flushed")
        } else {
            playbackActive = true
            playbackStarted = false
            audioTrack?.let { startPlaybackThread(it) }
            Log.d(TAG, "Playback resumed (pre-filling)")
        }
    }

    fun setVolume(volume: Float) { audioTrack?.setVolume(volume.coerceIn(0f, 1f)) }
    fun setMuted(muted: Boolean) { audioTrack?.setVolume(if (muted) 0f else 1f) }

    fun clearBuffer() {
        codecLock.lock()
        try {
            pcmQueue.clear(); pcmQueueBytes.set(0); playbackStarted = false
            if (playbackActive) {
                audioTrack?.pause(); audioTrack?.flush(); codec?.flush()
            } else { codec?.flush() }
            Log.d(TAG, "Buffer cleared")
        } catch (e: Exception) { Log.e(TAG, "Clear buffer error: ${e.message}") }
        finally { codecLock.unlock() }
    }

    fun release() {
        codecLock.lock()
        try { release_internal() } finally { codecLock.unlock() }
    }

    private fun release_internal() {
        playbackActive = false; configured = false; playbackStarted = false
        try { playbackThread?.join(500) } catch (_: Exception) {}
        playbackThread = null
        pcmQueue.clear(); pcmQueueBytes.set(0)
        try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        audioTrack = null; codec = null
        Log.d(TAG, "Audio pipeline released")
    }
}
