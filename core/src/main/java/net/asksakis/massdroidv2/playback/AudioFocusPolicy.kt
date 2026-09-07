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
     * [localPlayerSelected] is what stops the music arriving back on this phone
     * after the listener moved to another speaker during the interruption.
     */
    fun onFocusGain(resumeOwed: Boolean, localPlayerSelected: Boolean): FocusGain =
        if (resumeOwed && localPlayerSelected) FocusGain.RESUME else FocusGain.IGNORE
}

/** The three answers the platform gives to a focus request. */
enum class FocusRequestResult { GRANTED, DELAYED, FAILED }
