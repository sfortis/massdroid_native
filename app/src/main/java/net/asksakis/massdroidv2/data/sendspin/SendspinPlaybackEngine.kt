package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
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
 * Shared Sendspin playback machinery: MediaCodec decode, AudioTrack output,
 * the encoded frame queue, the timeline playback thread, lifecycle/boundary
 * handling, generation guarding and output-latency measurement.
 *
 * Every decoded PCM chunk carries its input server timestamp and is written at
 * an absolute deadline. WHAT that deadline is differs by role and is the only
 * thing subclasses provide:
 *
 *  - [SendspinSyncEngine]   grouped: absolute group timeline + scheduling
 *                            headroom, clock-readiness gate, late-drop and
 *                            sync-error reporting.
 *  - [SendspinDirectEngine] solo: a local anchor so playback starts ~now with
 *                            no clock dependency and no group machinery.
 *
 * The manager runs exactly one engine at a time and swaps instances at a group
 * join/leave boundary (already a hard relock), so each role stays isolated.
 */
abstract class SendspinPlaybackEngine : SendspinAudioEngine {
    companion object {
        private const val TAG = "AudioStream"
        private const val HEADER_SIZE = 9
        private const val TYPE_PLAYER_AUDIO = 4
        // Hard memory ceiling for the encoded frame queue. MUST stay well above
        // the server's buffer_capacity (~1.5 MB) or the queue sits exactly at
        // the cap during steady playback and the overflow guard keeps dropping
        // frames -> audible gaps. 6 MB gives ample headroom so it never fires
        // in normal operation; it is only a runaway backstop.
        private const val MAX_ENCODED_BUFFER_BYTES = 6_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024
        private const val LATE_DROP_MS = 120L
        private const val SLEEP_SPIN_US = 500L
        private const val STARTUP_MUTE_MS = 350L
        private const val LATENCY_EMA_ALPHA = 0.05
        private const val SYNC_SAMPLE_CALLBACK_MS = 1_000L
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
        val targetWriteUs: Long,
    )

    /** localOutput + headroom for a chunk; the role-specific scheduling decision. */
    protected data class LocalPlan(val localOutputUs: Long, val headroomUs: Long)

    // ---- Role hooks --------------------------------------------------------

    /** Minimum buffered audio before playback may start. */
    protected abstract val startBufferMs: Long

    /** Whether this engine runs the grouped sync machinery (late-drop, sync-error, mute, clock gate). */
    protected val isSync: Boolean get() = correctionMode == CorrectionMode.SYNC

    /** Role-specific local output time + headroom for [serverTimestampUs]. */
    protected abstract fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan

    /** Extra start gate after the buffer threshold is met. Default: ready now. */
    protected open fun startupGate(neededMs: Long): Boolean = true

    /** Called from [flushQueuesAndDecoder] so a role can reset its own timeline state. */
    protected open fun onFlush() {}

    // ---- Shared state ------------------------------------------------------

    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private val decoderMarks = ArrayDeque<DecoderMark>()
    private val codecLock = Any()
    private val playbackThreadLock = Any()
    private val latencyTimestamp = AudioTimestamp()
    // Reused decoded-PCM scratch buffer (playback thread only) so we don't
    // allocate a fresh ByteArray per decoded chunk. Grown on demand.
    private var pcmScratch = ByteArray(16384)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var configured = false
    @Volatile private var playbackActive = false
    @Volatile protected var playbackStarted = false
    // Set on a local track-change discontinuity (next/previous): the server
    // keeps sending the OLD track's frames until it processes the skip, and
    // those in-flight frames would otherwise be accepted (they get the bumped
    // generation) and briefly play the previous track. Drop all frames until
    // the server's fresh stream/start (configure) arrives. NOT set for seek,
    // which stays on the same stream (stream/clear, frames continue).
    @Volatile private var awaitingFreshStream = false
    @Volatile private var paused = false
    @Volatile private var configureGeneration = 0L
    @Volatile private var playbackGeneration = 0L
    @Volatile protected var activeCodec = "flac"
    @Volatile protected var activeBitDepth = 16
    @Volatile protected var activeSampleRate = 48_000
    @Volatile protected var activeChannels = 2
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile protected var startupWaitStartedMs = 0L
    @Volatile private var routedDeviceType = -1
    @Volatile private var routedProductName: String? = null
    @Volatile private var totalFramesWritten = 0L
    @Volatile protected var outputLatencyFallbackUs = 0L
    @Volatile private var syncMuteStartedMs = 0L
    @Volatile private var receivedFrameCount = 0L
    @Volatile private var decodedChunkCount = 0L
    @Volatile private var writtenChunkCount = 0L
    @Volatile private var lastTimingLogMs = 0L
    @Volatile private var lastStartupLogMs = 0L
    @Volatile private var lastSyncSampleCallbackMs = 0L
    @Volatile private var playbackThread: Thread? = null

