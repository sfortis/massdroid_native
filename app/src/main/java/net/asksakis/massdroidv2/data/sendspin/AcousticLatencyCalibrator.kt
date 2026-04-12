package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Measures acoustic round-trip latency by playing short 1kHz beeps and
 * detecting them in a continuous microphone recording with bandpass filtering.
 *
 * Recording runs on a dedicated audio-priority thread, fully decoupled from
 * playback. A prebuilt PCM buffer (beeps + silence) is played in one shot.
 */
class AcousticLatencyCalibrator {

    companion object {
        private const val TAG = "AcousticCal"
        private const val SAMPLE_RATE = 48000
        private const val TONE_COUNT = 6
        private const val TONE_DURATION_MS = 100
        private const val TONE_SPACING_MS = 500
        private const val TONE_FREQ_HZ = 1000
        private const val TONE_AMPLITUDE = 0.8f

        // Sharp attack/release ramp (2ms = 96 samples) to avoid click but keep onset sharp
        private const val RAMP_SAMPLES = 96

        // Bandpass filter: 2nd order IIR centered at 1kHz, Q~5 for 48kHz
        private const val BP_B0 = 0.06206
        private const val BP_B2 = -0.06206
        private const val BP_A1 = -1.86891
        private const val BP_A2 = 0.87588

        // Envelope detection
        private const val ENV_ATTACK = 0.05
        private const val ENV_RELEASE = 0.0005

        private const val ONSET_THRESHOLD_FRACTION = 0.15f
        private const val MIN_PEAK_ENVELOPE = 0.003f

        private const val MIN_DELAY_MS = 3
        private const val MAX_DELAY_MS_DEFAULT = 500
        private const val MIN_TONES_DETECTED = 4
        private const val MAX_VARIANCE_MS = 8.0
        private const val MIN_SNR_DB = 6.0f

        private const val PRE_ROLL_MS = 300
        private const val TAIL_MS = 600
    }

    data class CalibrationResult(
        val roundTripUs: Long,
        val detectedTones: Int,
        val varianceMs: Double,
        val snrDb: Float,
        val quality: Quality
    )

    enum class Quality { GOOD, MARGINAL, FAILED }

    var onProgress: ((toneIndex: Int, total: Int) -> Unit)? = null

