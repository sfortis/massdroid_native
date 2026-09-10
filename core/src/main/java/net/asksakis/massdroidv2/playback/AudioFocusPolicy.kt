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
     * Decide whether a focus gain should start playback.
     *
     * [localOutputStillStreaming] must come from the STREAM, not from the
     * connection. The protocol client deliberately stays connected as an available
     * player after the music leaves, so the connection state still reads STREAMING
     * once another speaker has taken over, while the stream flag goes down on
     * stream/end. Feeding it the connection state resumes a phone the music has
     * moved away from, and two speakers play at once.
     *
     * Two earlier attempts at this condition were both wrong and both measurable.
     * Asking whether the phone was the SELECTED player refused 16 of 41 resumes
     * over two days, every one with the transport mid-reconnect where the selection
     * momentarily reads as nothing, so a notification paused the music for good.
     * Asking the connection state instead fixed those but opened the two-speaker
     * case, because selecting another player does not pause the one being left.
     * The stream flag answers both: it stays up through a reconnect and goes down
     * when the music actually leaves.
     */
    fun onFocusGain(resumeOwed: Boolean, localOutputStillStreaming: Boolean): FocusGain =
        if (resumeOwed && localOutputStillStreaming) FocusGain.RESUME else FocusGain.IGNORE
}

/** The three answers the platform gives to a focus request. */
enum class FocusRequestResult { GRANTED, DELAYED, FAILED }