    override var onSyncStateChanged: ((SyncState) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float, dacAbsoluteMs: Float?) -> Unit)? = null
    override var clockSynchronizer: ClockSynchronizer? = null
    override var syncDelayMs: Int = 0
    override var routeAcousticExtraUs: Long = 0L
    override val measuredOutputLatencyUs: Long get() = measuredLatencyUs
    final override var syncState: SyncState = SyncState.IDLE
        private set

    @Volatile protected var measuredLatencyUs = 0L
    // Last converged latency for the current route. Survives a same-route codec
    // rebuild (which zeroes measuredLatencyUs) so a fresh start schedules
    // against the real ~27ms instead of the coarse minBuf fallback. Cleared on
    // an actual output-route change, where the latency genuinely differs.
    @Volatile protected var lastStableLatencyUs = 0L
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
        awaitingFreshStream = false
        startupWaitStartedMs = 0L
        lastFrameReceivedMs = System.currentTimeMillis()
        resetSyncMetrics()

        if (sameFormat) {
            Log.d(TAG, "configure ${startType.name} reuse codec=$codecName ${sampleRate}Hz/${bitDepth}bit")
            flushQueuesAndDecoder()
            playbackStarted = false
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

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(sampleRate * channels * 2 / 20)
        // Deeper software buffer (still LOW_LATENCY, unlike the banned NONE
        // path) so a 96 ms FLAC frame plus playback-thread jitter cannot drain
        // the DAC between deadline writes -> fewer underrun micro-mutes. The
        // fast-mixer getTimestamp stays precise, so the added (stable) latency
        // is measured and compensated without the NONE wobble/desync.
        val trackBufferBytes = minBuf * 4
        outputLatencyFallbackUs = minBuf.toLong() * 1_000_000L / (sampleRate.toLong() * channels * 2L)

        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // PERFORMANCE_MODE_NONE is BANNED here (see CLAUDE.md): the normal
            // mixer pushes getTimestamp output latency to ~150 ms and makes it
            // wobble, which the open-loop scheduler turns into audible desync —
            // and it did not even cure the underruns. Stay LOW_LATENCY.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        track.addOnRoutingChangedListener({ router ->
            val device = router.routedDevice
            val newType = device?.type ?: -1
            val newName = device?.productName?.toString()
            if (newType == routedDeviceType && newName == routedProductName) return@addOnRoutingChangedListener
            routedDeviceType = newType
            routedProductName = newName
            Log.d(TAG, "AudioTrack route changed: type=$newType ($newName)")
            onRoutingChanged?.invoke()
        }, null)

        audioTrack = track
        routedDeviceType = track.routedDevice?.type ?: -1
        routedProductName = track.routedDevice?.productName?.toString()
        onRoutingChanged?.invoke()

        codec = createCodec(codecName, sampleRate, channels, bitDepth, codecHeader)
        configured = true
        playbackActive = true
        playbackStarted = false
        paused = false
        totalFramesWritten = 0L
        measuredLatencyUs = 0L
        transitionSyncState(SyncState.IDLE)
        ensurePlaybackThread()
    }

    override fun currentConfigureGeneration(): Long = configureGeneration

    override fun onBinaryMessage(data: ByteArray, generation: Long) {
        if (!configured || paused || generation != configureGeneration || awaitingFreshStream) return
        if (data.size <= HEADER_SIZE || data[0].toInt() != TYPE_PLAYER_AUDIO) return
        // Overflow backstop: if we are genuinely over the memory ceiling, drop
        // THIS incoming (newest) frame. Never poll() the head — that is the
        // frame about to be played, and dropping it punches an audible hole in
        // the current output. With MAX_ENCODED_BUFFER_BYTES well above the
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
        // Reference the WS buffer with an offset instead of copying the payload
        // out — the client gives us a fresh ByteArray per frame, so a second
        // copy is pure GC pressure on the audio path.
        frameQueue.offer(EncodedFrame(serverTimestampUs, data, HEADER_SIZE, payloadLength, generation))
        frameQueueBytes.addAndGet(payloadLength.toLong())
        lastEnqueuedTimestampUs = serverTimestampUs
        lastFrameReceivedMs = System.currentTimeMillis()
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
            Log.d(TAG, "Pause boundary: flushing AudioTrack/decoder buf=${bufferDurationMs()}ms state=$syncState")
            flushQueuesAndDecoder()
            playbackStarted = false
            syncMuted = false
            syncMuteStartedMs = 0L
            startupWaitStartedMs = 0L
            resetSyncMetrics()
            try { audioTrack?.pause() } catch (_: Exception) {}
            transitionSyncState(SyncState.IDLE)
        } else {
            Log.d(TAG, "Resume boundary: waiting for fresh timeline buffer mode=$correctionMode ${clockDebug()}")
            playbackActive = true
            ensurePlaybackThread()
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(if (muted || syncMuted) 0f else currentVolume)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setVolume(if (muted || syncMuted) 0f else currentVolume)
    }

    override fun onStreamEnd() {
        Log.d(TAG, "stream/end clear buf=${bufferDurationMs()}ms")
        configureGeneration++
        flushQueuesAndDecoder()
        playbackStarted = false
        syncMuted = false
        syncMuteStartedMs = 0L
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
        flushQueuesAndDecoder()
        playbackStarted = false
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        syncMuted = isSync
        syncMuteStartedMs = if (syncMuted) System.currentTimeMillis() else 0L
        transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason")
        clearBuffer()
        // Every local discontinuity (next/previous AND seek) makes MA open a
        // fresh stream (stream/end + stream/start). Until that stream/start
        // (configure) lands, the server is still flushing the OLD position's
        // in-flight frames; gate them out so we don't briefly play pre-seek /
        // previous-track audio (and the stutter from mixing it with the new
        // stream). configure() clears the gate.
        awaitingFreshStream = true
        Log.d(TAG, "Awaiting fresh stream after '$reason' — dropping in-flight frames")
    }

    override fun onTransportFailure() {
        val bufMs = bufferDurationMs()
        transitionSyncState(if (bufMs > startBufferMs) SyncState.HOLDOVER_PLAYING_FROM_BUFFER else SyncState.SYNC_ERROR_REBUFFERING)
    }

    override fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route changed: $reason acoustic=${routeAcousticExtraUs / 1000}ms")
        measuredLatencyUs = 0L
        lastStableLatencyUs = 0L
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        if (playbackStarted) {
            syncMuted = isSync
            syncMuteStartedMs = if (syncMuted) System.currentTimeMillis() else 0L
            playbackStarted = false
            try {
                audioTrack?.setVolume(0f)
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (_: Exception) {
            }
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        }
    }

    override fun getRoutedDeviceType(): Int? = audioTrack?.routedDevice?.type

    override fun getRoutedDeviceProductName(): String? =
        audioTrack?.routedDevice?.productName?.toString()

    override fun bufferDurationMs(): Long {
        val head = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tail = lastEnqueuedTimestampUs
        if (tail <= 0L || tail < head) return 0L
        return ((tail - head + estimatedFrameDurationUs) / 1000L).coerceAtLeast(0L)
    }

    override fun bufferedBytes(): Long = frameQueueBytes.get()

    override fun shiftAnchorForSyncDelayChange(deltaMs: Int) {
        resetSyncMetrics()
    }

    fun dacGroundTruthErrorMs(): Float? =
        if (isSync && syncState == SyncState.SYNCHRONIZED) smoothedSyncErrorMs.toFloat() else null

    override fun release() {
        releaseInternal()
    }

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
            val track = audioTrack
            if (track == null) {
                sleepMs(10)
                continue
            }
            if (!startupReady()) {
                sleepMs(10)
                continue
            }
            if (!playbackStarted) startTrack(track)

            val drained = drainDecoder(track, generation)
            if (drained) continue

            val frame = frameQueue.poll(10, TimeUnit.MILLISECONDS) ?: continue
            frameQueueBytes.addAndGet(-frame.length.toLong())
            if (frame.generation != configureGeneration) continue

            if (activeCodec == "pcm") {
                // writeScheduled owns the 24->16 conversion; do not convert here
                // too or 24-bit PCM gets mangled by a double pass.
                writeScheduled(frame.serverTimestampUs, frame.data, frame.offset, frame.length, frame.generation, track, generation)
            } else {
                queueCodecInput(frame)
                drainDecoder(track, generation)
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
     * Drop frames whose absolute deadline is already in the (near) past so the
     * first written chunk is schedulable. Shared helper; the SYNC engine calls
     * it from its clock gate, solo does not need it.
     */
    protected fun trimStartupLateFrames(neededMs: Long, headroomUs: Long): Boolean {
        if (playbackStarted) return true
        val minTargetWriteUs = nowUs() + headroomUs
        var droppedFrames = 0
        var droppedBytes = 0L
        var nextPlan: TimingPlan? = null
        while (true) {
            val head = frameQueue.peek() ?: break
            val plan = timingPlan(head.serverTimestampUs)
            if (plan.targetWriteUs >= minTargetWriteUs) {
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
                    "nextLead=${nextPlan?.let { (it.targetWriteUs - now) / 1000 } ?: -1}ms " +
                    "headroom=${headroomUs / 1000}ms buf=${bufferDurationMs()}ms ${clockDebug()}"
            )
        }
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("post-trim-buffer", neededMs)
            return false
        }
        return true
    }

    private fun startTrack(track: AudioTrack) {
        try {
            syncMuted = isSync
            syncMuteStartedMs = if (syncMuted) System.currentTimeMillis() else 0L
            track.setVolume(if (muted || syncMuted) 0f else currentVolume)
            track.play()
            playbackStarted = true
            startupOffsetMs = 0.0
            transitionSyncState(SyncState.SYNCHRONIZED)
            Log.d(
                TAG,
                "Synchronized codec=$activeCodec buf=${bufferDurationMs()}ms bytes=${bufferedBytes()} " +
                    "fallbackLat=${outputLatencyFallbackUs / 1000}ms measuredLat=${measuredLatencyUs / 1000}ms " +
                    "staticDelay=${routeAcousticExtraUs / 1000}ms syncDelay=${syncDelayMs}ms ${clockDebug()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack start failed: ${e.message}")
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        }
    }

    private fun queueCodecInput(frame: EncodedFrame) {
        val mc = codec ?: return
        synchronized(codecLock) {
            val inputIndex = mc.dequeueInputBuffer(1_000)
            if (inputIndex < 0) return
            val input = mc.getInputBuffer(inputIndex) ?: return
            input.clear()
            if (frame.length > input.remaining()) {
                Log.w(TAG, "Oversized $activeCodec frame dropped: ${frame.length}B")
                return
            }
            input.put(frame.data, frame.offset, frame.length)
            decoderMarks.addLast(DecoderMark(frame.serverTimestampUs, frame.generation))
            mc.queueInputBuffer(inputIndex, 0, frame.length, frame.serverTimestampUs, 0)
            if (receivedFrameCount <= 8 || receivedFrameCount % 500 == 0L) {
                Log.d(
                    TAG,
                    "Timing/codec-in codec=$activeCodec serverTs=${frame.serverTimestampUs / 1000}ms " +
                        "payload=${frame.length}B marks=${decoderMarks.size} gen=${frame.generation}"
                )
            }
        }
    }

    private fun drainDecoder(track: AudioTrack, generation: Long): Boolean {
        val mc = codec ?: return false
        var wroteAny = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            var ready = false
            var chunkTs = 0L
            var chunkLen = 0
            var chunkGen = 0L
            val more = synchronized(codecLock) {
                val outputIndex = mc.dequeueOutputBuffer(info, 0)
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
            // Write OUTSIDE the codec lock (writeScheduled sleeps to the deadline).
            // pcmScratch is safe to reuse next iteration: writeScheduled is
            // synchronous, so the next decode only runs after it returns.
            if (ready && chunkGen == configureGeneration && generation == playbackGeneration) {
                writeScheduled(chunkTs, pcmScratch, 0, chunkLen, chunkGen, track, generation)
                wroteAny = true
            }
        }
        return wroteAny
    }

    private fun writeScheduled(
        serverTimestampUs: Long,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        chunkGeneration: Long,
        track: AudioTrack,
        generation: Long,
    ) {
        if (length <= 0 || generation != playbackGeneration || paused) return
        val plan = timingPlan(serverTimestampUs)
        val targetWriteUs = plan.targetWriteUs

        // Both modes gate each chunk to its deadline so the AudioTrack FIFO
        // stays shallow (~output latency) -> seeks flush almost no stale audio
        // and respond instantly. The deadline differs by mode (see computeLocalPlan):
        // SYNC uses the absolute group timeline; DIRECT uses a local anchor that
        // plays the first post-flush frame ~now. Late-drop is SYNC-only (DIRECT
        // has no peer to fall behind). The bail checks configureGeneration so a
        // track change/seek interrupts a stale sleep immediately.
        var now = nowUs()
        if (isSync) {
            val lateMs = (now - targetWriteUs) / 1000L
            if (lateMs > LATE_DROP_MS) {
                updateSyncError(lateMs.toDouble())
                Log.d(
                    TAG,
                    "Timing/drop late=${lateMs}ms serverTs=${serverTimestampUs / 1000}ms " +
                        timingSummary(plan, now) + " buf=${bufferDurationMs()}ms ${clockDebug()}"
                )
                return
            }
        }
        while (true) {
            now = nowUs()
            val remainingUs = targetWriteUs - now
            if (remainingUs <= SLEEP_SPIN_US) break
            sleepMs(((remainingUs - SLEEP_SPIN_US) / 1000L).coerceAtLeast(1L))
            if (generation != playbackGeneration || paused || chunkGeneration != configureGeneration) return
        }
        while (nowUs() < targetWriteUs && generation == playbackGeneration &&
            !paused && chunkGeneration == configureGeneration
        ) {
            Thread.yield()
        }

        val beforeWriteUs = nowUs()
        val written = try {
            if (activeBitDepth == 24 && activeCodec == "pcm") {
                val slice = if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length)
                val converted = convertPcm24To16(slice)
                track.write(converted, 0, converted.size)
            } else {
                track.write(pcm, offset, length)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack write failed: ${e.message}")
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            return
        }
        if (written <= 0) return

        val frames = written / (activeChannels * 2)
        totalFramesWritten += frames
        measureOutputLatency(track)
        if (isSync) {
            updateSyncError((beforeWriteUs - targetWriteUs) / 1000.0)
        }
        writtenChunkCount++
        maybeLogWriteTiming(serverTimestampUs, plan, beforeWriteUs, written, frames)

        if (syncMuted &&
            syncMuteStartedMs > 0L &&
            System.currentTimeMillis() - syncMuteStartedMs > STARTUP_MUTE_MS &&
            abs(smoothedSyncErrorMs) < 20.0
        ) {
            syncMuted = false
            syncMuteStartedMs = 0L
            if (!muted) track.setVolume(currentVolume)
        }
    }

    private fun timingPlan(serverTimestampUs: Long): TimingPlan {
        val staticDelayUs = routeAcousticExtraUs.coerceAtLeast(0L)
        // Prefer the live measurement, then the last stable value from this
        // route (survives a same-route codec rebuild so a fresh start is
        // accurate immediately), and only fall back to the coarse minBuf
        // estimate on a genuine cold start / route change.
        val outputLatencyUs = measuredLatencyUs.takeIf { it > 0L }
            ?: lastStableLatencyUs.takeIf { it > 0L }
            ?: outputLatencyFallbackUs
        val local = computeLocalPlan(serverTimestampUs, outputLatencyUs)
        val targetWriteUs = local.localOutputUs +
            local.headroomUs -
            staticDelayUs +
            syncDelayMs.toLong() * 1000L -
            outputLatencyUs
        return TimingPlan(local.localOutputUs, staticDelayUs, outputLatencyUs, local.headroomUs, targetWriteUs)
    }

    private fun updateSyncError(errorMs: Double) {
        smoothedSyncErrorMs = if (smoothedSyncErrorMs == 0.0) {
            errorMs
        } else {
            0.2 * errorMs + 0.8 * smoothedSyncErrorMs
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSyncSampleCallbackMs >= SYNC_SAMPLE_CALLBACK_MS) {
            lastSyncSampleCallbackMs = nowMs
            onSyncSample?.invoke(
                smoothedSyncErrorMs.toFloat(),
                (measuredOutputLatencyUs / 1000f),
                ((clockSynchronizer?.errorUs() ?: 0L) / 1000f),
                smoothedSyncErrorMs.toFloat(),
            )
        }
    }

    private fun measureOutputLatency(track: AudioTrack) {
        if (!track.getTimestamp(latencyTimestamp) || activeSampleRate <= 0) return
        val framesAtPort = latencyTimestamp.framePosition
        if (framesAtPort <= 0) return
        // Queued (unplayed) frames = frames we wrote since flush - frames the DAC
        // has presented. Both share the post-flush epoch (totalFramesWritten is
        // zeroed on flush, framePosition resets with AudioTrack.flush). Do NOT
        // mix framePosition with playbackHeadPosition: their epochs differ per
        // HAL and inject a device-specific static latency bias (issue #45).
        val queuedFrames = totalFramesWritten - framesAtPort
        if (queuedFrames < 0) return
        val tsAgeUs = (System.nanoTime() - latencyTimestamp.nanoTime) / 1000L
        if (tsAgeUs !in 0..50_000L) return
        val latencyUs = tsAgeUs + queuedFrames * 1_000_000L / activeSampleRate.toLong()
        if (latencyUs !in 1..500_000L) return
        measuredLatencyUs = if (measuredLatencyUs <= 0L) {
            latencyUs
        } else {
            (LATENCY_EMA_ALPHA * latencyUs + (1.0 - LATENCY_EMA_ALPHA) * measuredLatencyUs).toLong()
        }
        lastStableLatencyUs = measuredLatencyUs
        if (writtenChunkCount <= 8 || writtenChunkCount % 500 == 0L) {
            Log.d(
                TAG,
                "Timing/latency raw=${latencyUs / 1000}ms ema=${measuredLatencyUs / 1000}ms " +
                    "fallback=${outputLatencyFallbackUs / 1000}ms tsAge=${tsAgeUs / 1000}ms " +
                    "queuedFrames=$queuedFrames written=$totalFramesWritten port=$framesAtPort"
            )
        }
    }

    protected fun maybeLogStartupWait(reason: String, neededMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastStartupLogMs < 1_000L) return
        lastStartupLogMs = now
        Log.d(
            TAG,
            "Timing/start-wait reason=$reason mode=$correctionMode buf=${bufferDurationMs()}ms " +
                "need=${neededMs}ms bytes=${bufferedBytes()} codec=$activeCodec " +
                "fallbackLat=${outputLatencyFallbackUs / 1000}ms measuredLat=${measuredLatencyUs / 1000}ms " +
                "staticDelay=${routeAcousticExtraUs / 1000}ms syncDelay=${syncDelayMs}ms ${clockDebug()}"
        )
    }

    private fun maybeLogWriteTiming(
        serverTimestampUs: Long,
        plan: TimingPlan,
        beforeWriteUs: Long,
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
                timingSummary(plan, beforeWriteUs) +
                " err=${"%.2f".format(smoothedSyncErrorMs)}ms written=${written}B frames=$frames " +
                "frameDur=${frames * 1000L / activeSampleRate.coerceAtLeast(1)}ms " +
                "buf=${bufferDurationMs()}ms bytes=${bufferedBytes()} totalFrames=$totalFramesWritten " +
                "syncMuted=$syncMuted state=$syncState ${clockDebug()}"
        )
    }

    private fun timingSummary(plan: TimingPlan, nowUs: Long): String =
        "localOut=${plan.localOutputUs / 1000}ms targetWrite=${plan.targetWriteUs / 1000}ms " +
            "now=${nowUs / 1000}ms lead=${(plan.targetWriteUs - nowUs) / 1000}ms " +
            "outLat=${plan.outputLatencyUs / 1000}ms staticDelay=${plan.staticDelayUs / 1000}ms " +
            "syncDelay=${syncDelayMs}ms headroom=${plan.headroomUs / 1000}ms"

    protected fun clockDebug(): String {
        val sync = clockSynchronizer ?: return "clock=none"
        return "clockSamples=${sync.currentSampleCount()} clockErr=${sync.errorUs()}us " +
            "clockOffset=${sync.currentOffsetUs()}us synced=${sync.isSynced()}"
    }

    private fun flushQueuesAndDecoder() {
        frameQueue.clear()
        frameQueueBytes.set(0)
        decoderMarks.clear()
        lastEnqueuedTimestampUs = 0L
        estimatedFrameDurationUs = 20_000L
        onFlush()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
        // AudioTrack.flush() resets framePosition/playbackHeadPosition to 0, so
        // reset our written-frame counter to keep both on the same post-flush
        // epoch for the queued-frames latency measurement.
        totalFramesWritten = 0L
        synchronized(codecLock) {
            try { codec?.flush() } catch (_: Exception) {}
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
        onSyncStateChanged?.invoke(newState)
    }

    private fun releaseInternal() {
        playbackActive = false
        paused = false
        playbackStarted = false
        playbackGeneration++
        synchronized(playbackThreadLock) {
            playbackThread?.interrupt()
            playbackThread = null
        }
        flushQueuesAndDecoder()
        synchronized(codecLock) {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
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
