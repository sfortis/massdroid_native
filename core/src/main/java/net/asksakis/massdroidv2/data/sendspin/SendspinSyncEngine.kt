package net.asksakis.massdroidv2.data.sendspin

import android.content.Context

/**
 * Grouped (multi-device) Sendspin engine. Each sample is heard at its server
 * timestamp, translated by the clock filter, as the spec defines it. The native
 * callback aligns the DAC presentation to that instant, and the engine
 * subtracts the rest of the output path it knows about.
 *
 * Until 2026-10-02 the engine played 200 ms after the timestamp. The sync probe
 * showed why that had seemed necessary: the phone was really playing about
 * 95 ms early (an over-long getOutputLatency was subtracted), and the other
 * speakers in the user's groups sat 170 to 200 ms late because their own output
 * latency was not compensated. Those speakers need their static delay set, not
 * a headroom on this phone.
 * The heavy lifting lives in [SendspinPlaybackEngine]; this class contributes
 * the SYNC timing policy and the clock-convergence gate.
 */
class SendspinSyncEngine(context: Context) : SendspinPlaybackEngine(context) {
    companion object {
        private const val SYNC_START_BUFFER_MS = 250L
        private const val SYNC_CLOCK_WAIT_MS = 3_000L
        private const val SYNC_CLOCK_ERROR_US = 15_000L
        private const val START_TARGET_HEADROOM_US = 50_000L
    }

    override val correctionMode: CorrectionMode = CorrectionMode.SYNC
    override val startBufferMs: Long = SYNC_START_BUFFER_MS

    override fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan {
        // Play at the server timestamp (see the class doc); the native dac0
        // alignment cancels our measured HAL output latency.
        val localOutputUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs) ?: nowUs()
        return LocalPlan(localOutputUs, headroomUs = 0L)
    }

    override fun startupGate(neededMs: Long): Boolean {
        val sync = clockSynchronizer ?: return false
        if (sync.isReadyForPlaybackStart()) return trimStartupLateFrames(neededMs, START_TARGET_HEADROOM_US)
        val now = System.currentTimeMillis()
        if (startupWaitStartedMs == 0L) startupWaitStartedMs = now
        val timedOutReady = now - startupWaitStartedMs >= SYNC_CLOCK_WAIT_MS &&
            sync.isSynced() &&
            sync.errorUs() <= SYNC_CLOCK_ERROR_US
        if (!timedOutReady) maybeLogStartupWait("clock", neededMs)
        return timedOutReady && trimStartupLateFrames(neededMs, START_TARGET_HEADROOM_US)
    }
}
