package net.asksakis.massdroidv2.domain.feedback

import android.util.Log
import kotlinx.coroutines.CancellationException
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.DislikeReceipt
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Not for me" on the playing track, and taking it back. Shared by the phone and the TV
 * player, so both record the same thing and both can undo it.
 *
 * The only negative signal the engine does not have to infer. Every other one is read out
 * of behaviour, and a skip in particular is ambiguous: it can mean "not this song", "not
 * right now", or nothing at all. The track is buried, its artist is only brushed, and the
 * whole thing is undoable because it is one press away from the other controls.
 */
@Singleton
class TrackDisliker @Inject constructor(
    private val smartListeningRepository: SmartListeningRepository,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) {

    sealed interface Outcome {
        /** Recorded, and the player moved on. [undo] puts it back. */
        data class Recorded(val undo: DislikeUndo) : Outcome

        /** Smart Listening is off and the server could not record one either. */
        data object NothingToRecord : Outcome

        /** Recording failed; [cause] says why. */
        data class Failed(val cause: Throwable) : Outcome
    }

    /**
     * Dislikes [track] locally and on the Music Assistant server, then skips past it on
     * [playerId] without filing the skip as a second negative signal.
     */
    suspend fun dislike(track: Track, playerId: String?): Outcome = try {
        // Null when Smart Listening is off; the server dislike below does not depend on it.
        val receipt = smartListeningRepository.recordDislike(track, trackArtists(track))
        val serverDislike = try {
            dislikeOnServer(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local dislike stands on its own; only report the failure when nothing
            // was recorded at all, and then as the failure it is.
            Log.w(TAG, "server dislike failed: ${e.message}")
            if (receipt == null) return Outcome.Failed(e)
            null
        }
        if (receipt == null && serverDislike == null) {
            Outcome.NothingToRecord
        } else {
            // Skipping past it is the point, but the skip must not be filed as a second
            // negative signal on top of the dislike. A failed skip leaves the dislike
            // recorded, and so still undoable.
            if (playerId != null) {
                try {
                    playerRepository.skipWithoutSignal(playerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "skip after dislike failed: ${e.message}")
                }
            }
            Outcome.Recorded(DislikeUndo(receipt, serverDislike, track.name))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "dislike failed: ${e.message}")
        Outcome.Failed(e)
    }

    /** Puts back what [dislike] recorded. Throws when it could not. */
    suspend fun undo(undo: DislikeUndo) {
        undo.receipt?.let { smartListeningRepository.undoDislike(it) }
        undo.serverDislike?.let { musicRepository.undoServerDislike(it.uri, it.wasFavorite) }
    }

    /**
     * The same dislike on the Music Assistant server, which keeps it per user and passes it on
     * to the track's providers. Null when the server cannot record one; throws when it refused.
     */
    private suspend fun dislikeOnServer(track: Track): ServerDislike? {
        if (track.uri.isBlank()) return null
        return if (musicRepository.dislikeOnServer(track.uri)) ServerDislike(track.uri, track.favorite) else null
    }

    private fun trackArtists(track: Track): List<Pair<String, String>> {
        val uri = MediaIdentity.canonicalArtistKey(itemId = track.artistItemId, uri = track.artistUri)
            ?: return emptyList()
        val name = track.artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }
        return listOf(uri to name)
    }

    private companion object {
        const val TAG = "TrackDisliker"
    }
}

/**
 * What a dislike recorded, so it can be taken back exactly. Either part is null when it was
 * not recorded: [receipt] with Smart Listening off, [serverDislike] on a server that cannot
 * record one. At least one of the two is present.
 */
data class DislikeUndo(
    val receipt: DislikeReceipt?,
    val serverDislike: ServerDislike?,
    val trackName: String,
)

/** A dislike recorded on the server, with what to restore: a like when [wasFavorite]. */
data class ServerDislike(val uri: String, val wasFavorite: Boolean)
