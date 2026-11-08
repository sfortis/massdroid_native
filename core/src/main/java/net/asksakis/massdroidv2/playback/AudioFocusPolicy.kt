package net.asksakis.massdroidv2.playback

import net.asksakis.massdroidv2.domain.model.PlaybackState

/**
 * The decisions behind starting the phone's own audio output, separated from the
 * Android calls that carry them out.
 *
 * This exists because three shipped bugs all lived in the same small table and
 * none of them could be tested. The controller that owns audio focus builds a
 * real [android.media.AudioFocusRequest] and keeps its listener inside it, so a
 * unit test can neither construct the controller nor deliver an event to it, and
 * this module is deliberately pure-JVM with no Robolectric. Moving the answers
 * here, and leaving the AudioManager and engine calls with the controller, makes
 * the table checkable without changing what the app does.
 *
 * It covers only what those bugs touched: what a starting stream may do, and
 * whether a returning focus gain should resume. Everything else about focus,
 * ducking, freezing for a call and permanent loss, still lives in the controller.
 */
object AudioFocusPolicy {

    /** What the output should do when the server starts a stream. */
    enum class StreamStart {
        /** Focus is ours: release the output, which configure() left paused. */
        PLAY,

        /** The grant is queued: stay paused, and start when the gain arrives. */
        WAIT_FOR_GAIN,

        /** Refused: stay paused, and stop claiming the listener wants to play. */
        STAY_SILENT,

        /**
         * A pause made here has not reached the server yet, so this is the playback
         * that pause stopped: stay paused, and leave focus and intent as they are.
         */
        HOLD_FOR_SERVER_PAUSE,

        /**
         * The phone rejoined while the MA connection is still down and the listener here
         * has paused. Whatever the server streams was not asked for here, and only MA can
         * say whether it is a pause the server missed or a play from elsewhere: stay
         * paused until MA is back. The reconnect refreshes Sendspin, and the stream/start
         * that follows is decided again.
         */
        HOLD_UNTIL_MA_RETURNS
    }

    /**
     * Why a start is owed to the next audio focus gain.
     *
     * A boolean could not tell the reasons apart, so the log described a play the
     * listener had just asked for as a resume after an interruption. It matters
     * beyond wording: the field check for phantom starts looks for a resume
     * delivered while the intent is still false, and a delayed grant answered within
     * a few tens of milliseconds lands in exactly that state legitimately. On
     * 2026-09-17 at 23:05 that cost a false positive.
     */
    enum class OwedResume {
        /** An interruption paused us, and we promised to resume when it ends. */
        INTERRUPTION,

        /**
         * An interruption paused us, and while it lasted the output stayed off the
         * external sink for the whole route-loss settle window. The resume still
         * stands, but only onto an external sink, see [onFocusGain].
         */
        INTERRUPTION_SINK_LOST,

        /**
         * A start was requested and the focus grant came back delayed. Either the
         * listener pressed play, or the server opened a stream for this player,
         * which is a start someone asked for elsewhere rather than one we owe an
         * interruption.
         */
        DEFERRED_PLAY,
    }

    /** What should happen when audio focus comes back. */
    enum class FocusGain {
        /** Start the playback an interruption or a delayed grant left owing. */
        RESUME,

        /** Nothing is owed, or it is owed somewhere this phone no longer plays. */
        IGNORE,

        /**
         * The resume is owed to an external sink, the output still reads the phone
         * speaker, and an external sink is connected: wait a short time for the
         * output to move back to it, see [SinkReturnWindow].
         */
        AWAIT_SINK_RETURN
    }

