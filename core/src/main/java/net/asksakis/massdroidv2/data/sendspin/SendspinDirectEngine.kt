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

    /**
     * Keep the encoded audio across an output-route change and re-anchor to it.
     *
     * Only the decoded PCM is tied to the route, through the output latency it
     * was scheduled against, so only that has to go. The encoded frames are the
     * same audio on any route, and throwing them away costs the whole buffer for
     * the rest of the stream: the MA server drains its own model of what we hold
     * at exactly realtime and never learns that we discarded it, so it goes on
     * feeding just-in-time instead of sending a fresh burst.
     *
     * Measured in the car on 2026-08-28. Two collapses in one drive, both at a
     * route change during the Bluetooth connect: a full 26 s buffer discarded
     * (once only 1.3 s after it arrived), then about 0.7 s of runway for the
     * following seven minutes, with underruns where there had been none. A third
     * of that drive played on a buffer too thin to absorb a cellular dip.
     */
    override fun flushForRouteChange() {
        flushDecodedOutput()
    }
}