    suspend fun measureRoundTrip(
        maxDelayMs: Int = MAX_DELAY_MS_DEFAULT
    ): CalibrationResult = withContext(Dispatchers.Default) {

        // Build the entire playback buffer upfront: preroll silence + N * (tone + spacing) + tail
        val preRollSamples = (PRE_ROLL_MS * SAMPLE_RATE) / 1000
        val toneSamples = (TONE_DURATION_MS * SAMPLE_RATE) / 1000
        val spacingSamples = (TONE_SPACING_MS * SAMPLE_RATE) / 1000
        val tailSamples = (TAIL_MS * SAMPLE_RATE) / 1000
        val totalPlaySamples = preRollSamples +
            TONE_COUNT * (toneSamples + spacingSamples) + tailSamples

        val playBuffer = ShortArray(totalPlaySamples)
        val toneTemplate = generateTone()
        val toneOffsets = IntArray(TONE_COUNT)  // sample offset of each tone in playBuffer

        var pos = preRollSamples
        for (i in 0 until TONE_COUNT) {
            toneOffsets[i] = pos
            toneTemplate.copyInto(playBuffer, pos)
            pos += toneSamples + spacingSamples
        }

        // Recording buffer: same total duration
        val totalRecordSamples = totalPlaySamples + (SAMPLE_RATE / 2) // extra 500ms safety
        val recordBuffer = ShortArray(totalRecordSamples)
        val recordPos = AtomicInteger(0)
        val recordingDone = AtomicBoolean(false)
        val progressCallback = AtomicInteger(0)

        var audioTrack: AudioTrack? = null
        var audioRecord: AudioRecord? = null

        try {
            // Create low-latency AudioTrack
            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            // Create low-latency AudioRecord with best available source
            audioRecord = createLowLatencyAudioRecord()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return@withContext failedResult()
            }

            // Start continuous recording on dedicated audio-priority thread
            val recordThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioRecord.startRecording()
                var p = 0
                val chunkSize = SAMPLE_RATE / 100  // 10ms chunks
                while (!recordingDone.get() && p < recordBuffer.size) {
                    val toRead = chunkSize.coerceAtMost(recordBuffer.size - p)
                    val read = audioRecord.read(recordBuffer, p, toRead)
                    if (read > 0) {
                        p += read
                        recordPos.set(p)
                    } else break
                }
                audioRecord.stop()
            }, "AcousticCal-Record").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // Wait for pre-roll recording to settle
            while (recordPos.get() < preRollSamples) {
                Thread.sleep(5)
            }

            // Capture recording position at playback start to align timelines
            val recordPosAtPlayStart = recordPos.get()
            Log.d(TAG, "Playback starting at recordPos=$recordPosAtPlayStart")

            // Play the prebuilt buffer on audio-priority thread
            val playThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioTrack.play()
                // Write in chunks, report progress per tone
                var written = 0
                val writeChunk = SAMPLE_RATE / 20  // 50ms chunks
                while (written < playBuffer.size) {
                    val toWrite = writeChunk.coerceAtMost(playBuffer.size - written)
                    audioTrack.write(playBuffer, written, toWrite)
                    written += toWrite
                    // Update progress based on which tone we've passed
                    for (i in 0 until TONE_COUNT) {
                        if (written >= toneOffsets[i] + toneSamples) {
                            val prev = progressCallback.get()
                            if (i + 1 > prev) {
                                progressCallback.set(i + 1)
                                onProgress?.invoke(i + 1, TONE_COUNT)
                            }
                        }
                    }
                }
                audioTrack.stop()
            }, "AcousticCal-Play").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // Wait for playback to finish
            playThread.join(15000)
            // Let recording capture tail
            Thread.sleep(TAIL_MS.toLong())
            recordingDone.set(true)
            recordThread.join(2000)

            Log.d(TAG, "Recording complete: ${recordPos.get()} samples captured")

            // Bandpass filter
            val filtered = bandpassFilter(recordBuffer, recordPos.get())

            // Envelope
            val envelope = computeEnvelope(filtered)

            val peakEnvelope = envelope.max()
            if (peakEnvelope < MIN_PEAK_ENVELOPE) {
                Log.w(TAG, "Signal too weak: peak=${"%.6f".format(peakEnvelope)}")
                return@withContext failedResult()
            }

            // Noise floor from pre-roll
            val noiseFloor = if (preRollSamples > 0) {
                envelope.take(preRollSamples).average().toFloat()
            } else 0f
            val snrDb = if (noiseFloor > 0) 20f * kotlin.math.log10(peakEnvelope / noiseFloor) else 40f

            val threshold = noiseFloor + (peakEnvelope - noiseFloor) * ONSET_THRESHOLD_FRACTION
            val delays = mutableListOf<Long>()
            val minDelaySamp = (MIN_DELAY_MS * SAMPLE_RATE) / 1000
            val maxDelaySamp = (maxDelayMs * SAMPLE_RATE) / 1000

            for (i in 0 until TONE_COUNT) {
                // toneOffsets[i] is in playback-buffer coordinates.
                // In recording-buffer coordinates, tone was sent at:
                val recToneStart = recordPosAtPlayStart + toneOffsets[i]
                val searchStart = recToneStart + minDelaySamp
                val searchEnd = (recToneStart + maxDelaySamp).coerceAtMost(envelope.size)
                if (searchStart >= searchEnd) continue

                var onsetSample = -1
                for (s in searchStart until searchEnd) {
                    if (envelope[s] > threshold) {
                        onsetSample = s
                        break
                    }
                }

                if (onsetSample >= 0) {
                    val delaySamp = onsetSample - recToneStart
                    val delayUs = (delaySamp * 1_000_000L) / SAMPLE_RATE
                    delays.add(delayUs)
                    Log.d(TAG, "Tone $i: delay=${delayUs / 1000}ms (recOffset=$recToneStart onset=$onsetSample)")
                } else {
                    Log.d(TAG, "Tone $i: not detected (search $searchStart..$searchEnd)")
                }
            }

            analyzeResults(delays, snrDb)

        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed: ${e.message}", e)
            failedResult()
        } finally {
            recordingDone.set(true)
            try { audioTrack?.release() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
        }
    }

    /** Generate a 1kHz tone with sharp 2ms ramp (not Hann). Clean beep sound. */
    private fun generateTone(): ShortArray {
        val numSamples = (TONE_DURATION_MS * SAMPLE_RATE) / 1000
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            // Sharp ramp: 2ms attack, 2ms release, full amplitude in between
            val ramp = when {
                i < RAMP_SAMPLES -> i.toFloat() / RAMP_SAMPLES
                i > numSamples - RAMP_SAMPLES -> (numSamples - i).toFloat() / RAMP_SAMPLES
                else -> 1f
            }
            val value = TONE_AMPLITUDE * ramp * sin(2.0 * PI * TONE_FREQ_HZ * t)
            samples[i] = (value * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /** Create AudioRecord with best low-latency source available. */
    private fun createLowLatencyAudioRecord(): AudioRecord {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            if (Build.VERSION.SDK_INT >= 24) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = minBuf * 2

        for (source in sources) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufBytes)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord: source=$source bufBytes=$bufBytes")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$source failed: ${e.message}")
            }
        }
        error("No AudioRecord source initialized")
    }

    /** 2nd order IIR bandpass at 1kHz. */
    private fun bandpassFilter(input: ShortArray, length: Int): FloatArray {
        val n = length.coerceAtMost(input.size)
        val output = FloatArray(n)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in 0 until n) {
            val x0 = input[i].toDouble() / Short.MAX_VALUE
            val y0 = BP_B0 * x0 + BP_B2 * x2 - BP_A1 * y1 - BP_A2 * y2
            output[i] = y0.toFloat()
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
        }
        return output
    }

    /** Envelope follower. */
    private fun computeEnvelope(signal: FloatArray): FloatArray {
        val env = FloatArray(signal.size)
        var cur = 0f
        for (i in signal.indices) {
            val a = abs(signal[i])
            cur = if (a > cur) (ENV_ATTACK * a + (1 - ENV_ATTACK) * cur).toFloat()
                  else ((1 - ENV_RELEASE) * cur).toFloat()
            env[i] = cur
        }
        return env
    }

    private fun analyzeResults(delays: List<Long>, snrDb: Float): CalibrationResult {
        if (delays.size < MIN_TONES_DETECTED) {
            Log.w(TAG, "Too few tones: ${delays.size}/$TONE_COUNT")
            return CalibrationResult(0, delays.size, 0.0, snrDb, Quality.FAILED)
        }
        val sorted = delays.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) / 1000.0 }
        val mad = deviations.sorted()[deviations.size / 2]
        val varianceMs = mad * 1.4826

        val quality = when {
            delays.size < MIN_TONES_DETECTED -> Quality.FAILED
            varianceMs > MAX_VARIANCE_MS -> Quality.MARGINAL
            snrDb < MIN_SNR_DB -> Quality.MARGINAL
            else -> Quality.GOOD
        }
        Log.d(TAG, "Result: roundTrip=${median / 1000}ms detected=${delays.size}/$TONE_COUNT " +
            "variance=${"%.1f".format(varianceMs)}ms SNR=${"%.1f".format(snrDb)}dB quality=$quality")
        return CalibrationResult(median, delays.size, varianceMs, snrDb, quality)
    }

    private fun failedResult() = CalibrationResult(0, 0, 0.0, 0f, Quality.FAILED)
}
