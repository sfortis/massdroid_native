package net.asksakis.massdroidv2.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.database.AlbumEntity
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.database.ArtistFeedbackSignalRow
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.BlockedArtistEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.SmartFeedbackEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistLearningMetrics
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.DislikeReceipt
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class SmartListeningRepositoryImpl @Inject constructor(
    private val dao: PlayHistoryDao,
    private val settingsRepository: SettingsRepository,
    private val transactions: TransactionRunner
) : SmartListeningRepository {

    companion object {
        private const val TAG = "SmartListeningRepo"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DECAY_DAYS = 60.0

        private const val SKIP_ARTIST_SIGNAL = -0.50
        private const val SKIP_ARTIST_DAMPENING = 0.25
        /** Below this a skip reads as outright rejection. */
        private const val HARD_SKIP_SEC = 15.0
        /** Below this it still reads as dislike, just a milder one. */
        private const val SOFT_SKIP_SEC = 30.0
        private const val LISTEN_ARTIST_SIGNAL = 0.20
        private const val LIKE_ARTIST_SIGNAL = 0.60
        private const val UNLIKE_ARTIST_SIGNAL = -0.70

        /**
         * Where an explicitly disliked track lands. Comfortably under the
         * suppression line (-0.15) so no later positive signal can drag it back
         * into a mix by accident.
         */
        private const val DISLIKE_TRACK_SCORE = -2.0

        private const val SUPPRESS_SCORE_THRESHOLD = -1.5
        private const val SUPPRESS_NEGATIVE_MIN = 3
    }

    override val blockedArtistUris: Flow<Set<String>> =
        dao.observeBlockedArtistUris().map { it.toSet() }

    override suspend fun recordSkip(track: Track, artists: List<Pair<String, String>>, listenedMs: Long?) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        val trackSignal = scaleSkipSignal(listenedMs, track.duration)
        val artistSignal = trackSignal * SKIP_ARTIST_DAMPENING
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "skip",
            signalPerArtist = artistSignal,
            trackSignalOverride = trackSignal,
            listenedMs = listenedMs
        )
    }

    override suspend fun recordListen(track: Track, artists: List<Pair<String, String>>, listenedMs: Long?) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        val signal = scaleListenSignal(listenedMs, track.duration)
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "listen",
            signalPerArtist = signal,
            listenedMs = listenedMs
        )
    }

    override suspend fun recordLike(track: Track, artists: List<Pair<String, String>>) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "like",
            signalPerArtist = LIKE_ARTIST_SIGNAL
        )
    }

    override suspend fun recordUnlike(track: Track, artists: List<Pair<String, String>>) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "unlike",
            signalPerArtist = UNLIKE_ARTIST_SIGNAL
        )
    }

    override suspend fun recordDislike(
        track: Track,
        artists: List<Pair<String, String>>
    ): DislikeReceipt? {
        if (!settingsRepository.smartListeningEnabled.first()) return null
        val trackKey = MediaIdentity.canonicalTrackKey(track.itemId, track.uri) ?: return null
        val normalized = normalizeArtists(track, artists)
        if (normalized.isEmpty()) return null

        val now = System.currentTimeMillis()
        // The artist is only brushed, using the same dampening a skip gets: one
        // bad track is not a verdict on whoever made it. Rejecting the artist
        // outright is what the block list is for.
        val artistSignal = UNLIKE_ARTIST_SIGNAL * SKIP_ARTIST_DAMPENING
        // One transaction: a dislike that filed its feedback but failed to bury
        // the track would leave it playable with a negative mark against it.
        // The score is read in here too, so two dislikes of the same track
        // cannot both snapshot the same starting value and hand out receipts
        // that undo to the wrong place.
        val previousScore = transactions.inTransaction {
            val before = dao.getTrackScore(trackKey) ?: 0.0
            insertArtistSignals(
                track = track,
                artists = artists,
                action = "dislike",
                signalPerArtist = artistSignal,
                trackSignalOverride = 0.0,
                now = now
            )
            // Set, not adjust: the track has to end up below the suppression
            // line whatever it scored before, so it never comes back in a mix.
            dao.setTrackScore(trackKey, DISLIKE_TRACK_SCORE)
            before
        }
        Log.d(TAG, "Disliked $trackKey (score $previousScore -> $DISLIKE_TRACK_SCORE)")
        return DislikeReceipt(
            trackKey = trackKey,
            previousScore = previousScore,
            artistSignal = artistSignal,
            artistUris = normalized.map { it.first },
            createdAt = now,
        )
    }

    override suspend fun undoDislike(receipt: DislikeReceipt) {
        val restored = transactions.inTransaction {
            // Compare-and-set, not a blind write: if anything has scored this
            // track since the dislike, that opinion is newer than the undo and
            // keeps precedence. The feedback row goes either way, since the
            // listener did take the dislike back.
            val changed = dao.restoreTrackScoreIfUnchanged(
                trackUri = receipt.trackKey,
                expected = DISLIKE_TRACK_SCORE,
                restore = receipt.previousScore,
            )
            dao.deleteSmartFeedback(receipt.trackKey, "dislike", receipt.createdAt)
            changed > 0
        }
        Log.d(
            TAG,
            if (restored) "Undid dislike for ${receipt.trackKey} (score back to ${receipt.previousScore})"
            else "Undid dislike for ${receipt.trackKey}; score left alone, something scored it since"
        )
    }

    override suspend fun setArtistBlocked(artistUri: String, artistName: String?, blocked: Boolean) {
        val artistKey = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return
        if (blocked) {
            dao.upsertBlockedArtist(
                BlockedArtistEntity(
                    artistUri = artistKey,
                    artistName = artistName?.takeIf { it.isNotBlank() },
                    blockedAt = System.currentTimeMillis()
                )
            )
            val verify = dao.getBlockedArtistUris()
            Log.d(TAG, "Blocked artist: $artistKey (total blocked: ${verify.size}, contains=${ artistKey in verify})")
        } else {
            dao.deleteBlockedArtist(artistKey)
            Log.d(TAG, "Unblocked artist: $artistKey")
        }
    }

    override suspend fun getBlockedArtistUris(): Set<String> = dao.getBlockedArtistUris().toSet()

    override suspend fun getBlockedArtists(): List<BlockedArtistInfo> =
        dao.getBlockedArtists().map {
            BlockedArtistInfo(
                artistUri = it.artistUri,
                artistName = it.artistName,
                blockedAt = it.blockedAt
            )
        }

    override suspend fun getArtistMetrics(days: Int): Map<String, ArtistLearningMetrics> {
        val since = System.currentTimeMillis() - days * MILLIS_PER_DAY
        val rows = dao.getArtistFeedbackSignals(since)
        return computeArtistMetrics(rows)
    }

    override suspend fun getSuppressedArtistUris(days: Int): Set<String> {
        return getArtistMetrics(days)
            .filter { (_, m) ->
                m.score <= SUPPRESS_SCORE_THRESHOLD && m.negativeSignals >= SUPPRESS_NEGATIVE_MIN
            }
            .keys
    }

    override suspend fun getSuppressedTrackUris(): Set<String> =
        dao.getSuppressedTrackUris().toSet()

    @VisibleForTesting
    internal fun scaleSkipSignal(listenedMs: Long?, durationSec: Double?): Double {
        if (listenedMs == null || durationSec == null || durationSec <= 0.0) return SKIP_ARTIST_SIGNAL
        val listenedSec = listenedMs / 1000.0
        val ratio = listenedSec / durationSec
        return when {
            // A skip inside the first quarter-minute is a judgement about the
            // track; the boundaries used to sit at 5s and 15s, which asked the
            // listener to react faster than anyone reasonably does. Measured on
            // a real history: 15% of skips landed under 5s and were dominated by
            // navigation rather than dislike (the `previous` button used to file
            // one of these too, and that is where the noise came from).
            listenedSec < HARD_SKIP_SEC -> -0.60
            listenedSec < SOFT_SKIP_SEC -> -0.45
            ratio < 0.25 -> -0.35
            ratio < 0.50 -> -0.20
            ratio < 0.75 -> -0.08
            else -> -0.03
        }
    }

    @VisibleForTesting
    internal fun scaleListenSignal(listenedMs: Long?, durationSec: Double?): Double {
        if (listenedMs == null || durationSec == null || durationSec <= 0.0) return LISTEN_ARTIST_SIGNAL
        val ratio = (listenedMs / 1000.0 / durationSec).coerceIn(0.0, 1.0)
        return when {
            ratio < 0.15 -> -0.20
            ratio < 0.30 -> -0.05
            ratio < 0.50 -> 0.08
            ratio < 0.75 -> 0.18
            else -> 0.28
        }
    }

    private suspend fun insertArtistSignals(
        track: Track,
        artists: List<Pair<String, String>>,
        action: String,
        signalPerArtist: Double,
        trackSignalOverride: Double? = null,
        listenedMs: Long? = null,
        // Passed in when the caller needs the rows to carry a timestamp it can
        // find again, which is how a dislike is undone.
        now: Long = System.currentTimeMillis()
    ) {
        val trackKey = MediaIdentity.canonicalTrackKey(track.itemId, track.uri) ?: return
        val albumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
        val normalized = normalizeArtists(track, artists)
        if (normalized.isEmpty()) return

        val feedback = normalized.map { (artistUri, _) ->
            SmartFeedbackEntity(
                trackUri = trackKey,
                artistUri = artistUri,
                action = action,
                signal = signalPerArtist,
                createdAt = now
            )
        }
        val trackScore = trackSignalOverride ?: signalPerArtist
        transactions.inTransaction {
            if (!albumKey.isNullOrBlank()) {
                dao.insertAlbum(
                    AlbumEntity(
                        uri = albumKey,
                        name = track.albumName,
                        imageUrl = track.imageUrl,
                        year = sanitizeYear(track.year)
                    )
                )
            }
            dao.insertTrack(
                TrackEntity(
                    uri = trackKey,
                    name = track.name,
                    albumUri = albumKey,
                    duration = track.duration,
                    imageUrl = track.imageUrl
                )
            )
            normalized.forEach { (artistUri, artistName) ->
                dao.insertArtist(ArtistEntity(uri = artistUri, name = artistName.ifBlank { "Artist" }))
            }
            dao.insertSmartFeedback(feedback)
            // A zero delta is not a score change. The dislike path passes one
            // because it sets the score absolutely instead, and issuing the
            // no-op write anyway just puts a second, contradictory-looking
            // statement about the same track in the same transaction.
            if (trackScore != 0.0) dao.adjustTrackScore(trackKey, trackScore)
        }
        val artistNames = normalized.joinToString(", ") { it.second }
        val label = when {
            action == "skip" && signalPerArtist <= -0.45 -> "HARD SKIP"
            action == "skip" -> "SOFT SKIP"
            action == "listen" && signalPerArtist >= 0.18 -> "FULL LISTEN"
            action == "listen" && signalPerArtist > 0.0 -> "PARTIAL LISTEN"
            action == "listen" -> "LOW LISTEN"
            action == "like" -> "LIKE"
            action == "unlike" -> "UNLIKE"
            else -> action.uppercase(Locale.US)
        }
        val listenInfo = if (listenedMs != null && track.duration != null && track.duration > 0) {
            val listenedSec = listenedMs / 1000
            val durationSec = track.duration.toInt()
            val pct = ((listenedMs / 1000.0 / track.duration) * 100).toInt().coerceAtMost(100)
            " | ${listenedSec}s/${durationSec}s ($pct%)"
        } else {
            ""
        }
        Log.d(
            TAG,
            "[$label] \"${track.name}\" by $artistNames | signal=${String.format(Locale.US, "%+.2f", signalPerArtist)}$listenInfo"
        )
    }

    private fun normalizeArtists(
        track: Track,
        artists: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val normalized = artists.mapNotNull { (uri, name) ->
            MediaIdentity.canonicalArtistKey(uri = uri)?.let { it to name }
        }.distinctBy { it.first }
        if (normalized.isNotEmpty()) return normalized
        val primaryUri = MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri) ?: return emptyList()
        val primaryName = track.artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }
        return listOf(primaryUri to primaryName)
    }

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }

    private fun computeArtistMetrics(rows: List<ArtistFeedbackSignalRow>): Map<String, ArtistLearningMetrics> {
        val now = System.currentTimeMillis()
        val grouped = rows.groupBy { it.artistName }
        return grouped.entries.associate { (_, signals) ->
            val canonicalUri = signals.minOf { it.artistUri }
            val score = signals.sumOf { row ->
                val ageDays = ((now - row.createdAt).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
                val decay = exp(-ageDays / DECAY_DAYS)
                row.signal * decay
            }
            val negativeSignals = signals.count { it.signal < 0.0 }
            canonicalUri to ArtistLearningMetrics(
                score = score,
                negativeSignals = negativeSignals,
                totalSignals = signals.size
            )
        }
    }
}
