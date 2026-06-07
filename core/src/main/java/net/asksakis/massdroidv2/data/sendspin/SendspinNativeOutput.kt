package net.asksakis.massdroidv2.data.sendspin

import android.util.Log

/**
 * Kotlin facade over the native (Oboe) Sendspin audio output.
 *
 * The output stage runs on a real-time HAL thread inside [SendspinOutputEngine]
 * (C++), so it is immune to JVM/ART GC pauses that previously stalled the
 * AudioTrack-on-JVM-thread writer and punched micro-drops in grouped sync. A
 * deep, server-time-anchored ring sits between the (GC-pausable) Kotlin
 * MediaCodec decode and the native callback; per-chunk timeline re-anchoring
 * happens inside the callback (skip/insert), so we keep group sync without a
 * shallow buffer. See [SendspinOutputEngine] header for the full rationale.
 *
 * Threading: [write] is called only from the decode/playback thread (single
 * producer). [start]/[stop]/[flush]/[release] come from the control path.
 *
 * PCM contract: [write] takes interleaved little-endian int16 frames. `offset`
 * must be 2-byte aligned (the native side reinterprets the buffer as int16);
 * callers feeding a raw frame with an odd header offset must copy into an
 * aligned buffer first.
 */
class SendspinNativeOutput {

    companion object {
        private const val TAG = "SendspinNative"

        @Volatile
        private var libraryLoaded = false

        private fun ensureLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("sendspin_output")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load sendspin_output native library: ${e.message}")
                false
            }
        }
    }

    @Volatile private var ptr: Long = 0L

    val isStarted: Boolean get() = ptr != 0L

    /**
     * Opens and starts the native Oboe output stream. [driftCorrection] enables
     * timeline alignment (grouped/SYNC); pass false for solo/DIRECT (pure FIFO).
     * Returns false on failure.
     */
    @Synchronized
    fun start(sampleRate: Int, channels: Int, driftCorrection: Boolean): Boolean {
        if (!ensureLibrary()) return false
        if (ptr != 0L) stop()
        val created = nativeCreate()
        if (created == 0L) {
            Log.e(TAG, "nativeCreate failed")
            return false
        }
        if (!nativeStart(created, sampleRate, channels, driftCorrection)) {
            nativeDestroy(created)
            Log.e(TAG, "nativeStart failed (${sampleRate}Hz ch=$channels)")
            return false
        }
        ptr = created
        return true
    }

    /** Stops and closes the stream, freeing native resources. */
    @Synchronized
    fun stop() {
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        nativeStop(p)
        nativeDestroy(p)
    }

    /** Drops all buffered audio + timeline markers (seek / track change). */
    fun flush() {
        val p = ptr
        if (p != 0L) nativeFlush(p)
    }

    /**
     * Feeds decoded interleaved int16 PCM. [presentationLocalUs] is the intended
     * CLOCK_MONOTONIC (System.nanoTime()/1000 domain) time of the FIRST frame.
     * Returns frames accepted.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int, presentationLocalUs: Long): Int {
        val p = ptr
        if (p == 0L || length <= 0) return 0
        return nativeWrite(p, pcm, offset, length, presentationLocalUs)
    }

    /** DAC presentation lag in microseconds (0 until the first valid timestamp). */
    fun outputLatencyUs(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeOutputLatencyUs(p)
    }

    /** Frames buffered (decoded, not yet played) in the native ring. */
    fun bufferedFrames(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeBufferedFrames(p)
    }

    /**
     * AAudio device id the stream is routed to (0 if not open). Match against
     * AudioManager.getDevices() to recover device type / product name.
     */
    fun deviceId(): Int {
        val p = ptr
        return if (p == 0L) 0 else nativeDeviceId(p)
    }

    /** Smoothed timeline drift in microseconds (intended minus DAC presentation). */
    fun driftEmaUs(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeDriftEmaUs(p)
    }

    /** Cumulative ring-underrun frames (audible dropouts); 0 = clean. */
    fun underrunFrames(): Long {
        val p = ptr
        return if (p == 0L) 0L else nativeUnderrunFrames(p)
    }

    /** Last applied resampler rate (1.0 = locked passthrough; off-1.0 = correcting). */
    fun resampleRate(): Double {
        val p = ptr
        return if (p == 0L) 1.0 else nativeResampleRateMicros(p) / 1_000_000.0
    }

    fun setVolume(volume: Float) {
        val p = ptr
        if (p != 0L) nativeSetVolume(p, volume.coerceIn(0f, 1f))
    }

    /**
     * Freeze (true) or resume (false) the consumer without dropping the ring.
     * Frozen = fade to silence then hold the read position, preserving the
     * buffered audio across a transient interruption. Unfreeze fades back in
     * from the held sample. Used for solo/DIRECT focus interruptions instead of
     * a flush (the server feeds realtime after a flush and never rebuilds the
     * deep buffer).
     */
    fun setFrozen(frozen: Boolean) {
        val p = ptr
        if (p != 0L) nativeSetFrozen(p, frozen)
    }

    /**
     * Idle power management: stop/restart the Oboe callback without closing the
     * stream or freeing the ring. pauseStream halts the real-time HAL thread
     * (no CPU, no audio hardware held) when playback is idle; resumeStream
     * restarts it quickly for the next stream.
     */
    fun pauseStream() {
        val p = ptr
        if (p != 0L) nativePauseStream(p)
    }

    fun resumeStream() {
        val p = ptr
        if (p != 0L) nativeResumeStream(p)
    }

    /** True if Oboe disconnected the stream (route preempted, e.g. phone call). */
    fun isDisconnected(): Boolean {
        val p = ptr
        return p != 0L && nativeIsDisconnected(p)
    }

    @Synchronized
    fun release() {
        stop()
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeStart(ptr: Long, sampleRate: Int, channels: Int, driftCorrection: Boolean): Boolean
    private external fun nativeStop(ptr: Long)
    private external fun nativeFlush(ptr: Long)
    private external fun nativeWrite(ptr: Long, pcm: ByteArray, offset: Int, length: Int, presentationLocalUs: Long): Int
    private external fun nativeOutputLatencyUs(ptr: Long): Long
    private external fun nativeBufferedFrames(ptr: Long): Long
    private external fun nativeSetVolume(ptr: Long, volume: Float)
    private external fun nativeSetFrozen(ptr: Long, frozen: Boolean)
    private external fun nativePauseStream(ptr: Long)
    private external fun nativeResumeStream(ptr: Long)
    private external fun nativeIsDisconnected(ptr: Long): Boolean
    private external fun nativeDeviceId(ptr: Long): Int
    private external fun nativeDriftEmaUs(ptr: Long): Long
    private external fun nativeUnderrunFrames(ptr: Long): Long
    private external fun nativeResampleRateMicros(ptr: Long): Long
}
