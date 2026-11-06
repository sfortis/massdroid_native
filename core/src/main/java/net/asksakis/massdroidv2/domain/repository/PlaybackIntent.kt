package net.asksakis.massdroidv2.domain.repository

/**
 * What asked for playback to start or stop.
 *
 * A consumer that issues its own pause commands needs to tell them apart from the
 * listener's. The phone-as-speaker output pauses itself when another app takes
 * audio focus, and it has to resume when that focus comes back; a pause the
 * listener pressed during the same interruption must cancel that resume instead.
 * Both arrive as the same command, so the cause travels with the event.
 */
enum class PlaybackIntentCause {
    /** The listener, or anything acting on their behalf, such as a car head unit. */
    LISTENER,

    /** The local output stepping aside for another app that took audio focus. */
    AUDIO_FOCUS,

    /**
     * A pause made earlier, sent again because the server may not have heard it, for
     * example after a dropped connection. It decides nothing new: the output is already
     * paused, and a resume owed to an interruption stays owed.
     */
    REPEAT
}

/** A playback command as it was issued, before the server has answered. */
data class PlaybackIntent(
    val willPlay: Boolean,
    val cause: PlaybackIntentCause = PlaybackIntentCause.LISTENER
)

/**
 * A play or pause the repository did not send, because a newer play or pause for the same
 * player was issued while it waited to retry. Thrown to a caller that waits for the
 * server's answer, so it does not take the missing send for an answer.
 */
class CommandNotSentException(message: String) : Exception(message)

/**
 * What the local (phone-as-speaker) player's controller decides about play and pause
 * commands for that player.
 *
 * Implemented by the Sendspin audio controller, which owns audio-focus policy and the
 * pause owed to the server after a dropped connection, and consulted by the repository.
 * The controller owns both answers. Any other player id is always allowed to play and
 * never owned: focus only governs audio this device produces itself, and only this
 * device's player has an owed pause.
 */
interface LocalPlaybackGate {
    /** Whether a play command may be sent for [playerId]; asked before it is sent. */
    fun allowsPlay(playerId: String): Boolean

    /**
     * Whether the controller delivers a pause for [playerId] itself: it is owed to the
     * server and sent again on the MA reconnect. The repository's transient retry then
     * drops its own copy, so MA hears the pause once.
     */
    fun ownsPause(playerId: String): Boolean
}
