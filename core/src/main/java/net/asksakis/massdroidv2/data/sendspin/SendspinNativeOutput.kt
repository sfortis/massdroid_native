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
 * producer). [start]/[stop]/[flush]/[release] come from the control path. Every
 * call that passes the native pointer holds this object's monitor, the same one
 * [start] and [stop] hold while they create and delete the engine, so no call can
 * reach an engine that is being deleted. None of the native calls blocks (write
 * copies into the ring and returns) and the native side never calls back into the
 * JVM, so the monitor is held only briefly and cannot deadlock with the callback.
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

    // True when the last start() could not open or start the stream. A stream
    // that never opened raises no error callback, so this is the only record
    // that the output is still wanted; [isDisconnected] reports it so the
    // engine retries. Cleared by a successful start and by an explicit stop.
    @Volatile private var startFailed = false

    val isStarted: Boolean get() = ptr != 0L

    // The only way to pass [ptr] to native code outside start/stop; see the class doc.
    private inline fun <T> withEngine(ifStopped: T, block: (Long) -> T): T = synchronized(this) {
        val p = ptr
        if (p == 0L) ifStopped else block(p)
    }

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
            startFailed = true
            Log.e(TAG, "nativeStart failed (${sampleRate}Hz ch=$channels)")
            return false
        }
        ptr = created
        startFailed = false
        // The native engine is recreated on every (re)open, so re-apply the
        // cached output gain + compressor level + dither (native defaults are
        // volume 1.0, compressor 0 = off, dither off, so a default needs no call).
        // Without the volume re-apply, a reopen mid-duck would jump back to full.
        if (volume != 1f) nativeSetVolume(created, volume)
        if (compressorLevel != 0) nativeSetCompressorLevel(created, compressorLevel)
        if (ditherEnabled) nativeSetDither(created, true)
        return true
    }

    /** Stops and closes the stream, freeing native resources. */
    @Synchronized
    fun stop() {
        startFailed = false
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        nativeStop(p)
        nativeDestroy(p)
    }

    /** Drops all buffered audio + timeline markers (seek / track change). */
    fun flush() {
        withEngine(Unit) { p -> nativeFlush(p) }
    }

    /**
     * Feeds decoded interleaved int16 PCM. [presentationLocalUs] is the intended
     * CLOCK_MONOTONIC (System.nanoTime()/1000 domain) time of the FIRST frame.
     * Returns frames accepted.
     */
    fun write(pcm: ByteArray, offset: Int, length: Int, presentationLocalUs: Long): Int {
        if (length <= 0) return 0
        return withEngine(0) { p -> nativeWrite(p, pcm, offset, length, presentationLocalUs) }
    }

    /** DAC presentation lag in microseconds (0 until the first valid timestamp). */
    fun outputLatencyUs(): Long {
        return withEngine(0L) { p -> nativeOutputLatencyUs(p) }
    }

    /** Frames buffered (decoded, not yet played) in the native ring. */
    fun bufferedFrames(): Long {
        return withEngine(0L) { p -> nativeBufferedFrames(p) }
    }

    /**
     * AAudio device id the stream is routed to (0 if not open). Match against
     * AudioManager.getDevices() to recover device type / product name.
     */
    fun deviceId(): Int {
        return withEngine(0) { p -> nativeDeviceId(p) }
    }

    /** Smoothed timeline drift in microseconds (intended minus DAC presentation). */
    fun driftEmaUs(): Long {
        return withEngine(0L) { p -> nativeDriftEmaUs(p) }
    }

    /**
     * Whether the callback has measured the drift since the last start or
     * flush. Until it has, [driftEmaUs] returns its reset value 0, which is
     * not a measurement.
     */
    fun driftMeasured(): Boolean {
        return withEngine(false) { p -> nativeDriftMeasured(p) }
    }

    /** Cumulative ring-underrun frames (audible dropouts); 0 = clean. */
    fun underrunFrames(): Long {
        return withEngine(0L) { p -> nativeUnderrunFrames(p) }
    }

    /** Last applied resampler rate (1.0 = locked passthrough; off-1.0 = correcting). */
    fun resampleRate(): Double {
        return withEngine(1.0) { p -> nativeResampleRateMicros(p) / 1_000_000.0 }
    }

    // Cached so a non-default gain (e.g. a transient focus duck) survives the
    // native recreate on every (re)open (see start()); native default is 1.0.
    @Volatile private var volume = 1f

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        withEngine(Unit) { p -> nativeSetVolume(p, v) }
    }

    // Cached so it survives the native recreate on every (re)open (see start()).
    @Volatile private var compressorLevel = 0

    /**
     * Dynamic-range compressor level: 0 = off, 1 = soft, 2 = medium, 3 = hard.
     * Amplitude-only output effect (applied in the native callback with the
     * volume gain); no effect on timing/latency/sync.
     */
    fun setCompressorLevel(level: Int) {
        val bounded = level.coerceIn(0, 3)
        compressorLevel = bounded
        withEngine(Unit) { p -> nativeSetCompressorLevel(p, bounded) }
    }

    // Cached so it survives the native recreate on every (re)open (see start()).
    @Volatile private var ditherEnabled = false

    /**
     * High-end output quantization: noise-shaped TPDF dither at the float->int16
     * step. Amplitude-only (sample values), no effect on timing/latency/sync.
     */
    fun setDither(enabled: Boolean) {
        ditherEnabled = enabled
        withEngine(Unit) { p -> nativeSetDither(p, enabled) }
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
        withEngine(Unit) { p -> nativeSetFrozen(p, frozen) }
    }

    /**
     * Idle power management: stop/restart the Oboe callback without closing the
     * stream or freeing the ring. pauseStream halts the real-time HAL thread
     * (no CPU, no audio hardware held) when playback is idle; resumeStream
     * restarts it quickly for the next stream.
     */
    fun pauseStream() {
        withEngine(Unit) { p -> nativePauseStream(p) }
    }

    fun resumeStream() {
        withEngine(Unit) { p -> nativeResumeStream(p) }
    }

    /**
     * True when the output needs a reopen: Oboe disconnected the stream (route
     * preempted, e.g. a phone call, or taken while stopped), or the last start
     * failed to open it at all.
     */
    fun isDisconnected(): Boolean {
        if (startFailed) return true
        return withEngine(false) { p -> nativeIsDisconnected(p) }
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
    private external fun nativeSetCompressorLevel(ptr: Long, level: Int)
    private external fun nativeSetDither(ptr: Long, enabled: Boolean)
    private external fun nativeSetFrozen(ptr: Long, frozen: Boolean)
    private external fun nativePauseStream(ptr: Long)
    private external fun nativeResumeStream(ptr: Long)
    private external fun nativeIsDisconnected(ptr: Long): Boolean
    private external fun nativeDeviceId(ptr: Long): Int
    private external fun nativeDriftEmaUs(ptr: Long): Long
    private external fun nativeDriftMeasured(ptr: Long): Boolean
    private external fun nativeUnderrunFrames(ptr: Long): Long
    private external fun nativeResampleRateMicros(ptr: Long): Long
}
