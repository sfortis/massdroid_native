package net.asksakis.massdroidv2.domain.party

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.Player

/**
 * The queue id `party/player` named, with the players as they were when the server answered.
 * The Party screen resolves its target only from this, never from the live players alone, so
 * the card shows nothing in between and changes only when the resolved target changes.
 */
data class PartyTargetSnapshot(val queueId: String?, val players: List<Player>) {

    fun resolve(mode: PartyMode, selectedQueueId: String?, thisPhonePlayerId: String?): PartyTarget =
        PartyTarget.resolve(queueId, mode, players, selectedQueueId, thisPhonePlayerId)
}

/** Reads the party player again after players start or stop. */
object PartyTargetFollower {

    /**
     * A new snapshot [SETTLE_MS] after the last time a player started or stopped, until
     * cancelled. The plugin's automatic choice follows whichever queue plays, so in the car a
     * home speaker that starts playing takes the guests' songs. A burst of changes gives one
     * read, and a failed read gives no snapshot, so the last one stays on screen.
     *
     * [shownPlayers] are the players of the snapshot on screen. When the players already
     * differ from them as the follow starts, for example after a player started while the
     * screen was in the background, the party player is read at once. Null means no snapshot
     * is shown, and the load in progress reads the target itself.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(
        players: StateFlow<List<Player>>,
        shownPlayers: List<Player>?,
        readTarget: suspend () -> String?
    ): Flow<PartyTargetSnapshot> {
        val shownStates = shownPlayers?.playStates()
        return players
            .distinctUntilChanged { old, new -> old.playStates() == new.playStates() }
            .withIndex()
            .filter { (index, current) -> index > 0 || (shownStates != null && current.playStates() != shownStates) }
            .transformLatest { (index, _) ->
                if (index > 0) delay(SETTLE_MS)
                val queueId = try {
                    readTarget()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the party player again, keeping the current one: ${e.message}")
                    return@transformLatest
                }
                emit(PartyTargetSnapshot(queueId, players.value))
            }
    }

    private fun List<Player>.playStates() = map { it.playerId to it.state }

    const val SETTLE_MS = 1_000L
    private const val TAG = "PartyTarget"
}