    /**
     * Decide what a starting stream may do.
     *
     * [requestFocus] is only called when focus is not already held, so an ordinary
     * track change, which is a stream end followed by a stream start, does not ask
     * the platform again.
     *
     * [pauseOwedToServer] comes first, ahead of the focus we may still hold. A pause
     * made while the connection was down never reached the server, which keeps the
     * queue playing and starts the stream again when the phone rejoins. Playing it
     * would undo the pause: after a call the music would come back from a later
     * place, and after another app took over it would play on top of that app.
     */
    fun onStreamStart(
        holdsFocus: Boolean,
        pauseOwedToServer: Boolean = false,
        listenerPausedWithMaDown: Boolean = false,
        requestFocus: () -> FocusRequestResult
    ): StreamStart {
        if (pauseOwedToServer) return StreamStart.HOLD_FOR_SERVER_PAUSE
        if (listenerPausedWithMaDown) return StreamStart.HOLD_UNTIL_MA_RETURNS
        if (holdsFocus) return StreamStart.PLAY
        return when (requestFocus()) {
            FocusRequestResult.GRANTED -> StreamStart.PLAY
            FocusRequestResult.DELAYED -> StreamStart.WAIT_FOR_GAIN
            FocusRequestResult.FAILED -> StreamStart.STAY_SILENT
        }
    }

    /**
     * Whether another app's sound is audible right now, given what the platform
     * will tell us about the players it has active.
     *
     * The usage list alone was blind to an app playing plain media, and it cannot
     * simply be widened to include media, because our own output is media too. 122
     * ducks measured on a phone settle how: our own player is present in every
     * single one, reported as media usage with an UNKNOWN content type, which is
     * exactly what TikTok reports for itself. So the content type separates
     * nothing either.
     *
     * Arithmetic does. Our output is exactly one media entry, never two across
     * those 122 samples, so a second media entry belongs to somebody else. Of the
     * 22 samples where the usage list saw nothing, 9 were precisely that shape,
     * our player plus one more.
     *
     * [interrupterUsagePresent] is whether any active player carries a usage that
     * means another app is deliberately talking over us. [mediaPlayerCount] is how
     * many active players carry the plain media usage, ours included.
     */
    fun interrupterAudible(interrupterUsagePresent: Boolean, mediaPlayerCount: Int): Boolean =
        interrupterUsagePresent || mediaPlayerCount > 1

    /**
     * Whether a stream-generation reading describes a live stream that still needs
     * focus settled, or something that only looks like one.
     *
     * The counter is a StateFlow, so a collector that subscribes gets the latest
     * value replayed at once, and the manager does not reset the counter when it
     * stops: only the active flag goes down. Acting on that replay treated a
     * stream that had ended long ago as a fresh start, which asked for focus with
     * no playback behind it and could interrupt whatever else was playing. So the
     * reading has to be both new and backed by a stream that is running right now.
     *
     * [handled] is the last reading already acted on, or null when this is the
     * value replayed at subscription. Null is not the same as "skip": a controller
     * can subscribe while a stream is genuinely live, and that stream does need
     * focus, which is why a blind drop of the first value would be wrong.
     */
    fun streamNeedsFocus(generation: Long, handled: Long?, streamActive: Boolean): Boolean {
        if (generation == 0L) return false
        if (!streamActive) return false
        return handled == null || generation != handled
    }

    /**
     * Whether a server report of PLAYING should be adopted as the listener's wish
     * to play.
     *
     * Local play and pause write the intent before the server has answered, so a
     * PLAYING report can arrive after a pause that came later than the play it
     * describes. Adopting it there put the intent back to true behind the pause,
     * and the PAUSED report that followed did not take it down again, so a later
     * reconnect could resume music the listener had stopped. Latency alone
     * produces this; nothing has to arrive out of order.
     *
     * [pauseAwaitingConfirmation] is what tells the two apart. While a pause we
     * sent has not been confirmed, a PLAYING report is older than that pause and
     * is ignored. Once confirmed, a PLAYING report is genuinely later and is
     * adopted, which is what keeps remote play working.
     */
    fun adoptRemotePlaying(localIntent: Boolean, pauseAwaitingConfirmation: Boolean): Boolean =
        !localIntent && !pauseAwaitingConfirmation

