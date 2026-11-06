package net.asksakis.massdroidv2.data.repository

/**
 * Whether a play or pause that failed because the connection was down is sent again.
 *
 * The repository retries such a command once, after the reconnect it starts. By then the
 * listener may have pressed something else, and a retried play arriving after a newer
 * pause started playback the listener had just stopped. So the latest play or pause for
 * a player wins: each one takes the next generation of that player, and a retry whose
 * generation is no longer the current one is dropped.
 *
 * A pause the phone's own player owes the server is sent again by its controller on the
 * MA reconnect. Retried here as well, MA heard it twice, so a pause the controller owns
 * is not retried, unless it is the controller's own send: that one is the delivery the
 * ownership stands for, and dropping its retry lost the pause.
 */
internal object PlayerCommandRetry {

    enum class Decision {
        /** Send the command again. */
        RETRY,

        /** A newer play or pause for the same player was issued in the meantime. */
        SUPERSEDED,

        /** The pause is owed and the controller sends it on the MA reconnect. */
        PAUSE_OWNED_BY_CONTROLLER,
    }

    /**
     * [sentGeneration] is the generation the command took, [currentGeneration] the latest
     * one issued for its player. [pause] says the command stops playback, [sentByController]
     * that the local player's controller sent it itself (the owed pause on the MA
     * reconnect), and [controllerOwnsPause] that the controller delivers this player's pause.
     */
    fun decide(
        sentGeneration: Long,
        currentGeneration: Long?,
        pause: Boolean,
        sentByController: Boolean,
        controllerOwnsPause: Boolean,
    ): Decision = when {
        currentGeneration != sentGeneration -> Decision.SUPERSEDED
        pause && !sentByController && controllerOwnsPause -> Decision.PAUSE_OWNED_BY_CONTROLLER
        else -> Decision.RETRY
    }
}
