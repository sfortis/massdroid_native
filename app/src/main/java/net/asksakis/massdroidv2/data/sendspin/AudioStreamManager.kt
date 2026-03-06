package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock

class AudioStreamManager {

    companion object {
        private const val TAG = "AudioStream"
        private const val HEADER_SIZE = 9 // 1 byte type + 8 bytes timestamp
    }

    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var configured = false
    @Volatile private var playbackActive = true
    private var frameCount = 0
    private var decodedFrameCount = 0
    private val codecLock = ReentrantLock()

    fun configure(sampleRate: Int = 48000, channels: Int = 2, codecHeader: String? = null) {
        codecLock.lock()
        try {
            release_internal()

            val channelConfig = if (channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }

            // Create AudioTrack
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4

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
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Create Opus MediaCodec decoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)

            // CSD-0: OpusHead header
            val csd0 = if (codecHeader != null) {
                try {
                    Base64.decode(codecHeader, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode codec_header, using default OpusHead")
                    createOpusHeader(channels, sampleRate)
                }
            } else {
                createOpusHeader(channels, sampleRate)
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            Log.d(TAG, "CSD-0 OpusHead: ${csd0.size} bytes, first=${csd0.take(8).map { it.toInt() and 0xFF }}")

            // CSD-1: Pre-skip in nanoseconds (64-bit native byte order)
            val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong() // 80ms for 48kHz
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
            csd1.putLong(preSkipNs)
            csd1.rewind()
            format.setByteBuffer("csd-1", csd1)

            // CSD-2: Seek pre-roll in nanoseconds (64-bit native byte order)
            val seekPreRollNs = 80_000_000L // 80ms
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
            csd2.putLong(seekPreRollNs)
            csd2.rewind()
            format.setByteBuffer("csd-2", csd2)

            val createdCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(format, null, null, 0)
                start()
            }

            createdAudioTrack.play()
            codec = createdCodec
            audioTrack = createdAudioTrack
            configured = true
            playbackActive = true
            frameCount = 0
            decodedFrameCount = 0
            Log.d(TAG, "Audio pipeline configured: ${sampleRate}Hz ${channels}ch, buffer=$bufferSize, preSkip=${preSkipNs}ns")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio pipeline", e)
            release_internal()
            throw e
        } finally {
            codecLock.unlock()
        }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray {
        // Minimal OpusHead per RFC 7845
        val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OpusHead".toByteArray()) // magic
        header.put(1) // version
        header.put(channels.toByte()) // channel count
        header.putShort(3840.toShort()) // pre-skip
        header.putInt(sampleRate) // input sample rate
        header.putShort(0) // output gain
        header.put(0) // channel mapping family
        return header.array()
    }

    fun onBinaryMessage(data: ByteArray) {
        if (!configured || !playbackActive) return
        if (data.size < HEADER_SIZE) {
            Log.w(TAG, "Binary msg too small: ${data.size} bytes")
            return
        }

        frameCount++

        val opusPayload = data.copyOfRange(HEADER_SIZE, data.size)
        if (opusPayload.isEmpty()) {
            Log.w(TAG, "Empty opus payload in frame #$frameCount")
            return
        }

        if (frameCount <= 5) {
            val type = data[0].toInt() and 0xFF
            Log.d(TAG, "Frame #$frameCount: type=$type, total=${data.size}, " +
                    "payload=${opusPayload.size}, TOC=0x${"%02X".format(opusPayload[0])}")
        }
        if (frameCount % 500 == 0) {
            Log.d(TAG, "Frame #$frameCount: payload=${opusPayload.size}, decoded=$decodedFrameCount")
        }

        // tryLock: skip frame if codec is being flushed/reconfigured
        if (!codecLock.tryLock()) return
        try {
            decodeAndPlay(opusPayload)
        } finally {
            codecLock.unlock()
        }
    }

    private fun decodeAndPlay(opusData: ByteArray) {
        val mc = codec ?: return
        val track = audioTrack ?: return

        try {
            // Feed opus data to decoder
            val inputIndex = mc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(opusData)
                mc.queueInputBuffer(inputIndex, 0, opusData.size, presentationTimeUs, 0)
                presentationTimeUs += 20_000 // 20ms per Opus frame
            } else {
                if (frameCount <= 20) {
                    Log.w(TAG, "No input buffer for frame #$frameCount")
                }
            }

            // ALWAYS try to drain output, even if input wasn't queued
            drainOutput(mc, track)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    private var presentationTimeUs = 0L

    private fun drainOutput(mc: MediaCodec, track: AudioTrack) {
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
                            track.write(pcmData, 0, pcmData.size)
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
        } catch (_: IllegalStateException) {
            // Codec was flushed/stopped while draining, ignore
        }
    }

    fun setPaused(paused: Boolean) {
        if (!configured) return
        if (paused) {
            // Set flag FIRST to immediately block new frames on any thread
            playbackActive = false
            try {
                audioTrack?.stop()
                audioTrack?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "setPaused(true) error: ${e.message}")
            }
            Log.d(TAG, "Playback stopped, buffer flushed")
        } else {
            try {
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(TAG, "setPaused(false) error: ${e.message}")
            }
            // Set flag AFTER track is ready to accept data
            playbackActive = true
            Log.d(TAG, "Playback resumed")
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setVolume(if (muted) 0f else 1f)
    }

    fun clearBuffer() {
        codecLock.lock()
        try {
            if (playbackActive) {
                audioTrack?.pause()
                audioTrack?.flush()
                codec?.flush()
                audioTrack?.play()
            } else {
                // Paused/stopped: only flush codec, don't restart AudioTrack
                // (Samsung BT stack monitors AudioTrack state for AVRCP)
                codec?.flush()
            }
            Log.d(TAG, "Buffer cleared (playbackActive=$playbackActive)")
        } catch (e: Exception) {
            Log.e(TAG, "Clear buffer error: ${e.message}")
        } finally {
            codecLock.unlock()
        }
    }

    fun release() {
        codecLock.lock()
        try {
            release_internal()
        } finally {
            codecLock.unlock()
        }
    }

    // Must be called with codecLock held
    private fun release_internal() {
        playbackActive = false
        configured = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        audioTrack = null
        codec = null
        Log.d(TAG, "Audio pipeline released")
    }
}