    /**
     * Decide whether a focus gain should start the playback an interruption owed.
     *
     * There is deliberately no second condition here any more, and the history is
     * the argument. Three were tried and each was wrong in the field. Whether this
     * phone was the SELECTED player lost 16 of 41 resumes, because the selection
     * momentarily reads as nothing during a reconnect. Whether the transport was
     * connected resumed a phone the music had moved away from, because the client
     * stays connected as an available player. Whether the STREAM was up lost every
     * single resume, because pausing is what ends the stream: the server sent
     * stream/end 0.8 s after our own pause, so the condition was already false at
     * every gain that followed one, and it swallowed deferred plays with it.
     *
     * The owed resume is the answer. It is set only when this controller paused for
     * focus or deferred a play, and every reason not to resume clears it where
     * that reason happens: a pause the listener asked for, a permanent loss, a
     * local play, teardown, and the listener choosing another player. Asking a
     * second question at the moment of the gain means asking about a world that
     * has already changed, which is what went wrong three times.
     *
     * The one exception is [OwedResume.INTERRUPTION_SINK_LOST]. The sink went away
     * while the interruption lasted, so the [route] at the gain decides: an external
     * route resumes, and the phone speaker never does, so music does not start in
     * the car park from a phone that left the car. The speaker reading is not
     * trusted on its own while an external sink is still connected
     * ([externalSinkConnected]), because the output is still bound to the device it
     * last played on until a reopen settles, a few hundred milliseconds after the
     * car route returns. That case waits for the sink to come back.
     */
    fun onFocusGain(owed: OwedResume?, route: OutputRoute, externalSinkConnected: Boolean): FocusGain =
        when (owed) {
            null -> FocusGain.IGNORE
            OwedResume.INTERRUPTION, OwedResume.DEFERRED_PLAY -> FocusGain.RESUME
            OwedResume.INTERRUPTION_SINK_LOST -> when {
                route.isExternal -> FocusGain.RESUME
                externalSinkConnected -> FocusGain.AWAIT_SINK_RETURN
                else -> FocusGain.IGNORE
            }
        }

    /** What a confirmed loss of the external sink does. */
    enum class SinkLoss {
        /** Pause, and drop every start owed: the listener lost the speaker they chose. */
        PAUSE,

        /** An interruption already paused playback: keep its resume, judged at the gain. */
        KEEP_INTERRUPTION_RESUME
    }

    /**
     * Decide what the route-loss settle window does when the external sink is still
     * gone at its deadline.
     *
     * An interruption on Android Auto can move the output off the car for as long as
     * it lasts, and a message read aloud outlasts the settle window (issue #68). The
     * pause that followed used to drop the resume the interruption owed, so the focus
     * gain found nothing to do and the music stayed stopped. Playback is already
     * paused for the interruption, so the deadline has nothing to stop: it marks the
     * resume, and the gain checks the route. Without an interruption, or with a
     * deferred play, the loss pauses and drops what was owed, as it always did.
     */
    fun onSinkLossConfirmed(owed: OwedResume?): SinkLoss = when (owed) {
        OwedResume.INTERRUPTION, OwedResume.INTERRUPTION_SINK_LOST -> SinkLoss.KEEP_INTERRUPTION_RESUME
        OwedResume.DEFERRED_PLAY, null -> SinkLoss.PAUSE
    }

    /**
     * Whether the listener choosing [newSelectedPlayerId] cancels a resume owed to
     * the local player [localPlayerId].
     *
     * This is the one case the old conditions existed for: moving to another
     * speaker mid-interruption, which sends no pause to the player being left, so
     * nothing else would clear the owed resume and the gain would start a second
     * speaker. It is answered here, when the choice is made, rather than guessed
     * at afterwards.
     *
     * A null selection is NOT a cancellation. It means the player list has not
     * arrived, which happens on every reconnect, and treating it as a decision is
     * exactly how the selected-player condition lost 16 resumes.
     */
    /** What a transient (non-duck) focus loss should do. */
    enum class TransientLoss {
        /** We were playing: pause, and owe a resume to the gain that follows. */
        PAUSE_AND_OWE,

        /** Nothing was playing: nothing to pause, and nothing may be owed. */
        NOTHING_PLAYING
    }

