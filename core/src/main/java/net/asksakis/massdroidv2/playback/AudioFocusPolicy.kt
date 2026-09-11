package net.asksakis.massdroidv2.playback

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
        STAY_SILENT
    }

    /** What should happen when audio focus comes back. */
    enum class FocusGain {
        /** Start the playback an interruption or a delayed grant left owing. */
        RESUME,

        /** Nothing is owed, or it is owed somewhere this phone no longer plays. */
        IGNORE
    }

    /**
     * Decide what a starting stream may do.
     *
     * [requestFocus] is only called when focus is not already held, so an ordinary
     * track change, which is a stream end followed by a stream start, does not ask
     * the platform again.
     */
    fun onStreamStart(
        holdsFocus: Boolean,
        requestFocus: () -> FocusRequestResult
    ): StreamStart {
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
     * The flag is the answer. It is set only when this controller paused for
     * focus or deferred a play, and every reason not to resume clears it where
     * that reason happens: a pause the listener asked for, a permanent loss, a
     * local play, teardown, and the listener choosing another player. Asking a
     * second question at the moment of the gain means asking about a world that
     * has already changed, which is what went wrong three times.
     */
    fun onFocusGain(resumeOwed: Boolean): FocusGain =
        if (resumeOwed) FocusGain.RESUME else FocusGain.IGNORE

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
    fun selectionCancelsOwedResume(newSelectedPlayerId: String?, localPlayerId: String?): Boolean =
        newSelectedPlayerId != null && localPlayerId != null && newSelectedPlayerId != localPlayerId
}

/** The three answers the platform gives to a focus request. */
enum class FocusRequestResult { GRANTED, DELAYED, FAILED }
