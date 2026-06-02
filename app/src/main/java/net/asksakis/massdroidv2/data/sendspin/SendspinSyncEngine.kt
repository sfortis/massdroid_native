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
 * Sendspin playback engine rebuilt around the protocol invariant:
 *
 *   binary timestamp == local output deadline for the first sample.
 *
 * The old engine used a continuous blocking write stream as the primary clock
 * and then tried to correct with anchors, DAC feedback and rate changes. This
 * implementation makes every decoded PCM chunk carry its input server timestamp,
 * computes an absolute write deadline, and keeps AudioTrack buffering shallow.
 */
class SendspinSyncEngine : SendspinAudioEngine {
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

        private const val SYNC_START_BUFFER_MS = 250L
        // Solo start cushion: enough to ride out a small network/decode hiccup
        // right after a seek/track start without making the seek feel laggy.
        private const val DIRECT_START_BUFFER_MS = 350L
        private const val SYNC_CLOCK_WAIT_MS = 3_000L
        private const val SYNC_CLOCK_ERROR_US = 15_000L
        private const val LATE_DROP_MS = 120L
        private const val START_TARGET_HEADROOM_US = 50_000L
        // Positive scheduling headroom added to every SYNC chunk's local
        // deadline, matching sendspin-js SCHEDULE_HEADROOM_SEC = 0.2. Every
        // Sendspin client (web UI, Cast receiver, demo) plays each sample at
        // serverTime + 200ms, so all group members stay phase-locked while
        // absorbing one-way network latency. Without it the first frames of a
        // fresh stream arrive already past their deadline and get drop-stormed.
        private const val SCHEDULE_HEADROOM_US = 200_000L
        // DIRECT-mode start lead: the first post-flush frame is anchored to
        // now + outputLatency + this, so solo playback begins ~this soon
        // instead of at the server's far-future absolute timestamp.
        private const val DIRECT_START_HEADROOM_US = 60_000L
        private const val SLEEP_SPIN_US = 500L
        private const val STARTUP_MUTE_MS = 350L
        private const val LATENCY_EMA_ALPHA = 0.05
        private const val SYNC_SAMPLE_CALLBACK_MS = 1_000L
    }

    private data class EncodedFrame(
        val serverTimestampUs: Long,
        val payload: ByteArray,
        val generation: Long,
    ) : Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    private data class DecoderMark(
        val serverTimestampUs: Long,
        val generation: Long,
    )

    private data class PcmChunk(
        val serverTimestampUs: Long,
        val pcm: ByteArray,
        val generation: Long,
    )

    private data class TimingPlan(
        val localOutputUs: Long,
        val staticDelayUs: Long,
        val outputLatencyUs: Long,
        val headroomUs: Long,
        val targetWriteUs: Long,
    )

    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private val decoderMarks = ArrayDeque<DecoderMark>()
    private val codecLock = Any()
    private val playbackThreadLock = Any()
    private val latencyTimestamp = AudioTimestamp()

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var configured = false
    @Volatile private var playbackActive = false
    @Volatile private var playbackStarted = false
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
    @Volatile private var activeCodec = "flac"
    @Volatile private var activeBitDepth = 16
    @Volatile private var activeSampleRate = 48_000
    @Volatile private var activeChannels = 2
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var lastFrameReceivedMs = 0L
    @Volatile private var startupWaitStartedMs = 0L
    @Volatile private var routedDeviceType = -1
    @Volatile private var routedProductName: String? = null
    @Volatile private var totalFramesWritten = 0L
    @Volatile private var outputLatencyFallbackUs = 0L
    @Volatile private var syncMuteStartedMs = 0L
    @Volatile private var receivedFrameCount = 0L
    @Volatile private var decodedChunkCount = 0L
    @Volatile private var writtenChunkCount = 0L
    @Volatile private var lastTimingLogMs = 0L
    @Volatile private var lastStartupLogMs = 0L
    @Volatile private var lastSyncSampleCallbackMs = 0L

    override var onSyncStateChanged: ((SyncState) -> Unit)? = null
    override var onSyncSample: ((errorMs: Float, outputLatencyMs: Float, filterErrorMs: Float, dacAbsoluteMs: Float?) -> Unit)? = null
    override var clockSynchronizer: ClockSynchronizer? = null
    override var syncDelayMs: Int = 0
    override var routeAcousticExtraUs: Long = 0L
    override val measuredOutputLatencyUs: Long
        get() = measuredLatencyUs
    override var correctionMode: CorrectionMode = CorrectionMode.SYNC
        private set
    override var syncState: SyncState = SyncState.IDLE
        private set

    // DIRECT-mode local timeline anchor (0 = unset, re-armed on every flush).
    @Volatile private var directAnchorServerUs = 0L
    @Volatile private var directAnchorLocalUs = 0L
    @Volatile private var measuredLatencyUs = 0L
    // Last converged latency for the current route. Survives a same-route codec
    // rebuild (which zeroes measuredLatencyUs) so a fresh SYNC start schedules
    // against the real ~27ms instead of the coarse minBuf fallback. Cleared on
    // an actual output-route change, where the latency genuinely differs.
    @Volatile private var lastStableLatencyUs = 0L
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

    override fun setCorrectionMode(mode: CorrectionMode) {
        if (correctionMode == mode) return
        val old = correctionMode
        correctionMode = mode
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        directAnchorServerUs = 0L
        directAnchorLocalUs = 0L
        if (mode == CorrectionMode.SYNC && playbackStarted) {
            Log.d(TAG, "CorrectionMode hard relock for SYNC boundary")
            // Bump the stream epoch like every other hard boundary so any
            // in-flight DIRECT-timeline frame/decoder output is rejected and
            // does not leak into the SYNC timeline (now scheduled with headroom).
            configureGeneration++
            flushQueuesAndDecoder()
            playbackStarted = false
            syncMuted = true
            syncMuteStartedMs = System.currentTimeMillis()
            audioTrack?.setVolume(0f)
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
        }
        Log.d(TAG, "CorrectionMode: $old -> $mode")
    }

    override fun setCellularTransport(cellular: Boolean) {
        // The timeline engine is codec/clock driven. Transport affects server format
        // policy, not local playout scheduling.
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
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        receivedFrameCount++
        val previousTail = lastEnqueuedTimestampUs
        if (previousTail > 0L) {
            val spacing = serverTimestampUs - previousTail
            if (spacing in 5_000L..500_000L) estimatedFrameDurationUs = spacing
        }
        frameQueue.offer(EncodedFrame(serverTimestampUs, payload, generation))
        frameQueueBytes.addAndGet(payload.size.toLong())
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
        syncMuted = correctionMode == CorrectionMode.SYNC
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
        transitionSyncState(if (bufMs > DIRECT_START_BUFFER_MS) SyncState.HOLDOVER_PLAYING_FROM_BUFFER else SyncState.SYNC_ERROR_REBUFFERING)
    }

    override fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route changed: $reason acoustic=${routeAcousticExtraUs / 1000}ms")
        measuredLatencyUs = 0L
        lastStableLatencyUs = 0L
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        if (playbackStarted) {
            syncMuted = correctionMode == CorrectionMode.SYNC
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
        if (syncState == SyncState.SYNCHRONIZED) smoothedSyncErrorMs.toFloat() else null

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

    @Volatile private var playbackThread: Thread? = null

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
            frameQueueBytes.addAndGet(-frame.payload.size.toLong())
            if (frame.generation != configureGeneration) continue

            if (activeCodec == "pcm") {
                // writeScheduled owns the 24->16 conversion; do not convert here
                // too or 24-bit PCM gets mangled by a double pass.
                writeScheduled(PcmChunk(frame.serverTimestampUs, frame.payload, frame.generation), track, generation)
            } else {
                queueCodecInput(frame)
                drainDecoder(track, generation)
            }
        }
    }

    private fun startupReady(): Boolean {
        val neededMs = if (correctionMode == CorrectionMode.SYNC) SYNC_START_BUFFER_MS else DIRECT_START_BUFFER_MS
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("buffer", neededMs)
            return false
        }
        if (correctionMode != CorrectionMode.SYNC) return true

        val sync = clockSynchronizer ?: return false
        if (sync.isReadyForPlaybackStart()) return trimStartupLateFrames(neededMs)
        val now = System.currentTimeMillis()
        if (startupWaitStartedMs == 0L) startupWaitStartedMs = now
        val timedOutReady = now - startupWaitStartedMs >= SYNC_CLOCK_WAIT_MS &&
            sync.isSynced() &&
            sync.errorUs() <= SYNC_CLOCK_ERROR_US
        if (!timedOutReady) maybeLogStartupWait("clock", neededMs)
        return timedOutReady && trimStartupLateFrames(neededMs)
    }

    private fun trimStartupLateFrames(neededMs: Long): Boolean {
        if (playbackStarted || correctionMode != CorrectionMode.SYNC) return true
        val minTargetWriteUs = nowUs() + START_TARGET_HEADROOM_US
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
            frameQueueBytes.addAndGet(-dropped.payload.size.toLong())
            droppedFrames++
            droppedBytes += dropped.payload.size.toLong()
        }
        if (droppedFrames > 0) {
            val now = nowUs()
            Log.d(
                TAG,
                "Timing/start-trim frames=$droppedFrames bytes=$droppedBytes " +
                    "nextLead=${nextPlan?.let { (it.targetWriteUs - now) / 1000 } ?: -1}ms " +
                    "headroom=${START_TARGET_HEADROOM_US / 1000}ms buf=${bufferDurationMs()}ms ${clockDebug()}"
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
            syncMuted = correctionMode == CorrectionMode.SYNC
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
            if (frame.payload.size > input.remaining()) {
                Log.w(TAG, "Oversized $activeCodec frame dropped: ${frame.payload.size}B")
                return
            }
            input.put(frame.payload)
            decoderMarks.addLast(DecoderMark(frame.serverTimestampUs, frame.generation))
            mc.queueInputBuffer(inputIndex, 0, frame.payload.size, frame.serverTimestampUs, 0)
            if (receivedFrameCount <= 8 || receivedFrameCount % 500 == 0L) {
                Log.d(
                    TAG,
                    "Timing/codec-in codec=$activeCodec serverTs=${frame.serverTimestampUs / 1000}ms " +
                        "payload=${frame.payload.size}B marks=${decoderMarks.size} gen=${frame.generation}"
                )
            }
        }
    }

    private fun drainDecoder(track: AudioTrack, generation: Long): Boolean {
        val mc = codec ?: return false
        var wroteAny = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            val chunk = synchronized(codecLock) {
                val outputIndex = mc.dequeueOutputBuffer(info, 0)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = ByteArray(info.size)
                            mc.getOutputBuffer(outputIndex)?.get(out)
                            mc.releaseOutputBuffer(outputIndex, false)
                            // Only consume a timestamp mark for a real output
                            // buffer. A zero-size buffer (e.g. Opus pre-skip
                            // priming) must NOT eat a mark or the FIFO desyncs
                            // and every later chunk is mislabelled by one frame.
                            val mark = decoderMarks.pollFirst()
                            if (mark != null) {
                                decodedChunkCount++
                                if (decodedChunkCount <= 8 || decodedChunkCount % 500 == 0L) {
                                    val frames = out.size / (activeChannels * 2).coerceAtLeast(1)
                                    Log.d(
                                        TAG,
                                        "Timing/decode-out chunk#$decodedChunkCount codec=$activeCodec " +
                                            "serverTs=${mark.serverTimestampUs / 1000}ms bytes=${out.size} frames=$frames " +
                                            "dur=${frames * 1000L / activeSampleRate.coerceAtLeast(1)}ms marks=${decoderMarks.size}"
                                    )
                                }
                                PcmChunk(mark.serverTimestampUs, out, mark.generation)
                            } else {
                                null
                            }
                        } else {
                            mc.releaseOutputBuffer(outputIndex, false)
                            null
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = mc.outputFormat
                        Log.d(TAG, "Decoder output: ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                        null
                    }
                    else -> null
                }
            } ?: break
            if (chunk.generation != configureGeneration || generation != playbackGeneration) continue
            writeScheduled(chunk, track, generation)
            wroteAny = true
        }
        return wroteAny
    }

    private fun writeScheduled(chunk: PcmChunk, track: AudioTrack, generation: Long) {
        if (chunk.pcm.isEmpty() || generation != playbackGeneration || paused) return
        val plan = timingPlan(chunk.serverTimestampUs)
        val targetWriteUs = plan.targetWriteUs

        // Both modes gate each chunk to its deadline so the AudioTrack FIFO
        // stays shallow (~output latency) -> seeks flush almost no stale audio
        // and respond instantly. The deadline differs by mode (see timingPlan):
        // SYNC uses the absolute group timeline; DIRECT uses a local anchor that
        // plays the first post-flush frame ~now (no multi-second far-future
        // sleep). Late-drop is SYNC-only (DIRECT has no peer to fall behind).
        // The bail checks configureGeneration so a track change/seek interrupts
        // a stale sleep immediately.
        var now = nowUs()
        if (correctionMode == CorrectionMode.SYNC) {
            val lateMs = (now - targetWriteUs) / 1000L
            if (lateMs > LATE_DROP_MS) {
                updateSyncError(lateMs.toDouble())
                Log.d(
                    TAG,
                    "Timing/drop late=${lateMs}ms serverTs=${chunk.serverTimestampUs / 1000}ms " +
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
            if (generation != playbackGeneration || paused || chunk.generation != configureGeneration) return
        }
        while (nowUs() < targetWriteUs && generation == playbackGeneration &&
            !paused && chunk.generation == configureGeneration
        ) {
            Thread.yield()
        }

        val beforeWriteUs = nowUs()
        val finalPcm = if (activeBitDepth == 24 && activeCodec == "pcm") convertPcm24To16(chunk.pcm) else chunk.pcm
        val written = try {
            track.write(finalPcm, 0, finalPcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack write failed: ${e.message}")
            transitionSyncState(SyncState.SYNC_ERROR_REBUFFERING)
            return
        }
        if (written <= 0) return

        val frames = written / (activeChannels * 2)
        totalFramesWritten += frames
        measureOutputLatency(track)
        if (correctionMode == CorrectionMode.SYNC) {
            updateSyncError((beforeWriteUs - targetWriteUs) / 1000.0)
        }
        writtenChunkCount++
        maybeLogWriteTiming(chunk, plan, beforeWriteUs, written, frames)

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
        // route (survives a same-route codec rebuild so a fresh SYNC start is
        // accurate immediately), and only fall back to the coarse minBuf
        // estimate on a genuine cold start / route change.
        val outputLatencyUs = measuredLatencyUs.takeIf { it > 0L }
            ?: lastStableLatencyUs.takeIf { it > 0L }
            ?: outputLatencyFallbackUs
        val localOutputUs: Long
        val headroomUs: Long
        if (correctionMode == CorrectionMode.SYNC) {
            // Grouped: schedule against the absolute group timeline + headroom
            // so every member plays each sample at serverTime + headroom.
            localOutputUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs) ?: nowUs()
            headroomUs = SCHEDULE_HEADROOM_US
        } else {
            // Solo: no peer to phase-lock to. Anchor the first post-flush frame
            // to ~now (+ a small start headroom) and keep the relative spacing,
            // so playback starts immediately instead of sleeping until the
            // server's far-future absolute timestamp (the next-track/seek hang).
            if (directAnchorServerUs == 0L) {
                directAnchorServerUs = serverTimestampUs
                directAnchorLocalUs = nowUs() + outputLatencyUs + DIRECT_START_HEADROOM_US
            }
            localOutputUs = directAnchorLocalUs + (serverTimestampUs - directAnchorServerUs)
            headroomUs = 0L
        }
        val targetWriteUs = localOutputUs +
            headroomUs -
            staticDelayUs +
            syncDelayMs.toLong() * 1000L -
            outputLatencyUs
        return TimingPlan(localOutputUs, staticDelayUs, outputLatencyUs, headroomUs, targetWriteUs)
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

    private fun maybeLogStartupWait(reason: String, neededMs: Long) {
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
        chunk: PcmChunk,
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
            "Timing/write chunk#$writtenChunkCount codec=$activeCodec serverTs=${chunk.serverTimestampUs / 1000}ms " +
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

    private fun clockDebug(): String {
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
        // Re-arm the DIRECT anchor so the next stream/seek anchors to "now".
        directAnchorServerUs = 0L
        directAnchorLocalUs = 0L
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

    private fun nowUs(): Long = System.nanoTime() / 1000L

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