    /**
     * Decide what a transient focus loss does.
     *
     * We keep audio focus while paused, so another app's transient request reaches
     * us whether we are playing or not. The loss handler used to owe a resume
     * unconditionally, and while the gain still asked a second question that debt
     * was masked. Once the owed flag became the whole gate (the fix for the lost
     * resumes), every TikTok clip, keyboard click or notification that took focus
     * while the phone was PAUSED created a phantom debt, and the gain that followed
     * started music the listener had stopped: five self-starts in sixteen minutes on
     * 2026-09-12. The listener's intent at the moment of the loss is the only thing
     * that says whether there is anything to pause, so it is asked here, before the
     * pause clears it.
     *
     * The intent alone is not enough, because it can outlive the playback it
     * describes. A pause sent to the sync group the phone plays in goes to the
     * group's id, not to the local player, so the intent stayed set; six minutes
     * later a keyboard click took transient focus and the gain restarted the whole
     * group (2026-10-01). So the local stream has to be live as well: with no
     * stream there is nothing audible to pause and nothing to owe.
     */
    fun onTransientLoss(listenerWantsPlayback: Boolean, localStreamActive: Boolean): TransientLoss =
        if (listenerWantsPlayback && localStreamActive) {
            TransientLoss.PAUSE_AND_OWE
        } else {
            TransientLoss.NOTHING_PLAYING
        }

    /** How a transient (non-duck) loss holds playback that is really playing. */
    enum class InterruptionHold {
        /** Pause the MA player; the server resumes from the same position. */
        PAUSE,

        /** Freeze the phone alone, buffer kept, so the group plays on through a call. */
        FREEZE,

        /** Freeze at once, then pause unless the call mode appears within the settle window. */
        FREEZE_AND_CHECK_FOR_CALL
    }

    /**
     * Decide how a transient loss holds playback that is really playing.
     *
     * A solo phone always pauses, a call included. A freeze would leave the MA player
     * running on the server for the whole call, and the phone would come back to
     * stale audio while the server was minutes ahead; a pause resumes from the same
     * position.
     *
     * A grouped phone freezes for a call, because a pause goes to the group and
     * stops every speaker in it. Telecom requests the focus before it sets the call
     * mode, so the mode usually still reads normal here (2026-10-21, every call on
     * the S25). Then the freeze silences the output and the caller checks the mode
     * again a moment later. Before this, the freeze branch was effectively never
     * taken and a call paused the whole group.
     */
    fun interruptionHold(grouped: Boolean, callModeSet: Boolean): InterruptionHold = when {
        !grouped -> InterruptionHold.PAUSE
        callModeSet -> InterruptionHold.FREEZE
        else -> InterruptionHold.FREEZE_AND_CHECK_FOR_CALL
    }

    fun selectionCancelsOwedResume(newSelectedPlayerId: String?, localPlayerId: String?): Boolean =
        newSelectedPlayerId != null && localPlayerId != null && newSelectedPlayerId != localPlayerId

    /**
     * Whether a pause made here has to be sent again once the MA connection is back.
     *
     * The phone plays on from its buffer through a dropped connection, so a call, another
     * app or the listener can pause it while neither socket reaches the server. The MA
     * command then fails, or is written into a socket that is already dead and dropped
     * with it, and the server keeps the queue playing. A pause made while the MA
     * connection is down, or while the Sendspin stream is only held through a drop, is
     * therefore owed until the server confirms it.
     *
     * [serverState] is what MA last reported for this player. A server that reports it
     * as not playing has nothing left to pause. No report at all (null) counts as
     * playing, because the player list is emptied while the MA connection is down,
     * which is exactly when the pause is most likely to be lost.
     */
    fun pauseOwedToServer(
        maConnected: Boolean,
        streamHeldThroughDrop: Boolean,
        serverState: PlaybackState?,
    ): Boolean =
        (serverState == null || serverState == PlaybackState.PLAYING) &&
            (!maConnected || streamHeldThroughDrop)
}

/** The three answers the platform gives to a focus request. */
enum class FocusRequestResult { GRANTED, DELAYED, FAILED }
