package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.repository.PlayerCommandRetry.Decision
import org.junit.Test

/**
 * A play or pause that failed while the connection was down is retried only while it is
 * still the latest one for its player, and a pause the local controller owes MA is left to
 * the controller's reconnect, whose own send is always retried.
 */
class PlayerCommandRetryTest {

    private fun decide(
        sentGeneration: Long = 3,
        currentGeneration: Long? = 3,
        pause: Boolean = true,
        sentByController: Boolean = false,
        controllerOwnsPause: Boolean = false,
    ) = PlayerCommandRetry.decide(sentGeneration, currentGeneration, pause, sentByController, controllerOwnsPause)

    @Test
    fun `the latest command for the player is retried`() {
        assertThat(decide(pause = false)).isEqualTo(Decision.RETRY)
    }

    /** A play that failed transiently, then a pause before its retry: the play must not undo the pause. */
    @Test
    fun `a play superseded by a newer pause is not retried`() {
        assertThat(decide(pause = false, currentGeneration = 4)).isEqualTo(Decision.SUPERSEDED)
    }

    /**
     * The S25 rerun (2026-11-06): a pause owed after the drop went to MA from the reconnect,
     * and the repository's own retry of the listener's pause would have sent it again when
     * MA came back in time.
     */
    @Test
    fun `the listener's pause the controller owes MA is not retried here`() {
        assertThat(decide(controllerOwnsPause = true)).isEqualTo(Decision.PAUSE_OWNED_BY_CONTROLLER)
    }

    /**
     * Codecheck M1: the controller's own send on the reconnect is the delivery the ownership
     * stands for. Dropping its retry on a flapping connection cleared the debt unsent.
     */
    @Test
    fun `the controller's own pause is retried while it is owed`() {
        assertThat(decide(sentByController = true, controllerOwnsPause = true)).isEqualTo(Decision.RETRY)
    }

    /** Remote players have no owed pause, so their pause keeps the retry. */
    @Test
    fun `a pause nobody owns is retried`() {
        assertThat(decide()).isEqualTo(Decision.RETRY)
    }

    @Test
    fun `a superseded command is dropped even when it is the controller's pause`() {
        assertThat(decide(currentGeneration = 4, sentByController = true, controllerOwnsPause = true))
            .isEqualTo(Decision.SUPERSEDED)
    }
}
