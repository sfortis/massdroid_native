package net.asksakis.massdroidv2.domain.player

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.PlayerSelectionLock
import org.junit.Test

/**
 * A queue moved from the Players screen while the app already showed the target, and
 * the user saw nothing happen. Every screen now reports the outcome in the same words,
 * including the case where a selection lock keeps the app on another player.
 */
class QueueTransferTest {

    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val playerRepository = mockk<PlayerRepository>(relaxed = true)
    private val players = MutableStateFlow(
        listOf(Player("phone", "MassDroid S25"), Player("pi", "sendspin-swift"))
    )
    private val lock = MutableStateFlow<PlayerSelectionLock?>(null)
    private val transfer = QueueTransfer(musicRepository, playerRepository)

    init {
        every { playerRepository.players } returns players
        every { playerRepository.selectionLock } returns lock
    }

    @Test
    fun `a move that the app can follow names the target`() = runTest {
        every { playerRepository.selectPlayer("pi") } returns true

        val outcome = transfer.moveAndFollow("phone", "pi")

        assertThat(outcome).isEqualTo(QueueTransferOutcome.Moved("sendspin-swift"))
        assertThat(outcome.userMessage()).isEqualTo("Queue moved to sendspin-swift")
        coVerify(exactly = 1) { musicRepository.transferQueue("phone", "pi") }
    }

    @Test
    fun `a selection lock is reported with the player the app stayed on`() = runTest {
        lock.value = PlayerSelectionLock("phone", "car_audio")
        every { playerRepository.selectPlayer("pi") } returns false

        val outcome = transfer.moveAndFollow("phone", "pi")

        assertThat(outcome).isEqualTo(QueueTransferOutcome.MovedButLocked("sendspin-swift", "MassDroid S25"))
        assertThat(outcome.userMessage()).contains("stays on MassDroid S25")
    }

    @Test
    fun `a server failure does not touch the selection`() = runTest {
        val refused = IllegalStateException("Player pi is not available")
        coEvery { musicRepository.transferQueue("phone", "pi") } throws refused

        val outcome = transfer.moveAndFollow("phone", "pi")

        assertThat(outcome).isEqualTo(QueueTransferOutcome.Failed("sendspin-swift", refused))
        assertThat(outcome.userMessage()).isEqualTo("Couldn't move the queue to sendspin-swift")
        coVerify(exactly = 0) { playerRepository.selectPlayer(any()) }
    }

    @Test
    fun `an unknown player id is shown as is rather than dropped`() = runTest {
        every { playerRepository.selectPlayer("ghost") } returns true

        val outcome = transfer.moveAndFollow("phone", "ghost")

        assertThat(outcome.targetName).isEqualTo("ghost")
    }
}
