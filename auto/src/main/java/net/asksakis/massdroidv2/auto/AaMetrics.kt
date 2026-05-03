package net.asksakis.massdroidv2.auto

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AA churn telemetry. Counts MediaSession state-update flow events so we can measure baseline
 * churn before applying behavior fixes (Phase 0 of aa-refactor-plan.md).
 *
 * Flushes a rolling 10s window to logcat under tag "AaMetrics". Quiet windows (no events) are
 * skipped so the log stays readable when the app is idle.
 *
 * Phase 0 captures: how many `updateState` and `getState` calls fire per second, and whether the
 * playlist size or current index changed. Later phases use these numbers as regression baselines.
 */
object AaMetrics {

    private const val TAG = "AaMetrics"
    private const val FLUSH_INTERVAL_MS = 10_000L

    private val updateStateCount = AtomicLong(0)
    private val invalidateCount = AtomicLong(0)
    private val playlistRebuildCount = AtomicLong(0)
    private val getStateCount = AtomicLong(0)

    @Volatile private var lastIndex: Int = -1
    @Volatile private var lastPlaylistSize: Int = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var flushJob: Job? = null

    fun start() {
        if (flushJob != null) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }

    fun onUpdateState() {
        updateStateCount.incrementAndGet()
    }

    fun onInvalidate() {
        invalidateCount.incrementAndGet()
    }

    fun onPlaylistRebuild() {
        playlistRebuildCount.incrementAndGet()
    }

    fun onGetState(currentIndex: Int, playlistSize: Int) {
        getStateCount.incrementAndGet()
        if (currentIndex != lastIndex || playlistSize != lastPlaylistSize) {
            Log.d(
                TAG,
                "index=$currentIndex size=$playlistSize (was=$lastIndex/$lastPlaylistSize)"
            )
            lastIndex = currentIndex
            lastPlaylistSize = playlistSize
        }
    }

    /**
     * Per-call trace logs. Cheap when disabled because Log.isLoggable() short-circuits the string
     * concatenation. Enable at runtime with `adb shell setprop log.tag.AaMetrics DEBUG`.
     */
    private fun traceEnabled() = Log.isLoggable(TAG, Log.DEBUG)

    /** Trace: full updateState payload, fired by PlaybackService.observePlayerState. */
    fun traceUpdateState(positionMs: Long, isPlaying: Boolean, title: String, queueId: String?) {
        if (!traceEnabled()) return
        Log.d(TAG, "trace updateState pos=$positionMs play=$isPlaying q=$queueId t=\"$title\"")
    }

    /** Trace: what RemoteControlPlayer.getState actually returns. */
    fun traceGetState(currentIndex: Int, playlistSize: Int, contentPositionMs: Long, isPlaying: Boolean) {
        if (!traceEnabled()) return
        Log.d(TAG, "trace getState idx=$currentIndex size=$playlistSize pos=$contentPositionMs play=$isPlaying")
    }

    /** Trace: full queue rebuild from MA queue items. */
    fun traceUpdateQueue(queueId: String?, size: Int) {
        if (!traceEnabled()) return
        Log.d(TAG, "trace updateQueue q=$queueId size=$size")
    }

    private fun flush() {
        val us = updateStateCount.getAndSet(0)
        val inv = invalidateCount.getAndSet(0)
        val rb = playlistRebuildCount.getAndSet(0)
        val gs = getStateCount.getAndSet(0)
        if (us == 0L && inv == 0L && gs == 0L) return
        val seconds = FLUSH_INTERVAL_MS / 1000f
        Log.d(
            TAG,
            "10s: updateState=$us (${"%.2f".format(us / seconds)}/s) " +
                "invalidate=$inv playlistRebuild=$rb " +
                "getState=$gs (${"%.2f".format(gs / seconds)}/s)"
        )
    }
}
