package net.asksakis.massdroidv2.data.sendspin

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

enum class SyncState {
    IDLE,
    SYNCHRONIZED,
    HOLDOVER_PLAYING_FROM_BUFFER,
    SYNC_ERROR_REBUFFERING
}

/**
 * Shared Sendspin playback machinery: MediaCodec decode, the encoded frame
 * queue, the timeline playback thread, lifecycle/boundary handling, generation
 * guarding, and feeding the GC-immune native output.
 *
 * Output runs in [SendspinNativeOutput] (Oboe callback on a real-time HAL
 * thread), not on this JVM thread, so an ART GC pause can no longer stall the
 * writer and underrun the DAC. A deep server-time-anchored PCM ring sits
 * between decode (here, GC-pausable) and the native callback (GC-immune); the
 * callback does the per-chunk timeline re-anchoring (skip/insert), so group
 * sync is preserved without a shallow buffer. The producer paces itself
 * against the ring depth ([RING_TARGET_MS]) and leaves the rest encoded.
 *
 * Every decoded PCM chunk carries its intended absolute presentation time
 * (CLOCK_MONOTONIC us). WHAT that time is differs by role and is the only thing
 * subclasses provide:
 *
 *  - [SendspinSyncEngine]   grouped: absolute group timeline + scheduling
 *                            headroom, clock-readiness gate, start-trim.
 *  - [SendspinDirectEngine] solo: a local anchor so playback starts ~now with
 *                            no clock dependency and no group machinery.
 *
 * Output (HAL) latency is handled inside the native callback (it compares the
 * intended time against the real DAC presentation time), so this layer no
 * longer subtracts output latency from the schedule. It still subtracts the
 * acoustic/BT external delay (which getTimestamp does not see) and the UX sync
 * nudge.
 *
 * The manager runs exactly one engine at a time and swaps instances at a group
 * join/leave boundary (already a hard relock), so each role stays isolated.
 */
abstract class SendspinPlaybackEngine(context: Context) : SendspinAudioEngine {
    companion object {
        private const val TAG = "AudioStream"
        private const val HEADER_SIZE = 9
        private const val TYPE_PLAYER_AUDIO = 4
        // Hard memory ceiling for the encoded frame queue. The server streams
        // ahead up to the requested buffer_capacity (~4 MB ~= 30 s of FLAC), so
        // this must stay well above it; it is only a runaway backstop.
        private const val MAX_ENCODED_BUFFER_BYTES = 10_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024
        // Producer backpressure: keep roughly this much decoded PCM in the
        // native ring and leave everything else encoded in frameQueue. Must
        // stay below the native RING_SECONDS so write() never has to drop.
        private const val RING_TARGET_MS = 2_500L
        // After playback has been IDLE this long, stop the real-time Oboe output
        // callback (requestStop, stream stays open) and let the producer thread
        // exit, so a connected-but-not-playing app draws no audio-HAL / CPU power.
        // Longer than a normal track transition (stream/end -> stream/start, ~1 s)
        // so it never churns mid-playlist; the next stream restarts instantly.
        private const val IDLE_OUTPUT_STOP_GRACE_MS = 5_000L
        // Output is muted across every hard boundary and (re)start so the
        // transient at a skip/seek/relock (ring refill, decoder priming, clock
        // convergence) is never audible. The Oboe stream runs continuously, so
        // the flag MUST be applied to the native volume, unlike the old
        // AudioTrack path where flush() silenced the hardware buffer for free.
        // SYNC holds longer to let the clock/drift converge; DIRECT only needs
        // to cover the boundary transient.
        private const val SYNC_STARTUP_MUTE_MS = 350L
        private const val DIRECT_STARTUP_MUTE_MS = 200L
        private const val SYNC_SAMPLE_CALLBACK_MS = 1_000L
        // Bounded backstop for the fresh-stream gate. expectDiscontinuity arms
        // the gate expecting MA to open a fresh stream (configure()/clearBuffer()
        // clears it). If MA instead keeps feeding the SAME stream and that signal
        // never comes, the gate would drop every frame forever (silent freeze).
        // Once gated frames keep arriving past this window the server is clearly
        // continuing the stream, so accept it. Comfortably above a real track
        // transition (~1 s) and a measured seek restart (~2 s) so it never
        // pre-empts a legitimate fresh stream.
        private const val GATE_FRESH_STREAM_TIMEOUT_MS = 4_000L
        // A native-output reopen (route preempt / bound device gone) is DEBOUNCED:
        // a BT/car connect makes the bound output flap A2DP<->SCO<->earpiece<->speaker
        // for a second or two. Reopening on each transient binds the stream to a
        // momentary device (SCO/earpiece -> no car audio, "playing" but silent). We
        // wait for the route to go quiet for [REOPEN_SETTLE_MS] (i.e. A2DP fully
        // established) and only then reopen once, onto the settled route. Bounded by
        // [REOPEN_MAX_WAIT_MS] so continuous flapping or a genuine speaker-only route
        // still reopens. The producer parks on reopenInFlight meanwhile, so the ring
        // is held (bounded), not grown.
        //
        // REOPEN_MAX_WAIT_MS only governs the GIVE-UP-to-speaker fallthrough: the
        // moment a real music sink (A2DP/BLE/wired/USB) appears the wait
        // short-circuits and binds immediately, so a longer cap adds NO latency on
        // a normal connect. It only delays falling back to the phone speaker when no
        // external sink ever shows up. Voice-first BT (motorcycle intercoms / some
        // helmets) can take several seconds to bring A2DP up after the SCO link, so
        // 8 s gives slow stacks time to establish A2DP before we give up on it.
        private const val REOPEN_SETTLE_MS = 350L
        private const val REOPEN_MAX_WAIT_MS = 8_000L
    }

    // Holds the raw WS frame (header included) with an offset/length view of
    // the payload, so we don't copy the payload out per frame (GC pressure on
    // the audio hot path). The client hands a fresh ByteArray per frame.
    protected data class EncodedFrame(
        val serverTimestampUs: Long,
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val generation: Long,
    ) : Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    private data class DecoderMark(
        val serverTimestampUs: Long,
        val generation: Long,
    )

    protected data class TimingPlan(
        val localOutputUs: Long,
        val staticDelayUs: Long,
        val outputLatencyUs: Long,
        val headroomUs: Long,
        // Intended absolute presentation time (CLOCK_MONOTONIC us) of the first
        // sample. Fed to the native output, which aligns it against real DAC
        // presentation time.
        val presentationUs: Long,
    )

    /** localOutput + headroom for a chunk; the role-specific scheduling decision. */
    protected data class LocalPlan(val localOutputUs: Long, val headroomUs: Long)

    // ---- Role hooks --------------------------------------------------------

    /** Minimum buffered audio before playback may start. */
    protected abstract val startBufferMs: Long

    /** Whether this engine runs the grouped sync machinery (start-trim, sync-error, mute, clock gate). */
    protected val isSync: Boolean get() = correctionMode == CorrectionMode.SYNC

    /** Role-specific local output time + headroom for [serverTimestampUs]. */
    protected abstract fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan

    /** Extra start gate after the buffer threshold is met. Default: ready now. */
    protected open fun startupGate(neededMs: Long): Boolean = true

