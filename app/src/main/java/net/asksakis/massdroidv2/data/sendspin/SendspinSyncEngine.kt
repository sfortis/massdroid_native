package net.asksakis.massdroidv2.data.sendspin

import android.content.Context

/**
 * Grouped (multi-device) Sendspin engine. Every official client plays each
 * sample ACOUSTICALLY at `serverToLocal(serverTs) + SCHEDULE_HEADROOM` — see
 * sendspin-js scheduler.ts: `source.start(t + deltaSec + SCHEDULE_HEADROOM_SEC
 * - outputLatency)`, so after the output latency the sound emerges at
 * `serverTime + headroom`. The 200 ms headroom IS part of the agreed playout
 * phase (it gives every client time to schedule), NOT mere slack — so to share
 * the group's instant we must use the same 200 ms. The native callback aligns
 * the real DAC presentation time to this, which cancels our measured HAL
 * latency exactly as the JS client subtracts outputLatency. Any residual offset
 * vs an official reference is unmeasured device output latency (acoustic
 * calibration / `routeAcousticExtraUs`), not the headroom. The heavy lifting
 * lives in [SendspinPlaybackEngine]; this class contributes the SYNC timing
 * policy and the clock-convergence gate.
 */
class SendspinSyncEngine(context: Context) : SendspinPlaybackEngine(context) {
    companion object {
        private const val SYNC_START_BUFFER_MS = 250L
        private const val SYNC_CLOCK_WAIT_MS = 3_000L
        private const val SYNC_CLOCK_ERROR_US = 15_000L
        private const val START_TARGET_HEADROOM_US = 50_000L
        // Matches sendspin-js SCHEDULE_HEADROOM_SEC = 0.2: the wall-clock phase
        // at which every official client acoustically plays each sample
        // (serverTime + 200 ms). Required to share the group's playout instant.
        private const val SCHEDULE_HEADROOM_US = 200_000L
    }

    override val correctionMode: CorrectionMode = CorrectionMode.SYNC
    override val startBufferMs: Long = SYNC_START_BUFFER_MS

    override fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan {
        // Play at serverTs + 200 ms headroom (the official client phase); the
        // native dac0 alignment cancels our measured HAL output latency.
        val localOutputUs = clockSynchronizer?.serverToLocalUs(serverTimestampUs) ?: nowUs()
        return LocalPlan(localOutputUs, SCHEDULE_HEADROOM_US)
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
