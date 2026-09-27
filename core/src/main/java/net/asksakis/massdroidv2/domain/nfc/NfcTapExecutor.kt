package net.asksakis.massdroidv2.domain.nfc

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.websocket.SavedCredentialsConnector
import net.asksakis.massdroidv2.domain.repository.EverythingBlockedException
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NfcTap"

/** What came of acting on a scanned tag, in the terms the screen has to report. */
sealed interface NfcTapOutcome {
    /** Playback was asked for. [label] names what, as far as the tag and the queue know. */
    data class Started(val label: String?, val playerName: String?) : NfcTapOutcome
    /** The tag was read but the server could not be reached in time. */
    data object NotConnected : NfcTapOutcome
    /** The player the tag names is not on this server, or has not come back yet. */
    data class PlayerMissing(val playerId: String) : NfcTapOutcome
    /** The tag names no player and none is selected, so there is nowhere to play. */
    data object NoPlayer : NfcTapOutcome
    /** Everything the tag points at is by a blocked artist. */
    data object Blocked : NfcTapOutcome
    /** The command went out and the server refused it, or the socket died under it. */
    data class Failed(val message: String?) : NfcTapOutcome
}

/**
 * Acts on a scanned tag: connect if needed, put the named player in charge, and replace its
 * queue with what the tag points at.
 *
 * It lives here rather than in the activity that receives the NFC intent, because the
 * activity's job is to show the result of this and disappear. The same orchestration is
 * what any other entry point would need.
 */
@Singleton
class NfcTapExecutor @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val settingsRepository: SettingsRepository,
    private val connector: SavedCredentialsConnector
) {

    suspend fun execute(payload: NfcTagPayload): NfcTapOutcome {
        if (!connector.connectIfNeeded()) {
            Log.w(TAG, "Not connected, dropping ${payload.mediaUri}")
            return NfcTapOutcome.NotConnected
        }

        val targetId = payload.playerId ?: playerRepository.requireSelectedPlayerId()
        if (targetId.isNullOrBlank()) return NfcTapOutcome.NoPlayer

        // The phone can only play for itself once the local speaker is switched on, and a
        // tag bound to it is a request to do exactly that. Asked before the player is
        // waited for, because the client registers with the server only once it is enabled.
        if (targetId == settingsRepository.sendspinClientId.first() &&
            !settingsRepository.sendspinEnabled.first()
        ) {
            Log.d(TAG, "Tag targets this phone; enabling the local speaker")
            settingsRepository.setSendspinEnabled(true)
        }

        // players/all can still be filling in right after a cold start, and a player the
        // server has not mentioned yet would be selected as nothing at all. Bounded, so a
        // tag naming a speaker that is genuinely gone reports that instead of hanging.
        val player = withTimeoutOrNull(PLAYER_WAIT_MS) {
            playerRepository.players.first { players -> players.any { it.playerId == targetId } }
        }?.first { it.playerId == targetId }
        if (player == null) {
            Log.w(TAG, "Player $targetId never appeared")
            return NfcTapOutcome.PlayerMissing(targetId)
        }

        // A tap is an instruction about where the music goes, so the app follows it: the
        // transport controls, the mini player and the volume keys all read the selection,
        // and leaving it behind would point them at the speaker the user just walked away
        // from. A selection lock refuses the change (the car holds one), and the music
        // still goes where the tag said.
        val selected = playerRepository.selectPlayer(targetId)
        if (!selected) Log.d(TAG, "Selection locked elsewhere; playing on $targetId anyway")

        return try {
            playerRepository.setQueueFilterMode(targetId, PlayerRepository.QueueFilterMode.NORMAL)
            // Replace, because a tap on a tag means start this now. Leaving the option out
            // lets the server decide between replacing and appending, which would turn a
            // second tap into a growing queue rather than the restart it reads as.
            //
            // Awaited, because the toast is the only thing a tap ever says and it should not
            // say "playing" for a uri the server refused. The wait is capped: a large
            // container can keep the server busy well past the point where standing in front
            // of a speaker holding a phone stops being reasonable, and by then the command is
            // sent and the music is the server's business.
            withTimeoutOrNull(PLAY_CONFIRM_MS) {
                musicRepository.playMedia(
                    queueId = targetId,
                    uri = payload.mediaUri,
                    option = "replace",
                    awaitResponse = true
                )
            }
            NfcTapOutcome.Started(payload.label, player.displayName)
        } catch (e: EverythingBlockedException) {
            Log.d(TAG, "Everything at ${payload.mediaUri} is blocked")
            NfcTapOutcome.Blocked
        } catch (e: Exception) {
            Log.w(TAG, "play failed for ${payload.mediaUri}: ${e.message}")
            NfcTapOutcome.Failed(e.message)
        }
    }

    private companion object {
        /**
         * How long a named player may take to appear after a cold start. The connect itself
         * is already waited out separately, so this only covers the players arriving over
         * the socket after it.
         */
        const val PLAYER_WAIT_MS = 4_000L

        /**
         * How long to hold the tap open for the server's answer before reporting the
         * playback as started regardless. Long enough for a refusal to come back and be
         * shown, short enough that a slow container does not keep the activity on screen.
         */
        const val PLAY_CONFIRM_MS = 6_000L
    }
}
