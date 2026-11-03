package net.asksakis.massdroidv2.playback

import net.asksakis.massdroidv2.domain.model.PlaybackState

/**
 * When playback on this phone starts again without the listener pressing play: after a
 * Bluetooth device connects, and after an MA reconnect.
 */
internal object AutoResumePolicy {

    /**
     * The resume ticket a confirmed route loss leaves behind: the lost device, but only when the
     * listener still wanted playback when the loss was confirmed. A pause pressed during the
     * route-loss hold is a decision to stay paused, so it leaves no ticket. Such a pause either
     * cleared the intent ([intentAtDeadline]) or, when the transport was not ready, only moved
     * the listener's pause count on ([pausesAtHold] against [pausesAtDeadline]).
     */
    fun ticketAfterRouteLoss(
        lostSink: String?,
        intentAtDeadline: Boolean,
        pausesAtHold: Long,
        pausesAtDeadline: Long,
    ): String? = lostSink.takeIf { intentAtDeadline && pausesAtDeadline == pausesAtHold }

    /**
     * A Bluetooth device that connected resumes playback only when it is the device whose loss
     * paused it ([resumeTicket]). A device that cannot be identified never resumes anything.
     */
    fun bluetoothConnectResumes(resumeTicket: String?, connectedDevice: String?): Boolean =
        resumeTicket != null && resumeTicket == connectedDevice

    /**
     * How long after the Sendspin connection comes back a report other than PLAYING is still
     * taken for the stop MA made when it dropped. A second MA reconnect of the same network
     * change comes within seconds; a pause made a minute later is a real pause.
     */
    const val POST_DROP_HOLD_MS = 60_000L

    /**
     * Records a report of this phone's player from MA, made at [nowMs] on the elapsed clock.
     *
     * Only reports made while the Sendspin connection is up count ([transportUp]). When that
     * connection drops, MA stops and unregisters the player, so the IDLE it reports after the
     * player registers again was caused by the drop, not by anyone stopping the music. A
     * recorded PLAYING is therefore held against other reports for [POST_DROP_HOLD_MS] after the
     * connection comes back, unless a PLAYING report or a reconnect that read the record
     * ([consumedByReconnect]) ends the hold first, so a second MA reconnect during one Wi-Fi to
     * mobile handover still finds the resume it is owed.
     *
     * A report held back is kept, and it becomes the state once the window is over, whether or
     * not another report arrives ([stateAt]). The players list can stay quiet for hours, so an
     * MA reconnect at night reads the pause, not the old PLAYING.
     *
     * Only a recorded PLAYING is held, since nothing else can start a resume. That also keeps
     * the first reports after start(), which begins with the connection down, from being
     * ignored for nothing.
     *
     * [reported] is null when the player is missing from the list or unavailable.
     */
    fun recordServerReport(
        record: ServerPlaybackRecord,
        transportUp: Boolean,
        reported: PlaybackState?,
        nowMs: Long,
    ): ServerPlaybackRecord {
        val settled = settledAt(record, nowMs)
        val holding = settled.state == PlaybackState.PLAYING
        if (!transportUp) {
            // A new drop starts a new hold; what the last return reported belongs to that one.
            return settled.copy(transportUp = false, holdStartedMs = if (holding) nowMs else null, heldBack = null)
        }
        // The window starts again when the connection comes back, however long it was down.
        val holdStart = if (!settled.transportUp && holding) nowMs else settled.holdStartedMs
        val held = holdStart != null && nowMs - holdStart < POST_DROP_HOLD_MS
        val next = settled.copy(transportUp = true, holdStartedMs = holdStart.takeIf { held })
        return when {
            reported == null -> next
            held && reported != PlaybackState.PLAYING -> next.copy(heldBack = reported)
            else -> next.copy(state = reported, holdStartedMs = null, heldBack = null)
        }
    }

    /** The state that counts at [nowMs]: the report held back takes over once the window is over. */
    fun stateAt(record: ServerPlaybackRecord, nowMs: Long): PlaybackState? = settledAt(record, nowMs).state

    /** A reconnect read the record at [nowMs], so later reports count again whatever they say. */
    fun consumedByReconnect(record: ServerPlaybackRecord, nowMs: Long): ServerPlaybackRecord =
        settledAt(record, nowMs).copy(holdStartedMs = null, heldBack = null)

    // While the connection is down the window has not started, so nothing expires.
    private fun settledAt(record: ServerPlaybackRecord, nowMs: Long): ServerPlaybackRecord {
        val start = record.holdStartedMs ?: return record
        if (!record.transportUp || nowMs - start < POST_DROP_HOLD_MS) return record
        return record.copy(state = record.heldBack ?: record.state, holdStartedMs = null, heldBack = null)
    }

    /**
     * An MA reconnect resumes playback only when the network cut it: the listener here still
     * wanted playback on this phone, and the server was playing when it last reported while the
     * Sendspin connection was up. Either alone is not enough. A pause made elsewhere leaves the
     * local intent set, and a pause sent here as the network went down may never reach the server.
     */
    fun reconnectResumes(listenerWantsPlayback: Boolean, phoneSelected: Boolean, lastServerState: PlaybackState?): Boolean =
        listenerWantsPlayback && phoneSelected && lastServerState == PlaybackState.PLAYING
}

/**
 * The playback state MA last reported for this phone's player that counts for the resume after
 * an MA reconnect, see [AutoResumePolicy.recordServerReport].
 *
 * @property transportUp whether the Sendspin connection was up at the last update, so the
 *   update that sees it come back can start the hold window.
 * @property holdStartedMs when the hold of a recorded PLAYING began, on the elapsed clock, or
 *   null when no hold is running and every report counts.
 * @property heldBack the last report the hold kept back, which becomes [state] once the window
 *   is over.
 */
internal data class ServerPlaybackRecord(
    val state: PlaybackState? = null,
    val transportUp: Boolean = false,
    val holdStartedMs: Long? = null,
    val heldBack: PlaybackState? = null,
)