    /** Called from [flushQueuesAndDecoder] so a role can reset its own timeline state. */
    protected open fun onFlush() {}

    // ---- Shared state ------------------------------------------------------

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nativeOutput = SendspinNativeOutput()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private val decoderMarks = ArrayDeque<DecoderMark>()
    private val codecLock = Any()
    private val playbackThreadLock = Any()
    // Reused decoded-PCM scratch buffer (playback thread only) so we don't
    // allocate a fresh ByteArray per decoded chunk. Grown on demand.
    private var pcmScratch = ByteArray(16384)
    // Reused buffer to realign an odd-offset raw PCM frame (the native side
    // reinterprets the buffer as int16, which requires a 2-byte aligned start).
    private var pcmAligned = ByteArray(16384)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var configured = false
    @Volatile private var playbackActive = false
    @Volatile protected var playbackStarted = false
    // True while the Oboe output callback is stopped (requestStop) for idle power
    // saving. The stream stays open + the ring is preserved, so a stream/start or
    // resume restarts it quickly.
    @Volatile private var outputPausedForIdle = false
    // Guards against posting multiple reopen requests while one is in flight.
    @Volatile private var reopenInFlight = false
    // Debounced reopen (see REOPEN_SETTLE_MS): the pending settle callback and the
    // wall-clock instant the current reopen cycle was first requested (for the max
    // wait). Touched only on the main thread; 0 = no cycle in flight.
    private var reopenSettleRunnable: Runnable? = null
    @Volatile private var reopenRequestedAtMs = 0L
    // True while the output is frozen for a transient focus loss (solo/DIRECT).
    // startNativeOutput recreates the native object (which defaults to unfrozen),
    // so a reopen mid-freeze (phone call) must re-apply it or the preserved
    // buffer would play out into the still-preempted route and be lost.
    @Volatile private var outputFrozen = false
    // Set on any local discontinuity (next/previous AND seek): the server keeps
    // sending the OLD position's frames until it processes the command, and
    // those in-flight frames would otherwise be accepted and briefly play the
    // pre-discontinuity audio. Drop all frames until the server's fresh
    // stream/start (configure) or stream/clear (clearBuffer) arrives.
    // [GATE_FRESH_STREAM_TIMEOUT_MS] is the bounded backstop for the case where
    // that signal never comes (server continues the same stream).
    @Volatile private var awaitingFreshStream = false
    // Wall-clock instant the gate was armed; drives the timeout backstop. 0 when
    // the gate is clear.
    @Volatile private var awaitingFreshStreamSinceMs = 0L
    @Volatile private var paused = false
    @Volatile private var configureGeneration = 0L
    @Volatile private var playbackGeneration = 0L
    @Volatile protected var activeCodec = "flac"
    @Volatile protected var activeBitDepth = 16
    @Volatile protected var activeSampleRate = 48_000
    @Volatile protected var activeChannels = 2
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile protected var startupWaitStartedMs = 0L
    @Volatile private var routedDeviceType = -1
    @Volatile private var routedProductName: String? = null
    // Full output-path latency from AudioManager.getOutputLatency (DAC/analog
    // included), which Oboe getTimestamp under-reports. The gap (this minus the
    // getTimestamp latency) is the extra compensation that lets us play on the
    // group timeline without a manual sync nudge. 0 = unavailable.
    @Volatile private var halOutputLatencyUs = 0L
    @Volatile private var syncMuteStartedMs = 0L
    @Volatile private var receivedFrameCount = 0L
    @Volatile private var decodedChunkCount = 0L
    @Volatile private var writtenChunkCount = 0L
    @Volatile private var lastTimingLogMs = 0L
    @Volatile private var lastStartupLogMs = 0L
    @Volatile private var lastSyncSampleCallbackMs = 0L
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var deviceCallbackRegistered = false

