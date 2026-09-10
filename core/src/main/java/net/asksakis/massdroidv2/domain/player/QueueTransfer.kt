package net.asksakis.massdroidv2.domain.player

import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

/** What happened when a queue was moved to another player. */
sealed interface QueueTransferOutcome {
    val targetName: String

    /** The queue moved and the app now shows the target player. */
    data class Moved(override val targetName: String) : QueueTransferOutcome

    /**
     * The queue moved on the server but a selection lock (car audio, Android Auto)
     * kept the app on [lockedToName]. The screen would otherwise keep showing a
     * player that is no longer the one playing, so the user has to be told.
     */
    data class MovedButLocked(
        override val targetName: String,
        val lockedToName: String
    ) : QueueTransferOutcome

    /** The server refused or the request failed; nothing changed. */
    data class Failed(override val targetName: String, val cause: Exception) : QueueTransferOutcome
}

/** The one line a screen shows for [this] outcome, identical on every screen. */
fun QueueTransferOutcome.userMessage(): String = when (this) {
    is QueueTransferOutcome.Moved -> "Queue moved to $targetName"
    is QueueTransferOutcome.MovedButLocked ->
        "Queue moved to $targetName, but this app stays on $lockedToName"
    is QueueTransferOutcome.Failed -> "Couldn't move the queue to $targetName"
}

/**
 * Moves a queue to another player and follows it, the same way from every screen.
 *
 * The server command is a move: the source queue is emptied and the target's own queue
 * is replaced. Callers select the target afterwards so the app shows what is playing.
 * A selection lock can refuse that; the refusal is reported rather than swallowed.
 */
class QueueTransfer(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository
) {

    suspend fun moveAndFollow(sourceQueueId: String, targetPlayerId: String): QueueTransferOutcome {
        val targetName = displayName(targetPlayerId)
        try {
            musicRepository.transferQueue(sourceQueueId, targetPlayerId)
        } catch (e: Exception) {
            return QueueTransferOutcome.Failed(targetName, e)
        }
        if (playerRepository.selectPlayer(targetPlayerId)) return QueueTransferOutcome.Moved(targetName)
        val lockedTo = playerRepository.selectionLock.value?.playerId
        return QueueTransferOutcome.MovedButLocked(targetName, lockedTo?.let(::displayName) ?: "the locked player")
    }

    private fun displayName(playerId: String): String =
        playerRepository.players.value.firstOrNull { it.playerId == playerId }?.displayName ?: playerId
}
