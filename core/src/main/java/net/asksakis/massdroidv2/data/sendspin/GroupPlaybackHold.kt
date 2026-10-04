package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroupHold"
private const val PAUSE_CONFIRM_TIMEOUT_MS = 3_000L

/**
 * Pauses the group this phone plays in while a block runs, and resumes it
 * afterwards, so the other speakers do not play into a measurement that listens
 * with the microphone.
 *
 * Only a group that was playing is paused, and only that group is resumed. A
 * phone playing on its own is left alone: the caller stops the phone's own
 * output, and nothing else is audible.
 */
@Singleton
class GroupPlaybackHold @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun <T> pausedWhile(block: suspend () -> T): T {
        val leaderId = playingGroupLeaderId()
        if (leaderId != null) {
            Log.d(TAG, "Pausing group $leaderId for a measurement")
            runCatching { playerRepository.pause(leaderId) }
                .onFailure { Log.w(TAG, "Couldn't pause group $leaderId", it) }
            // The first measurement should not start while the server still
            // reports the group playing; a speaker's own buffer drains after it.
            withTimeoutOrNull(PAUSE_CONFIRM_TIMEOUT_MS) {
                playerRepository.players.first { list ->
                    list.firstOrNull { it.playerId == leaderId }?.state != PlaybackState.PLAYING
                }
            }
        }
        try {
            return block()
        } finally {
            if (leaderId != null) {
                withContext(NonCancellable) {
                    Log.d(TAG, "Resuming group $leaderId")
                    runCatching { playerRepository.play(leaderId) }
                        .onFailure { Log.w(TAG, "Couldn't resume group $leaderId", it) }
                }
            }
        }
    }

    /**
     * The player that leads the group this phone is in, when that group is
     * playing: the player that lists the phone among its members, or the phone
     * itself when it lists others. Null when the phone is not grouped.
     */
    private suspend fun playingGroupLeaderId(): String? {
        val phoneId = settingsRepository.sendspinClientId.first() ?: return null
        val players = playerRepository.players.value
        val leader: Player = players.firstOrNull { it.playerId != phoneId && phoneId in it.groupChilds }
            ?: players.firstOrNull { it.playerId == phoneId && it.groupChilds.any { child -> child != phoneId } }
            ?: return null
        return leader.playerId.takeIf { leader.state == PlaybackState.PLAYING }
    }
}