    override var onSyncStateChanged: ((SyncState) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float, dacAbsoluteMs: Float?) -> Unit)? = null
    override var clockSynchronizer: ClockSynchronizer? = null
    override var syncDelayMs: Int = 0
    override var routeAcousticExtraUs: Long = 0L
    override val measuredOutputLatencyUs: Long get() = nativeOutput.outputLatencyUs()
    final override var syncState: SyncState = SyncState.IDLE
        private set

    @Volatile private var currentVolume = 1f
    @Volatile private var muted = false

    // Compatibility fields consumed by SendspinManager/UI.
    @Volatile var syncMuted = false
        private set
    @Volatile var smoothedSyncErrorMs = 0.0
        private set
    @Volatile var startupOffsetMs = 0.0
        private set
    @Volatile var resyncCount = 0
        private set
    var onRoutingChanged: (() -> Unit)? = null

    // Detects output route changes (Oboe has no routing listener). If our bound
    // device disappears (disconnect / BT switch) we reopen the native stream
    // onto the new default route (self-heal — Oboe Shared streams do not
    // auto-migrate), then re-resolve and notify. Notifying drives the existing
    // route-change chain (controller -> onOutputRouteChanged) for the relock.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post { handleDeviceChange() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post { handleDeviceChange() }
        }
    }

    // Mode is fixed per subclass; the manager swaps instances rather than
    // flipping a live mode, so this is a no-op kept for the interface.
    override fun setCorrectionMode(mode: CorrectionMode) {
        if (mode != correctionMode) {
            Log.w(TAG, "setCorrectionMode($mode) ignored on ${this::class.simpleName} (mode is fixed; manager swaps engines)")
        }
    }

    override fun setCellularTransport(cellular: Boolean) {
        // The timeline engine is codec/clock driven. Transport affects server
        // format policy, not local playout scheduling.
    }

    override fun configure(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        startType: ProtocolStartType,
    ) {
        val sameFormat = configured &&
            codecName == activeCodec &&
            sampleRate == activeSampleRate &&
            channels == activeChannels &&
            bitDepth == activeBitDepth

        configureGeneration++
        cancelIdleStop()
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        startupWaitStartedMs = 0L
        // A stream/start means the server is actively streaming again. Resume
        // can arrive as stream/start (not setPaused(false)), so we must clear
        // the paused flag here or onBinaryMessage keeps dropping every frame and
        // the playback loop stays asleep -> stuck IDLE after pause/play.
        paused = false
        resetSyncMetrics()

        if (sameFormat) {
            Log.d(TAG, "configure ${startType.name} reuse codec=$codecName ${sampleRate}Hz/${bitDepth}bit")
            // Restart the output/producer if they were stopped for idle power save.
            ensureOutputRunning()
            flushQueuesAndDecoder()
            playbackStarted = false
            // Keep the output muted from here until the new stream's audio
            // actually starts flowing (lifted in writeChunk after the cushion).
            beginStartupMute()
            transitionSyncState(if (startType == ProtocolStartType.NEW_STREAM) SyncState.IDLE else SyncState.SYNC_ERROR_REBUFFERING)
            ensurePlaybackThread()
            return
        }

        Log.d(TAG, "configure ${startType.name} rebuild codec=$codecName ${sampleRate}Hz/${bitDepth}bit ch=$channels")
        releaseInternal()
        activeCodec = codecName
        activeSampleRate = sampleRate
        activeChannels = channels
        activeBitDepth = bitDepth

        startNativeOutput()
        registerDeviceCallback()
        refreshRoutedDevice()

        codec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)
        configured = true
        playbackActive = true
        playbackStarted = false
        paused = false
        // Armed silent until the new stream's audio starts (lifted in writeChunk).
        beginStartupMute()
        transitionSyncState(SyncState.IDLE)
        ensurePlaybackThread()
    }

    override fun currentConfigureGeneration(): Long = configureGeneration

    /**
     * Bounded backstop for the fresh-stream gate. The gate clears normally on
     * configure() (stream/start) or clearBuffer() (stream/clear). Here we time
     * from the FIRST gated frame: if frames keep arriving and getting dropped
     * for [GATE_FRESH_STREAM_TIMEOUT_MS] with neither signal, the server is
     * continuing the same stream (e.g. a no-op queue next on a single-item
     * audiobook), so stop dropping and accept it. Called on the WS binary thread
     * only while [awaitingFreshStream] is set.
     */
    private fun freshStreamGateExpired(): Boolean {
        val now = System.currentTimeMillis()
        val since = awaitingFreshStreamSinceMs
        if (since == 0L) {
            awaitingFreshStreamSinceMs = now
            return false
        }
        if (now - since < GATE_FRESH_STREAM_TIMEOUT_MS) return false
        Log.w(TAG, "Fresh-stream gate timeout (${now - since}ms, no configure/clear) — accepting continued stream")
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        return true
    }

    override fun onBinaryMessage(data: ByteArray, generation: Long) {
        if (!configured || paused || generation != configureGeneration) return
        if (awaitingFreshStream && !freshStreamGateExpired()) return
        // Fresh frames on a still-active stream after a power-save idle stop must
        // wake the output + producer; otherwise they pile up undrained (buffer
        // climbs to the ceiling) and playback never resumes. ensureOutputRunning
        // is a no-op once already running.
        if (outputPausedForIdle) mainHandler.post { ensureOutputRunning() }
        if (data.size <= HEADER_SIZE || data[0].toInt() != TYPE_PLAYER_AUDIO) return
        // Overflow backstop: if we are genuinely over the memory ceiling, drop
        // THIS incoming (newest) frame. Never poll() the head — that is about
        // to be decoded/played. With MAX_ENCODED_BUFFER_BYTES well above the
        // server buffer_capacity this never triggers in normal playback.
        if (frameQueueBytes.get() > MAX_ENCODED_BUFFER_BYTES) {
            Log.w(TAG, "Encoded buffer ceiling hit (${frameQueueBytes.get()}B) — dropping incoming frame")
            return
        }

        val serverTimestampUs = parseTimestampUs(data)
        val payloadLength = data.size - HEADER_SIZE
        receivedFrameCount++
        val previousTail = lastEnqueuedTimestampUs
        if (previousTail > 0L) {
            val spacing = serverTimestampUs - previousTail
            if (spacing in 5_000L..500_000L) estimatedFrameDurationUs = spacing
        }
        frameQueue.offer(EncodedFrame(serverTimestampUs, data, HEADER_SIZE, payloadLength, generation))
        frameQueueBytes.addAndGet(payloadLength.toLong())
        lastEnqueuedTimestampUs = serverTimestampUs
        val localUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs)
        if (receivedFrameCount <= 8 || receivedFrameCount % 500 == 0L) {
            Log.d(
                TAG,
                "Timing/in frame#$receivedFrameCount codec=$activeCodec serverTs=${serverTimestampUs / 1000}ms " +
                    "localOut=${localUs?.let { it / 1000 } ?: -1}ms lead=${localUs?.let { (it - nowUs()) / 1000 } ?: -1}ms " +
                    "spacing=${if (previousTail > 0L) (serverTimestampUs - previousTail) / 1000 else -1}ms " +
                    "buf=${bufferDurationMs()}ms bytes=${frameQueueBytes.get()} gen=$generation ${clockDebug()}"
            )
        }
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            Log.d(TAG, "Pause boundary: flushing native output/decoder buf=${bufferDurationMs()}ms state=$syncState")
            flushQueuesAndDecoder()
            playbackStarted = false
            syncMuted = false
            syncMuteStartedMs = 0L
            startupWaitStartedMs = 0L
            resetSyncMetrics()
            transitionSyncState(SyncState.IDLE)
        } else {
            Log.d(TAG, "Resume boundary: waiting for fresh timeline buffer mode=$correctionMode ${clockDebug()}")
            ensureOutputRunning()
            playbackActive = true
            ensurePlaybackThread()
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        applyOutputVolume()
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        applyOutputVolume()
    }

    /**
     * Output dynamic-range compressor level (0 off, 1 soft, 2 medium, 3 hard).
     * Amplitude-only stage in the native callback; no effect on timing/sync. The
     * native output caches it and re-applies across stream reopens.
     */
    fun setCompressorLevel(level: Int) {
        nativeOutput.setCompressorLevel(level)
    }

    private val startupMuteMs: Long get() = if (isSync) SYNC_STARTUP_MUTE_MS else DIRECT_STARTUP_MUTE_MS

    private fun applyOutputVolume() {
        nativeOutput.setVolume(if (muted || syncMuted) 0f else currentVolume)
    }

    /**
     * Mute the (continuously running) native output across a hard boundary or
     * (re)start. Unlike the old AudioTrack path, the Oboe stream keeps emitting,
     * so the mute must be applied to the native volume now; [writeChunk] lifts
     * it once enough time has passed and (in SYNC) drift has converged.
     */
    private fun beginStartupMute() {
        syncMuted = true
        syncMuteStartedMs = System.currentTimeMillis()
        applyOutputVolume()
    }

    override fun onStreamEnd() {
        Log.d(TAG, "stream/end clear buf=${bufferDurationMs()}ms")
        configureGeneration++
        flushQueuesAndDecoder()
        playbackStarted = false
        // Stay muted between tracks. A track change is expectDiscontinuity(mute)
        // -> stream/end -> stream/start; if we unmuted here the fade would ramp
        // back to full over the empty-ring gap and the next track's first
        // samples would play uncushioned (audible blip before the real fade-in).
        beginStartupMute()
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        transitionSyncState(SyncState.IDLE)
    }

    override fun clearBuffer() {
        Log.d(TAG, "stream/clear hard boundary buf=${bufferDurationMs()}ms")
        configureGeneration++
        // A server-initiated stream/clear continues the same stream, so accept
        // frames again. expectDiscontinuity re-arms the gate after this for a
        // track change (next/previous), where a new stream/start is expected.
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        flushQueuesAndDecoder()
        playbackStarted = false
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        // Mute now (both modes): the Oboe stream is still running, so without
        // this the ring-refill / decoder-priming transient at a skip/seek leaks
        // out as a brief garbled burst. Lifted in writeChunk after the cushion.
        beginStartupMute()
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason")
        clearBuffer()
        // Every local discontinuity (next/previous AND seek) makes MA open a
        // fresh stream (stream/end + stream/start). Until that stream/start
        // (configure) lands, the server is still flushing the OLD position's
        // in-flight frames; gate them out so we don't briefly play pre-seek /
        // previous-track audio. configure() clears the gate.
        awaitingFreshStream = true
        Log.d(TAG, "Awaiting fresh stream after '$reason' — dropping in-flight frames")
    }

    override fun onTransportFailure() {
        val bufMs = bufferDurationMs()
        transitionSyncState(if (bufMs > startBufferMs) SyncState.HOLDOVER_PLAYING_FROM_BUFFER else SyncState.SYNC_ERROR_REBUFFERING)
    }

    override fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route changed: $reason acoustic=${routeAcousticExtraUs / 1000}ms")
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        // The native stream is reopened by handleDeviceChange() when the bound
        // device actually disappears; do not reopen here (avoids a second
        // glitch). This path resets the timeline state and relocks: mute, drop
        // the buffer, and re-converge against the new route's latency.
        flushQueuesAndDecoder()
        if (playbackStarted) {
            playbackStarted = false
            beginStartupMute()
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        }
    }

    override fun getRoutedDeviceType(): Int? = routedDeviceType.takeIf { it >= 0 }

    override fun getRoutedDeviceProductName(): String? = routedProductName

    override fun bufferDurationMs(): Long {
        val head = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tail = lastEnqueuedTimestampUs
        if (tail <= 0L || tail < head) return 0L
        return ((tail - head + estimatedFrameDurationUs) / 1000L).coerceAtLeast(0L)
    }

    override fun bufferedBytes(): Long = frameQueueBytes.get()

    /** Decoded PCM cushion currently in the native ring (ms); the real underrun margin. */
    fun ringBufferedMs(): Long =
        nativeOutput.bufferedFrames() * 1000L / activeSampleRate.coerceAtLeast(1)

    /** Cumulative native ring-underrun frames (audible dropouts since the last (re)start). */
    fun underrunFrames(): Long = nativeOutput.underrunFrames()

    /** Last applied resampler rate (1.0 locked; off-1.0 = actively correcting, SYNC only). */
    fun resampleRate(): Double = nativeOutput.resampleRate()

    override fun shiftAnchorForSyncDelayChange(deltaMs: Int) {
        resetSyncMetrics()
    }

    fun dacGroundTruthErrorMs(): Float? =
        if (isSync && syncState == SyncState.SYNCHRONIZED) smoothedSyncErrorMs.toFloat() else null

    override fun release() {
        releaseInternal()
    }

    // ---- Routing ------------------------------------------------------------

    // region Idle power management

    private val idleStopRunnable = Runnable { stopOutputForIdle() }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        mainHandler.postDelayed(idleStopRunnable, IDLE_OUTPUT_STOP_GRACE_MS)
    }

    private fun cancelIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
    }

    /**
     * Fired when playback has stayed IDLE past the grace window: stop the
     * real-time Oboe callback and let the producer thread exit, so a connected
     * but-not-playing app holds no audio HAL / burns no CPU. The stream stays
     * open and the ring is preserved; [ensureOutputRunning] restarts it.
     */
    private fun stopOutputForIdle() {
        if (syncState != SyncState.IDLE || playbackStarted || outputPausedForIdle) return
        Log.d(TAG, "Idle ${IDLE_OUTPUT_STOP_GRACE_MS}ms: stopping native output + producer (power save)")
        outputPausedForIdle = true
        playbackActive = false      // producer loop exits
        nativeOutput.pauseStream()  // real-time HAL callback stops firing
    }

    /** Restart the output + producer if they were stopped for idle. */
    private fun ensureOutputRunning() {
        cancelIdleStop()
        if (!outputPausedForIdle) return
        outputPausedForIdle = false
        nativeOutput.resumeStream()
        playbackActive = true
        ensurePlaybackThread()
        Log.d(TAG, "Output resumed from idle stop")
    }

    // endregion

    private fun startNativeOutput() {
        // NOTE: deliberately does NOT touch outputPausedForIdle. A route reopen
        // (commitReopen, e.g. BT disconnect) calls this while we are idle-stopped
        // purely to rebind the Oboe stream to the settled route — the producer
        // stays parked. Clearing the idle flag here would lie about that state:
        // ensureOutputRunning() would then no-op on resume and the producer would
        // never restart (encoded buffer floods, device wedges IDLE, no audio). The
        // flag is owned solely by stopOutputForIdle / ensureOutputRunning /
        // releaseInternal, which keep it consistent with the producer's liveness.
        // SYNC aligns to the group timeline; DIRECT (solo) is a pure FIFO.
        if (!nativeOutput.start(activeSampleRate, activeChannels, isSync)) {
            Log.e(TAG, "Native output failed to start ${activeSampleRate}Hz ch=$activeChannels; stream will be silent")
        }
        // A (re)start recreates the native engine, which defaults to unfrozen. If
        // we were frozen for a focus loss (e.g. reopen mid phone call), re-apply
        // it so the preserved buffer is held silent until the focus regain
        // unfreeze, instead of playing out into the still-preempted route.
        if (outputFrozen) nativeOutput.setFrozen(true)
        // Oboe getTimestamp/calculateLatency only report the HAL buffer (~tens
        // of ms), missing the DAC/analog/speaker path. The hidden
        // AudioManager.getOutputLatency reports the FULL output latency (what
        // the browser/OS uses), so we can COMPUTE the full compensation instead
        // of needing a manual sync nudge. Re-queried on every (re)configure /
        // route reopen so it tracks the active output.
        val halMs = queryHalOutputLatencyMs()
        halOutputLatencyUs = if (halMs > 0) halMs * 1000L else 0L
        Log.d(TAG, "HAL output latency=${halMs}ms (getTimestamp sees ~${measuredOutputLatencyUs / 1000}ms)")
    }

    private fun queryHalOutputLatencyMs(): Int = try {
        val m = AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
        m.invoke(audioManager, AudioManager.STREAM_MUSIC) as Int
    } catch (e: Exception) {
        -1
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) return
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        deviceCallbackRegistered = true
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        deviceCallbackRegistered = false
    }

    /**
     * System device add/remove. Reopen the native stream only if OUR bound
     * output device is gone (a spurious add/remove of an unrelated device must
     * not glitch playback) — but DEBOUNCED (see [scheduleReopen]): a BT/car
     * connect flaps the device set, and reopening on each transient binds the
     * stream to a momentary SCO/earpiece. While a reopen is already pending, every
     * further device change just pushes the settle window out (the route is still
     * moving). Then re-resolve + notify so the route-change chain runs the relock.
     */
    private fun handleDeviceChange() {
        if (!configured) return
        if (reopenInFlight) {
            // Route still changing while we wait to reopen: keep waiting for quiet.
            scheduleReopen("device-change while settling")
            return
        }
        val boundId = nativeOutput.deviceId()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val stillPresent = boundId > 0 && outputs.any { it.id == boundId }
        if (!stillPresent) {
            scheduleReopen("bound device $boundId gone")
            return
        }
        // Connect-side supersede: our bound device is still present, but a
        // higher-priority external sink (BT A2DP / wired / USB) just appeared
        // while we're bound to a non-external output (the built-in speaker).
        // Android routes music to the new sink, but the Oboe stream keeps playing
        // to the now-inactive built-in path: the timeline advances yet there's no
        // sound (issue #52). Reopen onto the new route. Skip when already bound to
        // an external sink so a spurious add doesn't churn an active BT stream.
        val boundType = outputs.firstOrNull { it.id == boundId }?.type
        val boundIsExternal = boundType != null && isExternalSink(boundType)
        val externalSinkPresent = outputs.any { isExternalSink(it.type) }
        if (!boundIsExternal && externalSinkPresent) {
            scheduleReopen("external sink appeared while bound to built-in output")
        } else {
            refreshRoutedDevice()
        }
    }

    /**
     * Reopen the native output after Oboe reported the stream disconnected with
     * the bound device still present (a phone call / nav prompt preempts the
     * route without removing the device, so handleDeviceChange does not fire).
     * Routed through the debounced [scheduleReopen]. The loop already set
     * reopenInFlight to park the producer.
     */
    private fun reopenAfterDisconnect() {
        if (!configured) {
            reopenInFlight = false
            return
        }
        scheduleReopen("oboe disconnect")
    }

    /**
     * Coalesce reopen requests and wait for the output route to stabilise before
     * binding (see [REOPEN_SETTLE_MS]). Re-armed on every trigger; commits once the
     * route has been quiet for the settle window, or after [REOPEN_MAX_WAIT_MS].
     * Runs on the main thread (device callbacks + the loop's mainHandler.post).
     */
    private fun scheduleReopen(reason: String) {
        if (!configured) {
            reopenInFlight = false
            return
        }
        val now = System.currentTimeMillis()
        if (!reopenInFlight || reopenRequestedAtMs == 0L) {
            reopenInFlight = true
            reopenRequestedAtMs = now
            Log.d(TAG, "Reopen requested ($reason): waiting for route to settle")
        }
        reopenSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        reopenSettleRunnable = null
        if (now - reopenRequestedAtMs >= REOPEN_MAX_WAIT_MS) {
            Log.d(TAG, "Reopen: max settle wait elapsed, reopening on current route")
            commitReopen(reason)
            return
        }
        val r = Runnable { commitReopen(reason) }
        reopenSettleRunnable = r
        mainHandler.postDelayed(r, REOPEN_SETTLE_MS)
    }

    /**
     * Perform the deferred reopen once the route settled. If our bound device is
     * gone and no external sink (A2DP/BLE/wired/USB — deliberately NOT SCO, which
     * is the voice channel, not a music route) is present yet, keep waiting for
     * A2DP to establish (until the max wait); a genuine speaker-only route falls
     * through after the timeout (the controller's route-loss-settle handles the
     * pause meanwhile). Reopens WITHOUT flushing so the preserved encoded buffer
     * (e.g. across a phone-call freeze) refills the fresh ring.
     */
    private fun commitReopen(reason: String) {
        reopenSettleRunnable = null
        if (!configured) {
            reopenInFlight = false
            reopenRequestedAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - reopenRequestedAtMs < REOPEN_MAX_WAIT_MS) {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val boundId = nativeOutput.deviceId()
            val boundGone = boundId <= 0 || outputs.none { it.id == boundId }
            val hasExternalSink = outputs.any { isExternalSink(it.type) }
            if (boundGone && !hasExternalSink) {
                Log.d(TAG, "Reopen ($reason): no external sink established yet, waiting")
                val r = Runnable { commitReopen(reason) }
                reopenSettleRunnable = r
                mainHandler.postDelayed(r, REOPEN_SETTLE_MS)
                return
            }
        }
        try {
            Log.d(TAG, "Reopen committing ($reason)")
            startNativeOutput()        // fresh stream on the now-settled route; clears disconnected_
            refreshRoutedDevice()
            onRoutingChanged?.invoke()
        } finally {
            reopenInFlight = false
            reopenRequestedAtMs = 0L
        }
    }

    /** A real music sink we are willing to bind to. SCO (voice channel) is excluded. */
    private fun isExternalSink(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> true
        else -> false
    }

    /**
     * Resolve the routed device from the native AAudio device id via
     * AudioManager (Oboe has no routing listener) and notify on change.
     */
    private fun refreshRoutedDevice() {
        if (!configured) return
        val id = nativeOutput.deviceId()
        val info = if (id > 0) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        } else {
            null
        }
        val newType = info?.type ?: -1
        val newName = info?.productName?.toString()
        if (newType == routedDeviceType && newName == routedProductName) return
        routedDeviceType = newType
        routedProductName = newName
        Log.d(TAG, "Routed device: type=$newType ($newName) id=$id")
        onRoutingChanged?.invoke()
    }

    // ---- Playback thread ----------------------------------------------------

    private fun ensurePlaybackThread() {
        synchronized(playbackThreadLock) {
            if (playbackThread?.isAlive == true) return
            val generation = ++playbackGeneration
            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                playbackLoop(generation)
            }, "SendspinTimeline").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    private fun playbackLoop(generation: Long) {
        while (playbackActive && generation == playbackGeneration) {
            if (paused || !configured) {
                sleepMs(10)
                continue
            }
            // Oboe disconnected (route preempted by a phone call etc.) but the
            // device stays present, so the device-callback never fires. Reopen
            // ourselves, else the dead stream never drains the ring (buffer grows
            // unbounded) and audio is silent while the UI shows "playing".
            if (nativeOutput.isDisconnected() && !reopenInFlight) {
                reopenInFlight = true
                mainHandler.post { reopenAfterDisconnect() }
                sleepMs(50)
                continue
            }
            // Park the producer while a reopen is in flight. startNativeOutput
            // tears down and recreates the native output, so for a window the
            // native pointer is 0: bufferedFrames() then reports 0 (no
            // backpressure) and write() drops every frame. Without this guard the
            // producer spins, draining the encoded frameQueue into the codec and
            // discarding it (the codec backlog is later flushed past the ring in
            // one drainDecoder), destroying the deep buffer preserved across a
            // phone-call freeze and leaving a shallow, underrunning ring.
            // reopenAfterDisconnect clears the flag once the stream is back.
            if (reopenInFlight) {
                sleepMs(10)
                continue
            }
            // The start buffer gate applies ONLY before the first startTrack.
            // Once playing, keep feeding the ring unconditionally — re-gating on
            // startBufferMs every iteration would BLOCK writes whenever the buffer
            // dips below the gate (e.g. the server throttling its feed to ~288 ms
            // after a reopen), starving the ring to a 1-frame trickle -> underrun
            // and a permanently shallow buffer. Underruns while playing are
            // handled by the native callback, not by stalling the producer.
            if (!playbackStarted) {
                if (!startupReady()) {
                    sleepMs(10)
                    continue
                }
                startTrack()
            }

            // Backpressure: keep the native ring around RING_TARGET_MS, leaving
            // the rest encoded. Prevents draining a multi-second server burst
            // into the (shorter) ring and dropping audio.
            val ringTargetFrames = activeSampleRate.toLong() * RING_TARGET_MS / 1000L
            if (nativeOutput.bufferedFrames() >= ringTargetFrames) {
                sleepMs(10)
                continue
            }

            val drained = drainDecoder(generation)
            if (drained) continue

            // A frame held from a previous iteration (codec input buffers were
            // momentarily full) takes priority over a new one and must not be
            // dropped, or the FIFO gets a content hole (audible skip, invisible
            // to underrun; seen on slower decoders like Xiaomi).
            val frame: EncodedFrame
            val held = pendingFrame
            if (held != null) {
                frame = held
                pendingFrame = null
            } else {
                val polled = try {
                    frameQueue.poll(10, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // releaseInternal interrupts us to stop (release / format change).
                    // Exit cleanly instead of crashing the producer thread.
                    break
                } ?: continue
                frameQueueBytes.addAndGet(-polled.length.toLong())
                frame = polled
            }
            if (frame.generation != configureGeneration) continue

            if (activeCodec == "pcm") {
                // writeChunk owns the 24->16 conversion; do not convert here too.
                writeChunk(frame.serverTimestampUs, frame.data, frame.offset, frame.length, frame.generation, generation)
            } else {
                // Hold + retry instead of dropping when no codec input buffer is
                // free; draining output below frees the codec to accept it.
                if (!queueCodecInput(frame)) pendingFrame = frame
                drainDecoder(generation)
            }
        }
    }

    private fun startupReady(): Boolean {
        val neededMs = startBufferMs
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("buffer", neededMs)
            return false
        }
        return startupGate(neededMs)
    }

    /**
     * Drop frames whose intended presentation time is already in the (near)
     * past so the first fed chunk is schedulable on the timeline. Shared
     * helper; the SYNC engine calls it from its clock gate.
     */
    protected fun trimStartupLateFrames(neededMs: Long, headroomUs: Long): Boolean {
        if (playbackStarted) return true
        val minPresentationUs = nowUs() + headroomUs
        var droppedFrames = 0
        var droppedBytes = 0L
        var nextPlan: TimingPlan? = null
        while (true) {
            val head = frameQueue.peek() ?: break
            val plan = timingPlan(head.serverTimestampUs)
            if (plan.presentationUs >= minPresentationUs) {
                nextPlan = plan
                break
            }
            val dropped = frameQueue.poll() ?: break
            frameQueueBytes.addAndGet(-dropped.length.toLong())
            droppedFrames++
            droppedBytes += dropped.length.toLong()
        }
        if (droppedFrames > 0) {
            val now = nowUs()
            Log.d(
                TAG,
                "Timing/start-trim frames=$droppedFrames bytes=$droppedBytes " +
                    "nextLead=${nextPlan?.let { (it.presentationUs - now) / 1000 } ?: -1}ms " +
                    "headroom=${headroomUs / 1000}ms buf=${bufferDurationMs()}ms ${clockDebug()}"
            )
        }
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("post-trim-buffer", neededMs)
            return false
        }
        return true
    }

    private fun startTrack() {
        // Re-arm the mute cushion from the moment audio actually starts flowing
        // (the boundary mute may have been set much earlier, during rebuffer).
        beginStartupMute()
        playbackStarted = true
        startupOffsetMs = 0.0
        transitionSyncState(SyncState.SYNCHRONIZED)
        Log.d(
            TAG,
            "Synchronized codec=$activeCodec buf=${bufferDurationMs()}ms bytes=${bufferedBytes()} " +
                "measuredLat=${measuredOutputLatencyUs / 1000}ms staticDelay=${routeAcousticExtraUs / 1000}ms " +
                "syncDelay=${syncDelayMs}ms ${clockDebug()}"
        )
    }

    /**
     * Queues one encoded frame to the codec input. Returns true if the frame was
     * consumed (queued, or genuinely unusable/oversized), false if no input
     * buffer was free and the caller must HOLD and retry the frame, never
     * dropping it (a dropped frame is a content hole = audible skip, invisible to
     * the output underrun counter; observed on slower decoders like Xiaomi).
     */
    private fun queueCodecInput(frame: EncodedFrame): Boolean {
        return synchronized(codecLock) {
            // Read the codec INSIDE the lock: a concurrent format change
            // (releaseInternal, also under codecLock) releases it and sets it
            // null, so a ref captured before the lock would be stale/Released.
            val mc = codec ?: return@synchronized true
            try {
                // 10 ms (was 1 ms): absorb a momentarily-busy decoder without
                // bouncing. The native ring (~2.5 s) covers this producer stall,
                // and a false return makes the caller retry rather than drop.
                val inputIndex = mc.dequeueInputBuffer(10_000)
                if (inputIndex < 0) return@synchronized false
                val input = mc.getInputBuffer(inputIndex) ?: return@synchronized false
                input.clear()
                if (frame.length > input.remaining()) {
                    Log.w(TAG, "Oversized $activeCodec frame dropped: ${frame.length}B")
                    return@synchronized true
                }
                input.put(frame.data, frame.offset, frame.length)
                decoderMarks.addLast(DecoderMark(frame.serverTimestampUs, frame.generation))
                mc.queueInputBuffer(inputIndex, 0, frame.length, frame.serverTimestampUs, 0)
            } catch (e: IllegalStateException) {
                // Codec reconfigured/released out from under us (defensive: the
                // join in releaseInternal should normally prevent this).
                Log.w(TAG, "queueCodecInput skipped: codec not executing (${e.message})")
                return@synchronized true
            }
            if (receivedFrameCount <= 8 || receivedFrameCount % 500 == 0L) {
                Log.d(
                    TAG,
                    "Timing/codec-in codec=$activeCodec serverTs=${frame.serverTimestampUs / 1000}ms " +
                        "payload=${frame.length}B marks=${decoderMarks.size} gen=${frame.generation}"
                )
            }
            true
        }
    }

    // A decoded-stream frame that could not be queued to the codec yet (input
    // buffers momentarily full on a slow decoder). Held and retried next
    // iteration instead of dropped; dropping punched audible content holes.
    private var pendingFrame: EncodedFrame? = null

    private fun drainDecoder(generation: Long): Boolean {
        if (codec == null) return false
        var wroteAny = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            var ready = false
            var chunkTs = 0L
            var chunkLen = 0
            var chunkGen = 0L
            val more = synchronized(codecLock) {
                // Read inside the lock; a concurrent format change releases it.
                val mc = codec ?: return@synchronized false
                val outputIndex = try {
                    mc.dequeueOutputBuffer(info, 0)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "drainDecoder stop: codec not executing (${e.message})")
                    return@synchronized false
                }
                if (outputIndex < 0) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt = mc.outputFormat
                        Log.d(TAG, "Decoder output: ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                    }
                    return@synchronized false
                }
                if (info.size > 0) {
                    // Only consume a timestamp mark for a real output buffer. A
                    // zero-size buffer (e.g. Opus pre-skip priming) must NOT eat
                    // a mark or the FIFO desyncs and every later chunk is
                    // mislabelled by one frame.
                    val mark = decoderMarks.pollFirst()
                    if (mark != null) {
                        if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                        mc.getOutputBuffer(outputIndex)?.get(pcmScratch, 0, info.size)
                        chunkTs = mark.serverTimestampUs
                        chunkLen = info.size
                        chunkGen = mark.generation
                        ready = true
                        decodedChunkCount++
                        if (decodedChunkCount <= 8 || decodedChunkCount % 500 == 0L) {
                            val frames = info.size / (activeChannels * 2).coerceAtLeast(1)
                            Log.d(
                                TAG,
                                "Timing/decode-out chunk#$decodedChunkCount codec=$activeCodec " +
                                    "serverTs=${mark.serverTimestampUs / 1000}ms bytes=${info.size} frames=$frames " +
                                    "dur=${frames * 1000L / activeSampleRate.coerceAtLeast(1)}ms marks=${decoderMarks.size}"
                            )
                        }
                    }
                }
                mc.releaseOutputBuffer(outputIndex, false)
                true
            }
            if (!more) break
            // Feed OUTSIDE the codec lock. pcmScratch is safe to reuse next
            // iteration: writeChunk is synchronous (a JNI memcpy into the ring),
            // so the next decode only runs after it returns.
            if (ready && chunkGen == configureGeneration && generation == playbackGeneration) {
                writeChunk(chunkTs, pcmScratch, 0, chunkLen, chunkGen, generation)
                wroteAny = true
            }
        }
        return wroteAny
    }

    /**
     * Hands a decoded PCM chunk to the native output, tagged with its intended
     * absolute presentation time. No sleep, no late-drop, no latency
     * subtraction: the native callback owns the timeline alignment and DAC
     * latency. This thread only decodes and feeds, so a GC pause here is
     * absorbed by the ring instead of underrunning the DAC.
     */
    private fun writeChunk(
        serverTimestampUs: Long,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        chunkGeneration: Long,
        generation: Long,
    ) {
        if (length <= 0 || generation != playbackGeneration || paused) return
        if (chunkGeneration != configureGeneration) return
        val plan = timingPlan(serverTimestampUs)

        if (activeBitDepth == 24 && activeCodec == "pcm") {
            val slice = if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length)
            val converted = convertPcm24To16(slice)
            nativeOutput.write(converted, 0, converted.size, plan.presentationUs)
        } else if (offset % 2 != 0) {
            // Odd offset would misalign the native int16 reinterpret; realign.
            if (pcmAligned.size < length) pcmAligned = ByteArray(length)
            System.arraycopy(pcm, offset, pcmAligned, 0, length)
            nativeOutput.write(pcmAligned, 0, length, plan.presentationUs)
        } else {
            nativeOutput.write(pcm, offset, length, plan.presentationUs)
        }

        val frames = length / (activeChannels * 2).coerceAtLeast(1)
        writtenChunkCount++
        maybeSampleNativeSync()
        maybeLogWriteTiming(serverTimestampUs, plan, nowUs(), length, frames)

        // Unmute only once actually locked (fresh native drift < 5 ms), not at a
        // loose 20 ms: the native fast skip/insert convergence runs while muted,
        // so a tight gate keeps every audible sample on the click-free resampler.
        // DIRECT (no drift correction) unmutes on time alone.
        if (syncMuted &&
            syncMuteStartedMs > 0L &&
            System.currentTimeMillis() - syncMuteStartedMs > startupMuteMs &&
            (!isSync || abs(nativeOutput.driftEmaUs()) < 5_000L)
        ) {
            syncMuted = false
            syncMuteStartedMs = 0L
            applyOutputVolume()
        }
    }

    private fun timingPlan(serverTimestampUs: Long): TimingPlan {
        // Output (HAL) latency is handled natively; this value is only used by
        // the DIRECT anchor (to start ~now + latency) and for logging.
        val outputLatencyUs = nativeOutput.outputLatencyUs()
        val local = computeLocalPlan(serverTimestampUs, outputLatencyUs)
        // Acoustic correction (routeAcousticExtraUs) and the unreported HAL/DAC
        // gap exist ONLY to land our ACOUSTIC output on the shared GROUP timeline
        // (serverTs + headroom), matching the official clients. In solo (DIRECT)
        // there is no peer to phase-lock to, so neither applies — and the BT
        // acoustic (~400 ms) would otherwise schedule the first frame in the PAST
        // (lead < 0), making the native anchor chase a moving/past target. Solo is
        // pure FIFO: present = local anchor + headroom (+ the client UX nudge).
        val staticDelayUs = if (isSync) routeAcousticExtraUs.coerceAtLeast(0L) else 0L
        // The native dac0 alignment compensates only the HAL latency that
        // getTimestamp reports (outputLatencyUs). The real output path is longer
        // (DAC/analog); AudioManager.getOutputLatency gives the full value. Shift
        // playback earlier by that unreported gap so our ACOUSTIC output lands on
        // the group timeline (serverTs + headroom) — matching official clients
        // without a manual nudge.
        val unreportedLatencyUs = if (isSync) {
            (halOutputLatencyUs - outputLatencyUs).coerceAtLeast(0L)
        } else {
            0L
        }
        // Intended presentation time: timeline + headroom, shifted earlier by the
        // external acoustic/BT delay, the unreported HAL gap, and the UX nudge.
        val presentationUs = local.localOutputUs +
            local.headroomUs -
            staticDelayUs -
            unreportedLatencyUs +
            syncDelayMs.toLong() * 1000L
        return TimingPlan(local.localOutputUs, staticDelayUs, outputLatencyUs, local.headroomUs, presentationUs)
    }

    private fun maybeSampleNativeSync() {
        if (!isSync) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSyncSampleCallbackMs < SYNC_SAMPLE_CALLBACK_MS) return
        lastSyncSampleCallbackMs = nowMs
        // Native drift = intended - DAC presentation. That IS the live sync
        // error the UI shows; the callback also corrects it.
        smoothedSyncErrorMs = nativeOutput.driftEmaUs() / 1000.0
        onSyncSample?.invoke(
            smoothedSyncErrorMs.toFloat(),
            measuredOutputLatencyUs / 1000f,
            (clockSynchronizer?.errorUs() ?: 0L) / 1000f,
            smoothedSyncErrorMs.toFloat(),
        )
    }

    protected fun maybeLogStartupWait(reason: String, neededMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastStartupLogMs < 1_000L) return
        lastStartupLogMs = now
        Log.d(
            TAG,
            "Timing/start-wait reason=$reason mode=$correctionMode buf=${bufferDurationMs()}ms " +
                "need=${neededMs}ms bytes=${bufferedBytes()} codec=$activeCodec " +
                "measuredLat=${measuredOutputLatencyUs / 1000}ms staticDelay=${routeAcousticExtraUs / 1000}ms " +
                "syncDelay=${syncDelayMs}ms ${clockDebug()}"
        )
    }

    private fun maybeLogWriteTiming(
        serverTimestampUs: Long,
        plan: TimingPlan,
        nowUsValue: Long,
        written: Int,
        frames: Int,
    ) {
        val nowMs = System.currentTimeMillis()
        val force = writtenChunkCount <= 12 || writtenChunkCount % 250 == 0L
        if (!force && nowMs - lastTimingLogMs < 1_000L) return
        lastTimingLogMs = nowMs
        Log.d(
            TAG,
            "Timing/write chunk#$writtenChunkCount codec=$activeCodec serverTs=${serverTimestampUs / 1000}ms " +
                timingSummary(plan, nowUsValue) +
                " err=${"%.2f".format(smoothedSyncErrorMs)}ms fed=${written}B frames=$frames " +
                "frameDur=${frames * 1000L / activeSampleRate.coerceAtLeast(1)}ms " +
                "ringMs=${nativeOutput.bufferedFrames() * 1000L / activeSampleRate.coerceAtLeast(1)} " +
                "buf=${bufferDurationMs()}ms bytes=${bufferedBytes()} " +
                "syncMuted=$syncMuted state=$syncState ${clockDebug()}"
        )
    }

    private fun timingSummary(plan: TimingPlan, nowUs: Long): String =
        "localOut=${plan.localOutputUs / 1000}ms present=${plan.presentationUs / 1000}ms " +
            "now=${nowUs / 1000}ms lead=${(plan.presentationUs - nowUs) / 1000}ms " +
            "outLat=${plan.outputLatencyUs / 1000}ms staticDelay=${plan.staticDelayUs / 1000}ms " +
            "syncDelay=${syncDelayMs}ms headroom=${plan.headroomUs / 1000}ms"

    protected fun clockDebug(): String {
        val sync = clockSynchronizer ?: return "clock=none"
        return "clockSamples=${sync.currentSampleCount()} clockErr=${sync.errorUs()}us " +
            "clockOffset=${sync.currentOffsetUs()}us synced=${sync.isSynced()}"
    }

    private fun flushQueuesAndDecoder() {
        // A flush discards the ring, so any pending freeze is moot; clear it so
        // the native consumer is not left holding after the ring is reset.
        outputFrozen = false
        nativeOutput.setFrozen(false)
        frameQueue.clear()
        frameQueueBytes.set(0)
        decoderMarks.clear()
        lastEnqueuedTimestampUs = 0L
        estimatedFrameDurationUs = 20_000L
        onFlush()
        nativeOutput.flush()
        synchronized(codecLock) {
            try { codec?.flush() } catch (_: Exception) {}
        }
    }

    /**
     * Freeze the native consumer WITHOUT dropping the ring: fade to silence and
     * hold the read position so the buffered audio survives a transient
     * interruption (focus loss) and resumes instantly + click-free, with the
     * deep buffer intact. Unlike a pause/flush, nothing is discarded and the
     * producer keeps the ring full. Solo/DIRECT only — the server feeds realtime
     * after a flush and never rebuilds the deep buffer, so freezing is the only
     * way to keep it across the gap.
     */
    fun freezeOutput() {
        Log.d(TAG, "Freeze output (preserve buffer) buf=${bufferDurationMs()}ms state=$syncState")
        outputFrozen = true
        nativeOutput.setFrozen(true)
    }

    fun unfreezeOutput() {
        Log.d(TAG, "Unfreeze output (resume from preserved buffer) buf=${bufferDurationMs()}ms state=$syncState isSync=$isSync")
        outputFrozen = false
        if (isSync) {
            // Grouped: the leader kept playing during the interruption, so the
            // preserved buffer is now BEHIND the live group timeline. Re-mute and
            // reset the sync metrics so the native correction SKIPS forward to the
            // current group position (still in the buffer for interruptions
            // shorter than the buffer depth) inaudibly, then unmutes once
            // re-locked. DIRECT (solo) has no peer, so it just fades back in from
            // the exact freeze point below.
            beginStartupMute()
            resetSyncMetrics()
        }
        nativeOutput.setFrozen(false)
    }

    /**
     * Stop the Oboe output stream so an acoustic speaker calibration chirp gets a
     * CLEAN, exclusive audio path. Freezing (setFrozen) keeps the stream OPEN and
     * the chirp shares the mixer, which collapsed the measured round trip
     * (~150ms -> ~62ms). pauseStream requestStops the stream while preserving the
     * ring; resume restarts it and, when grouped, re-mutes so the native
     * correction skips forward to the live group position (the timeline advanced
     * during the chirp) inaudibly.
     */
    fun pauseOutputForCalibration() {
        Log.d(TAG, "Pause output for calibration (clean chirp path) state=$syncState")
        nativeOutput.pauseStream()
    }

    fun resumeOutputAfterCalibration() {
        Log.d(TAG, "Resume output after calibration isSync=$isSync")
        nativeOutput.resumeStream()
        if (isSync) {
            beginStartupMute()
            resetSyncMetrics()
        }
    }

    private fun resetSyncMetrics() {
        smoothedSyncErrorMs = 0.0
        startupOffsetMs = 0.0
        resyncCount = 0
    }

    private fun transitionSyncState(newState: SyncState) {
        if (syncState == newState) return
        val old = syncState
        syncState = newState
        Log.d(TAG, "Sync: $old -> $newState (buf=${bufferDurationMs()}ms, ${bufferedBytes() / 1000}KB)")
        // Stop the always-on output after a grace once genuinely idle; cancel the
        // moment audio is flowing/aligning again so a track transition never
        // churns the HAL.
        if (newState == SyncState.IDLE) scheduleIdleStop() else cancelIdleStop()
        onSyncStateChanged?.invoke(newState)
    }

    private fun releaseInternal() {
        cancelIdleStop()
        outputPausedForIdle = false
        reopenSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        reopenSettleRunnable = null
        reopenRequestedAtMs = 0L
        reopenInFlight = false
        outputFrozen = false
        playbackActive = false
        paused = false
        playbackStarted = false
        playbackGeneration++
        // Stop the producer and WAIT for it to exit before releasing the codec.
        // Without the join the interrupted producer can still be mid
        // queueInputBuffer/dequeue on the codec we are about to release ->
        // IllegalStateException ("valid only at Executing states; currently at
        // Released state") on a format change (e.g. FLAC -> Opus). The producer
        // catches the interrupt and breaks, so the join returns promptly.
        val producer = synchronized(playbackThreadLock) {
            val t = playbackThread
            playbackThread = null
            t
        }
        producer?.interrupt()
        if (producer != null && producer !== Thread.currentThread()) {
            try { producer.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        flushQueuesAndDecoder()
        synchronized(codecLock) {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
        unregisterDeviceCallback()
        nativeOutput.release()
        routedDeviceType = -1
        routedProductName = null
        configured = false
        syncMuted = false
        syncMuteStartedMs = 0L
        transitionSyncState(SyncState.IDLE)
    }

    private fun createCodec(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
    ): MediaCodec? = when (codecName) {
        "opus" -> createOpusDecoder(sampleRate, channels, codecHeader)
        "flac" -> createFlacDecoder(sampleRate, channels, bitDepth, codecHeader)
        "pcm" -> null
        else -> {
            Log.w(TAG, "Unsupported codec $codecName; treating stream as silent")
            null
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OPUS_MAX_INPUT_SIZE)
        val csd0 = codecHeader?.let {
            try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
        } ?: createOpusHeader(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(preSkipNs); rewind() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); rewind() })
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createFlacDecoder(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MAX_INPUT_SIZE)
        format.setInteger("bit-depth", bitDepth)
        codecHeader?.let {
            try { format.setByteBuffer("csd-0", ByteBuffer.wrap(Base64.decode(it, Base64.DEFAULT))) } catch (_: Exception) {}
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(channels.toByte())
            putShort(3840.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0)
        }.array()

    private fun convertPcm24To16(data: ByteArray): ByteArray {
        val out = ByteArray(data.size / 3 * 2)
        var src = 0
        var dst = 0
        while (src + 2 < data.size) {
            out[dst++] = data[src + 1]
            out[dst++] = data[src + 2]
            src += 3
        }
        return out
    }

    private fun parseTimestampUs(data: ByteArray): Long {
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (data[i].toLong() and 0xffL)
        return ts
    }

    protected fun nowUs(): Long = System.nanoTime() / 1000L

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
