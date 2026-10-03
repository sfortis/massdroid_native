package net.asksakis.massdroidv2.data.sendspin

/**
 * Keeps a mono copy of the decoded stream, placed on the server clock by the
 * chunk timestamps, while the sync probe runs.
 *
 * The playback thread calls [append] for every chunk it hands to the native
 * output; the probe reads a window of it with [snapshot] after each recording.
 * The copy is a ring of [seconds] of audio, so a session with several
 * measurements always holds the most recent stretch. A chunk is placed by its
 * own timestamp rather than appended after the previous one, so a dropped or
 * late chunk leaves silence in its slot and cannot shift the rest.
 */
class SyncProbeTap(private val sampleRate: Int, seconds: Int) {
    private val capacity = sampleRate * seconds
    private val ring = FloatArray(capacity)
    private val lock = Any()
    private var baseServerUs = Long.MIN_VALUE
    // Absolute frame index (from baseServerUs) of the newest frame written.
    private var newest = -1L

    /** [pcm] holds interleaved 16-bit little-endian frames. */
    fun append(serverTimestampUs: Long, pcm: ByteArray, offset: Int, length: Int, channels: Int) {
        if (channels <= 0) return
        val frameBytes = channels * 2
        val frames = length / frameBytes
        synchronized(lock) {
            if (baseServerUs == Long.MIN_VALUE) baseServerUs = serverTimestampUs
            val first = (serverTimestampUs - baseServerUs) * sampleRate / 1_000_000L
            if (first < 0) return
            // A gap since the newest frame is silence, not what the ring held a
            // full cycle ago.
            var gap = newest + 1
            while (gap < first && gap <= newest + capacity) {
                ring[(gap % capacity).toInt()] = 0f
                gap++
            }
            for (f in 0 until frames) {
                val index = first + f
                if (index <= newest - capacity) continue
                var sum = 0
                val base = offset + f * frameBytes
                for (c in 0 until channels) {
                    val lo = pcm[base + c * 2].toInt() and 0xFF
                    val hi = pcm[base + c * 2 + 1].toInt()
                    sum += (hi shl 8) or lo
                }
                ring[(index % capacity).toInt()] = sum.toFloat() / (channels * 32768f)
                if (index > newest) newest = index
            }
        }
    }

    /**
     * The stream between [fromServerUs] and [toServerUs], clipped to what the
     * ring still holds, or null if none of it is there.
     */
    fun snapshot(fromServerUs: Long, toServerUs: Long): SyncProbeSignal? = synchronized(lock) {
        if (newest < 0) return null
        val oldest = maxOf(0L, newest - capacity + 1)
        val from = maxOf(oldest, (fromServerUs - baseServerUs) * sampleRate / 1_000_000L)
        val to = minOf(newest, (toServerUs - baseServerUs) * sampleRate / 1_000_000L)
        if (to <= from) return null
        val samples = FloatArray((to - from + 1).toInt()) { ring[((from + it) % capacity).toInt()] }
        SyncProbeSignal(samples, baseServerUs + from * 1_000_000L / sampleRate, 1_000_000.0 / sampleRate)
    }
}

/**
 * Where the timing model expects this phone's speaker to play, relative to
 * the server timestamp, and the parts that make up that expectation.
 */
data class SyncProbeModel(
    val expectedLagUs: Long,
    val headroomUs: Long,
    val syncDelayUs: Long,
    val acousticCorrectionUs: Long,
    // Latency the native output aligns against (Oboe calculateLatencyMillis).
    val reportedOutputLatencyUs: Long,
    // Full latency from AudioManager.getOutputLatency; 0 when unavailable.
    val fullOutputLatencyUs: Long,
    // Native smoothed drift; positive means the output currently plays early.
    val nativeDriftUs: Long,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
)
