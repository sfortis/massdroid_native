package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.database.ArtistFeedbackSignalRow
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.BlockedArtistEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.SmartFeedbackEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistLearningMetrics
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
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
    private val settingsRepository: SettingsRepository
) : SmartListeningRepository {

    companion object {
        private const val TAG = "SmartListeningRepo"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DECAY_DAYS = 60.0

        private const val SKIP_ARTIST_SIGNAL = -0.25
        private const val LISTEN_ARTIST_SIGNAL = 0.20
        private const val LIKE_ARTIST_SIGNAL = 0.60
        private const val UNLIKE_ARTIST_SIGNAL = -0.70

        private const val SUPPRESS_SCORE_THRESHOLD = -1.8
        private const val SUPPRESS_NEGATIVE_MIN = 3
    }

    override val blockedArtistUris: Flow<Set<String>> =
        dao.observeBlockedArtistUris().map { it.toSet() }

    override suspend fun recordSkip(track: Track, artists: List<Pair<String, String>>) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "skip",
            signalPerArtist = SKIP_ARTIST_SIGNAL
        )
    }

    override suspend fun recordListen(track: Track, artists: List<Pair<String, String>>) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "listen",
            signalPerArtist = LISTEN_ARTIST_SIGNAL
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

    override suspend fun setArtistBlocked(artistUri: String, artistName: String?, blocked: Boolean) {
        val artistKey = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return
        if (blocked) {
            dao.insertArtist(ArtistEntity(uri = artistKey, name = artistName?.ifBlank { "Artist" } ?: "Artist"))
            dao.upsertBlockedArtist(
                BlockedArtistEntity(
                    artistUri = artistKey,
                    artistName = artistName?.takeIf { it.isNotBlank() },
                    blockedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Blocked artist: $artistKey")
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

    private suspend fun insertArtistSignals(
        track: Track,
        artists: List<Pair<String, String>>,
        action: String,
        signalPerArtist: Double
    ) {
        val now = System.currentTimeMillis()
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
        Log.d(
            TAG,
            "Recorded $action for $trackKey on ${feedback.size} artist(s), signal=${String.format(Locale.US, "%.2f", signalPerArtist)}"
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

    private fun computeArtistMetrics(rows: List<ArtistFeedbackSignalRow>): Map<String, ArtistLearningMetrics> {
        val now = System.currentTimeMillis()
        val grouped = rows.groupBy { it.artistUri }
        return grouped.mapValues { (_, signals) ->
            val score = signals.sumOf { row ->
                val ageDays = ((now - row.createdAt).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
                val decay = exp(-ageDays / DECAY_DAYS)
                row.signal * decay
            }
            val negativeSignals = signals.count { it.signal < 0.0 }
            ArtistLearningMetrics(
                score = score,
                negativeSignals = negativeSignals,
                totalSignals = signals.size
            )
        }
    }
}
