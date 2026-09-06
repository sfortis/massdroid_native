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
    AUDIO_FOCUS
}

/** A playback command as it was issued, before the server has answered. */
data class PlaybackIntent(
    val willPlay: Boolean,
    val cause: PlaybackIntentCause = PlaybackIntentCause.LISTENER
)

/**
 * Decides whether the local (phone-as-speaker) player may start playing.
 *
 * Implemented by the Sendspin audio controller, which owns audio-focus policy,
 * and consulted by the repository before it sends a play command for that
 * player. Any other player id is always allowed: focus only governs audio this
 * device produces itself.
 */
fun interface LocalPlaybackGate {
    fun allowsPlay(playerId: String): Boolean
}
