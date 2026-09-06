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
