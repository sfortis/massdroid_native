package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Routes server-pushed volume and mute events for the local Sendspin player to
 * the phone's STREAM_MUSIC. This keeps all local playback gain on a single
 * system-managed stage, so hardware keys, UI sliders, and group fan-outs all
 * converge on the same perceived volume without software-vs-system conflicts.
 *
 * Echo suppression: when we mirror a hardware-key change to MA, MA broadcasts
 * the value back. We detect echoes by comparing to the last value we pushed
 * ourselves and a short post-push cooldown window.
 */
class LocalSpeakerVolumeBridge(
    private val audioManager: AudioManager,
    private val volumeEvents: SharedFlow<Int>,
    private val muteEvents: SharedFlow<Boolean>
) {
    companion object {
        private const val TAG = "LocalVolBridge"
        private const val ECHO_WINDOW_MS = 1_500L
    }

    private var jobs: MutableList<Job> = mutableListOf()

    @Volatile private var lastLocallyPushedPct: Int = -1
    @Volatile private var lastLocalPushAtMs: Long = 0L
    // First event after start() is a baseline snapshot (MA broadcasting the
    // stored value for our player). Applying it would yank STREAM_MUSIC to
    // whatever MA has cached — which at app startup typically isn't what the
    // user expects. Use it to seed [lastLocallyPushedPct] so later genuine
    // changes apply normally.
    @Volatile private var awaitingBaseline: Boolean = true

    /** Start observing. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        awaitingBaseline = true
        jobs += scope.launch {
            volumeEvents.collect { pct ->
                applyServerVolume(pct)
            }
        }
        jobs += scope.launch {
            muteEvents.collect { muted ->
                applyServerMute(muted)
            }
        }
        Log.d(TAG, "Bridge started")
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        Log.d(TAG, "Bridge stopped")
    }

    /**
     * Call when the user drives volume locally (hw keys, slider) so we can
     * suppress the server echo that will arrive shortly after.
     */
    fun recordLocalPush(pct: Int) {
        lastLocallyPushedPct = pct.coerceIn(0, 100)
        lastLocalPushAtMs = System.currentTimeMillis()
    }

    private fun applyServerVolume(pct: Int) {
        val bounded = pct.coerceIn(0, 100)
        if (awaitingBaseline) {
            awaitingBaseline = false
            lastLocallyPushedPct = bounded
            lastLocalPushAtMs = System.currentTimeMillis()
            Log.d(TAG, "applyServerVolume: baseline $bounded% (no STREAM_MUSIC change)")
            return
        }
        // Echo from our own hw-key mirror: skip to avoid bouncing STREAM_MUSIC.
        if (bounded == lastLocallyPushedPct &&
            System.currentTimeMillis() - lastLocalPushAtMs < ECHO_WINDOW_MS
        ) {
            Log.d(TAG, "applyServerVolume: ignoring echo at $bounded%")
            return
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val targetIndex = (bounded * max + 50) / 100 // round to nearest
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current == targetIndex) return
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetIndex,
            0 // no system UI overlay for programmatic changes
        )
        Log.d(TAG, "applyServerVolume: $bounded% -> STREAM_MUSIC index $targetIndex")
    }

    private fun applyServerMute(muted: Boolean) {
        // Use adjustStreamVolume for a broadly compatible mute toggle across
        // API levels. setStreamMute is deprecated on recent APIs.
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        Log.d(TAG, "applyServerMute: $muted")
    }
}
