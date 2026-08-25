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
import net.asksakis.massdroidv2.data.database.PlayOrigin
import net.asksakis.massdroidv2.data.database.TrackArtistEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistLearningMetrics
import net.asksakis.massdroidv2.domain.repository.ArtistAliasResolver
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.DislikeReceipt
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.trackIdentityKey
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Singleton
class SmartListeningRepositoryImpl @Inject constructor(
    private val dao: PlayHistoryDao,
    private val settingsRepository: SettingsRepository,
    private val transactions: TransactionRunner,
    private val artistAliases: ArtistAliasResolver
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

        /** The top tier of [scaleListenSignal]: a listen past ~75% of the track. */
        private const val FULL_LISTEN_SIGNAL = 0.28
        private const val GENERATED_FULL_LISTEN_SCALE = 0.5
        private const val GENERATED_PARTIAL_LISTEN_SCALE = 0.15

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

    override suspend fun recordListen(
        track: Track,
        artists: List<Pair<String, String>>,
        listenedMs: Long?,
        origin: PlayOrigin
    ) {
        if (!settingsRepository.smartListeningEnabled.first()) return
        val base = scaleListenSignal(listenedMs, track.duration)
        val signal = base * generatedListenScale(origin, base)
        insertArtistSignals(
            track = track,
            artists = artists,
            action = "listen",
            signalPerArtist = signal,
            listenedMs = listenedMs
        )
    }

    /**
     * How much a listen from [origin] is worth, relative to an organic one.
     *
     * A mix serving a track and the listener not skipping it is EXPOSURE, not
     * choice, and treating it as full preference is how the engine came to feed on
     * itself: Metaform reached a track score of +1.2 without the listener ever
     * choosing him, purely from passive full listens of mix-served tracks, and then
     * anchored a hip hop mix for someone who never plays hip hop.
     *
     * The rules follow the improvement plan's source-aware policy:
     * - a NEGATIVE signal (early bail) keeps full strength regardless of origin,
     *   because rejecting what the mix chose is real information about the mix;
     * - a positive signal from a generated queue is scaled down: a full listen to
     *   [GENERATED_FULL_LISTEN_SCALE] (moderate evidence: they let it play out),
     *   a partial one to [GENERATED_PARTIAL_LISTEN_SCALE] (barely evidence);
     * - organic and unknown keep full strength. Unknown is deliberately NOT
     *   discounted: most of it is the listener's own queue resumed after a process
     *   restart, and discounting it would neuter the signal that drives everything.
     */
    @VisibleForTesting
    internal fun generatedListenScale(origin: PlayOrigin, signal: Double): Double = when {
        signal <= 0.0 -> 1.0
        origin != PlayOrigin.SMART_MIX && origin != PlayOrigin.GENRE_RADIO -> 1.0
        signal >= FULL_LISTEN_SIGNAL -> GENERATED_FULL_LISTEN_SCALE
        else -> GENERATED_PARTIAL_LISTEN_SCALE
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

    /**
     * Blocks or unblocks an artist under EVERY uri the server knows for them.
     *
     * One artist reaches the app under several uris - the library row and one
     * per provider carrying them - and which one a screen hands over depends on
     * where the artist was found. Storing only that one meant a block placed
     * from the library never matched the same artist arriving from a queue
     * event, so the "blocked" artist kept playing (reported for The Midnight:
     * blocked as `library://artist/202`, playing as a Deezer uri).
     *
     * Aliases come from Music Assistant, so this stays provider-agnostic. If the
     * server cannot be reached the caller's own uri is still stored, which is no
     * worse than the previous behaviour.
     */
    override suspend fun setArtistBlocked(artistUri: String, artistName: String?, blocked: Boolean) {
        val artistKey = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return
        val name = artistName?.takeIf { it.isNotBlank() }
        val keys = (listOf(artistKey) + artistAliases.aliasesFor(artistKey))
            .mapNotNull { MediaIdentity.canonicalArtistKey(uri = it) }
            .distinct()
        if (blocked) {
            val now = System.currentTimeMillis()
            dao.upsertBlockedArtists(
                keys.map { BlockedArtistEntity(artistUri = it, artistName = name, blockedAt = now) }
            )
            Log.d(TAG, "Blocked artist ${name ?: artistKey} under ${keys.size} uri(s): $keys")
        } else {
            for (key in keys) dao.deleteBlockedArtist(key)
            // Aliases are resolved from the server, so an unblock made offline
            // would leave the other uris blocked and the artist still silenced
            // with no way to tell why. Clearing by name as well is the forgiving
            // direction: the worst case is releasing a same-named artist the
            // listener also blocked, rather than a block that cannot be undone.
            name?.let { dao.deleteBlockedArtistsByName(it) }
            Log.d(TAG, "Unblocked artist ${name ?: artistKey}")
        }
    }

    override suspend fun backfillBlockedArtistAliases(providersFingerprint: String): Boolean {
        // An empty fingerprint means the provider list has not been read yet.
        // Expanding now would record "expanded against nothing" and then never
        // run again, which is worse than waiting for the next connect.
        if (providersFingerprint.isBlank()) return false
        if (settingsRepository.blockedArtistAliasProviders.first() == providersFingerprint) return false
        val existing = dao.getBlockedArtists()
        if (existing.isEmpty()) {
            settingsRepository.setBlockedArtistAliasProviders(providersFingerprint)
            return false
        }
        var added = 0
        for (row in existing) {
            val aliases = artistAliases.aliasesFor(row.artistUri)
                .mapNotNull { MediaIdentity.canonicalArtistKey(uri = it) }
                .filter { it != row.artistUri }
            if (aliases.isEmpty()) continue
            dao.upsertBlockedArtists(
                aliases.map {
                    BlockedArtistEntity(
                        artistUri = it,
                        artistName = row.artistName,
                        blockedAt = row.blockedAt
                    )
                }
            )
            added += aliases.size
        }
        settingsRepository.setBlockedArtistAliasProviders(providersFingerprint)
        Log.d(TAG, "Blocked-artist expansion for [$providersFingerprint]: ${existing.size} blocks, +$added uri(s)")
        return added > 0
    }

    override suspend fun getBlockedArtistUris(): Set<String> = dao.getBlockedArtistUris().toSet()

    override suspend fun clearBlockedArtists() {
        dao.clearBlockedArtists()
        Log.w(TAG, "Blocked artists cleared by user action")
    }

    override suspend fun getBlockedArtists(): List<BlockedArtistInfo> =
        (dao.getBlockedArtistsForDisplay() + dao.getUnnamedBlockedArtists())
            .sortedByDescending { it.blockedAt }
            .map {
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

    override suspend fun getSuppressedTrackKeys(): Set<String> =
        dao.getSuppressedTrackIdentities().mapNotNullTo(mutableSetOf()) { row ->
            trackIdentityKey(row.artistName, row.trackName).takeIf { it.isNotBlank() }
        }

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
                // Link the two as well, not just store them side by side. Without
                // this a track first seen through feedback had a row in `tracks` and
                // a row in `artists` but nothing joining them, so nothing could name
                // its artist afterwards: measured on a real library, 15 of 22
                // disliked tracks had no artist link, which is exactly the set a
                // recording-level rejection cannot recognise elsewhere.
                dao.insertTrackArtist(TrackArtistEntity(trackUri = trackKey, artistUri = artistUri))
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
