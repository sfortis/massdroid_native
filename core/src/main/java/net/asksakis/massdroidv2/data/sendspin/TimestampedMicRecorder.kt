package net.asksakis.massdroidv2.data.sendspin

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * A microphone recording placed on the local CLOCK_MONOTONIC axis.
 *
 * [originUs] is the capture time of the first sample, from AudioRecord's
 * input timestamp; [timestampSpreadUs] is the largest distance of a single
 * timestamp reading from the median, a bound on how well the recording is
 * placed in time.
 */
class TimestampedRecording(
    val samples: FloatArray,
    val originUs: Long,
    val source: String,
    val timestampSpreadUs: Long,
) {
    val durationUs: Long get() = (samples.size - 1) * 1_000_000L / TimestampedMicRecorder.RATE
}

/**
 * Records the microphone and places the recording in time with
 * AudioRecord.getTimestamp. Used by the sync probe and by the output
 * calibration, which both compare the recording with a signal whose play time
 * is known.
 */
object TimestampedMicRecorder {
    const val RATE = 48_000
    private const val READ_FRAMES = 1024

    /**
     * Records for [durationMs]; null when the microphone did not open (no
     * permission) or delivered less than a second.
     */
    // The callers request RECORD_AUDIO before they record, and a refused
    // permission leaves the recorder uninitialised, which returns null here.
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Long): TimestampedRecording? = withContext(Dispatchers.IO) {
        val (record, source) = openRecorder() ?: return@withContext null
        val pcm = ShortArray((RATE * durationMs / 1000L).toInt())
        val origins = ArrayList<Long>()
        val timestamp = AudioTimestamp()
        try {
            record.startRecording()
            var filled = 0
            while (filled < pcm.size) {
                coroutineContext.ensureActive()
                val read = record.read(pcm, filled, minOf(READ_FRAMES, pcm.size - filled))
                if (read <= 0) break
                filled += read
                if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    // Local time of frame 0, as this timestamp places it.
                    origins += timestamp.nanoTime / 1000L - timestamp.framePosition * 1_000_000L / RATE
                }
            }
            if (filled < RATE || origins.isEmpty()) return@withContext null
            origins.sort()
            val originUs = origins[origins.size / 2]
            val spreadUs = max(originUs - origins.first(), origins.last() - originUs)
            TimestampedRecording(FloatArray(filled) { pcm[it] / 32768f }, originUs, source, spreadUs)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * UNPROCESSED first: the voice sources may run echo cancellation, which
     * removes the phone's own playback from the recording.
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, String>? {
        val minBuffer = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "unprocessed",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice_recognition",
            MediaRecorder.AudioSource.MIC to "mic",
        )
        for ((source, name) in sources) {
            val record = runCatching {
                AudioRecord(
                    source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, RATE / 5 * 2)
                )
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to name
            record.release()
        }
        return null
    }
}
