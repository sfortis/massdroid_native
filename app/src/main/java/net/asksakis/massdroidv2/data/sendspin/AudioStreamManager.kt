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
    private var activeCodec = "opus"
    private var activeBitDepth = 16

    fun configure(
        codecName: String = "opus",
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: String? = null
    ) {
        codecLock.lock()
        try {
            release_internal()
            activeCodec = codecName
            activeBitDepth = bitDepth

            val channelConfig = if (channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }

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
                "pcm" -> null // PCM needs no decoder
                else -> {
                    Log.w(TAG, "Unknown codec $codecName, falling back to opus")
                    activeCodec = "opus"
                    createOpusDecoder(sampleRate, channels, codecHeader)
                }
            }

            createdAudioTrack.play()
            codec = createdCodec
            audioTrack = createdAudioTrack
            configured = true
            playbackActive = true
            frameCount = 0
            decodedFrameCount = 0
            Log.d(TAG, "Audio pipeline configured: $codecName ${sampleRate}Hz/${bitDepth}bit ${channels}ch, buffer=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio pipeline", e)
            release_internal()
            throw e
        } finally {
            codecLock.unlock()
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)

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

        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd1.putLong(preSkipNs)
        csd1.rewind()
        format.setByteBuffer("csd-1", csd1)

        val seekPreRollNs = 80_000_000L
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd2.putLong(seekPreRollNs)
        csd2.rewind()
        format.setByteBuffer("csd-2", csd2)

        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createFlacDecoder(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?
    ): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger("bit-depth", bitDepth)

        if (codecHeader != null) {
            try {
                val headerBytes = Base64.decode(codecHeader, Base64.DEFAULT)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(headerBytes))
                Log.d(TAG, "FLAC CSD-0: ${headerBytes.size} bytes")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode FLAC codec_header: ${e.message}")
            }
        }

        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OpusHead".toByteArray())
        header.put(1) // version
        header.put(channels.toByte())
        header.putShort(3840.toShort()) // pre-skip
        header.putInt(sampleRate)
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

        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        if (payload.isEmpty()) {
            Log.w(TAG, "Empty payload in frame #$frameCount")
            return
        }

        if (frameCount <= 5) {
            val type = data[0].toInt() and 0xFF
            Log.d(TAG, "Frame #$frameCount ($activeCodec): type=$type, total=${data.size}, payload=${payload.size}")
        }
        if (frameCount % 500 == 0) {
            Log.d(TAG, "Frame #$frameCount ($activeCodec): payload=${payload.size}, decoded=$decodedFrameCount")
        }

        if (!codecLock.tryLock()) return
        try {
            if (activeCodec == "pcm") {
                playPcm(payload)
            } else {
                decodeAndPlay(payload)
            }
        } finally {
            codecLock.unlock()
        }
    }

    private fun playPcm(pcmData: ByteArray) {
        val track = audioTrack ?: return
        val output = if (activeBitDepth == 24) convertPcm24To16(pcmData) else pcmData
        track.write(output, 0, output.size)
        decodedFrameCount++
    }

    private fun convertPcm24To16(data: ByteArray): ByteArray {
        val sampleCount = data.size / 3
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            // 24-bit LE: take high 2 bytes (bytes 1,2 of each 3-byte sample)
            out[i * 2] = data[i * 3 + 1]
            out[i * 2 + 1] = data[i * 3 + 2]
        }
        return out
    }

    private fun decodeAndPlay(encodedData: ByteArray) {
        val mc = codec ?: return
        val track = audioTrack ?: return

        try {
            val inputIndex = mc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(encodedData)
                mc.queueInputBuffer(inputIndex, 0, encodedData.size, presentationTimeUs, 0)
                presentationTimeUs += 20_000
            } else {
                if (frameCount <= 20) {
                    Log.w(TAG, "No input buffer for frame #$frameCount")
                }
            }

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
