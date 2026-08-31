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
     * A change of output route costs solo playback nothing.
     *
     * Nothing in flight is invalidated by it. The native callback runs with
     * `driftCorrection` off in this mode, so it is a plain FIFO that never schedules a
     * sample against the output latency, and the local anchor is already fixed: the
     * decoded audio in the ring and the encoded audio behind it are equally good on the
     * new route as on the old one. So this drops the relock the grouped engine needs, and
     * with it the mute, the re-arm and the restart.
     *
     * Measured in the car on 2026-08-28, before any of this: the old full flush threw away
     * a 26 second buffer at a route change and the MA server never rebuilt it, because it
     * drains its own model of what we hold at exactly realtime and never re-bursts. A
     * third of that drive then played on 0.7 s of runway. Keeping only the encoded queue
     * fixed most of it but still dropped up to 2.5 s of decoded audio, which the ring
     * reset discards outright (`resetRing` moves the read index to the write index).
     * Keeping everything is the honest end of that line.
     *
     * The stream is still reopened when the bound device disappears, and that path resets
     * the ring on its own; this is only about not doing it when nothing asked.
     */
    override fun onRouteChangeBoundary() {
        // Deliberately empty. See above.
    }
}
