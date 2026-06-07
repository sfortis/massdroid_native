package net.asksakis.massdroidv2.data.sendspin

import android.content.Context

/**
 * Solo (single-device) Sendspin engine. There is no peer to phase-lock to, so
 * absolute server-time scheduling buys nothing and only adds latency (and the
 * far-future-timestamp startup hang). Instead this anchors the first
 * post-flush frame to ~now and keeps the relative chunk spacing, so playback
 * starts immediately and seeks respond instantly. No clock dependency, no
 * headroom, no late-drop, no sync-error machinery — those all live only in
 * [SendspinSyncEngine]. The shared decode/output machinery is in
 * [SendspinPlaybackEngine].
 */
class SendspinDirectEngine(context: Context) : SendspinPlaybackEngine(context) {
    companion object {
        // Solo start cushion: enough to ride out a small network/decode hiccup
        // right after a seek/track start without making the seek feel laggy.
        private const val DIRECT_START_BUFFER_MS = 350L
        // Start lead: the first post-flush frame is anchored to
        // now + outputLatency + this, so solo playback begins ~this soon.
        private const val DIRECT_START_HEADROOM_US = 60_000L
    }

    override val correctionMode: CorrectionMode = CorrectionMode.DIRECT
    override val startBufferMs: Long = DIRECT_START_BUFFER_MS

    // Local timeline anchor (0 = unset, re-armed on every flush via onFlush()).
    @Volatile private var anchorServerUs = 0L
    @Volatile private var anchorLocalUs = 0L

    override fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan {
        if (anchorServerUs == 0L) {
            anchorServerUs = serverTimestampUs
            anchorLocalUs = nowUs() + outputLatencyUs + DIRECT_START_HEADROOM_US
        }
        val localOutputUs = anchorLocalUs + (serverTimestampUs - anchorServerUs)
        return LocalPlan(localOutputUs, 0L)
    }

    override fun onFlush() {
        // Re-arm so the next stream/seek anchors to "now".
        anchorServerUs = 0L
        anchorLocalUs = 0L
    }
}
