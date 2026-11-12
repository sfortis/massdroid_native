package net.asksakis.massdroidv2.data.genre

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.TrackGenreEntity
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.musicbrainz.GenreOutcome
import net.asksakis.massdroidv2.data.musicbrainz.GenreResolution
import net.asksakis.massdroidv2.data.musicbrainz.IdentityEvidence
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import net.asksakis.massdroidv2.domain.recommendation.genreKey
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-off repair of genres that came from a namesake on MusicBrainz.
 *
 * Until 2026-11 a name search preferred the candidate spelled exactly like the
 * artist, and a known MBID was not used for the lookup. Both gave artists another
 * artist's genres: the ambient project "Aes Dana" was stored as black metal, the
 * psy-chill "ASURA" as power metal, the library band "Wild" as power metal. Those
 * genres reached the artist, every track recorded with them, and through a shared
 * track the co-credited artists, and an ambient listener's Smart Mix filled with
 * metal.
 *
 * Every cached identity that can matter is asked again with the fixed lookup: the
 * name-keyed answers of artists played in the last 90 days, and the id-keyed
 * answers that describe a different MusicBrainz artist than their id. Only an
 * answer naming a DIFFERENT MusicBrainz artist changes anything: then the genres
 * the old artist brought are taken back and the right ones written, together with
 * the cache row, in one transaction. A re-check that identifies nobody (an ambiguous
 * name, no recording found) changes nothing, because "not identified now" is not
 * evidence that the old answer was wrong: the first run on the owner's phone took
 * correct genres from Aqua, Birdy and six others that way. Plays, and genres from
 * Music Assistant that the wrong artist did not bring, are not changed.
 *
 * It costs one to three MusicBrainz requests per artist, paced by the shared rate
 * limiter. The position is stored after each artist, so a run stopped by the app
 * closing or by MusicBrainz not answering continues from there on the next connect.
 */
@Singleton
class NamesakeGenreRepair @Inject constructor(
    private val dao: PlayHistoryDao,
    private val resolver: MusicBrainzGenreResolver,
    private val settingsRepository: SettingsRepository,
    private val transactions: TransactionRunner,
    private val genreSpellings: GenreSpellingResolver
) {

    @Suppress("TooGenericExceptionCaught")
    suspend fun runIfDue() {
        try {
            // Runs after the recording re-check of revision 1, which must have succeeded:
            // storing revision 2 over a failed revision 1 would skip that one for good.
            if (settingsRepository.musicBrainzIdentityRevision.first() != REVISION - 1) return
            val cursor = settingsRepository.musicBrainzRepairCursor.first()
            val since = System.currentTimeMillis() - PLAYED_WINDOW_MS
            val rows = (dao.getPlayedNameResolvedTags(since) + dao.getMismatchedIdTags())
                .distinctBy { it.artistName }
                .sortedBy { it.artistName }
                .filter { it.artistName > cursor }
            Log.d(TAG, "Namesake repair: ${rows.size} cached identities to re-check")
            var corrected = 0
            for (row in rows) {
                when (repairOrFail(row)) {
                    Repair.UNANSWERED -> if (!giveUpOn(row)) return
                    Repair.CORRECTED -> corrected++
                    Repair.UNCHANGED -> Unit
                }
                settingsRepository.setMusicBrainzRepairCursor(row.artistName)
                settingsRepository.setMusicBrainzRepairFailures(0)
            }
            settingsRepository.setMusicBrainzIdentityRevision(REVISION)
            settingsRepository.setMusicBrainzRepairCursor("")
            Log.d(TAG, "Namesake repair done: corrected $corrected of ${rows.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Namesake repair failed: ${e.message}")
        }
    }

    private enum class Repair { CORRECTED, UNCHANGED, UNANSWERED }

    /** A row whose repair throws counts as unanswered, so one bad row cannot end every run. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun repairOrFail(row: MusicBrainzArtistTagsEntity): Repair =
        try {
            repair(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Namesake repair of '${row.artistName}' failed: ${e.message}")
            Repair.UNANSWERED
        }

    /**
     * Whether to move past a row that got no answer. A run pauses at such a row and the next
     * connect asks it again; after [MAX_ROW_FAILURES] runs in a row on the same row it is
     * left as it is, because a row that always fails would otherwise stop the repair from
     * ever finishing. Leaving it changes nothing: the repair only acts on an answer. The
     * count belongs to the row it was taken on, so another row starts again from one.
     */
    private suspend fun giveUpOn(row: MusicBrainzArtistTagsEntity): Boolean {
        val sameRow = settingsRepository.musicBrainzRepairFailedRow.first() == row.artistName
        val failures = (if (sameRow) settingsRepository.musicBrainzRepairFailures.first() else 0) + 1
        if (failures < MAX_ROW_FAILURES) {
            settingsRepository.setMusicBrainzRepairFailedRow(row.artistName)
            settingsRepository.setMusicBrainzRepairFailures(failures)
            Log.d(TAG, "Namesake repair paused at '${row.artistName}': no answer ($failures of $MAX_ROW_FAILURES)")
            return false
        }
        Log.d(TAG, "Namesake repair: '${row.artistName}' left unchanged after $failures runs without an answer")
        return true
    }

    private suspend fun repair(row: MusicBrainzArtistTagsEntity): Repair {
        val idKeyed = MBID_KEY.matches(row.artistName)
        val artists = artistsOf(row.artistName, idKeyed)
        val uris = artists.map { it.uri }
        val knownMbid = if (idKeyed) row.artistName else artists.firstNotNullOfOrNull { it.mbid?.takeIf(String::isNotBlank) }
        val answer = resolver.recheck(
            cacheKey = row.artistName,
            artistName = artists.firstOrNull()?.name ?: row.artistName,
            mbid = knownMbid,
            trackHint = uris.takeIf { it.isNotEmpty() }?.let { dao.getSampleTrackName(it) }
        )
        if (answer.outcome == GenreOutcome.UNAVAILABLE) return Repair.UNANSWERED
        val newMbid = correctedIdentity(row.mbid, answer) ?: return Repair.UNCHANGED
        val oldGenres = row.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val wrong = misattributedGenres(row.mbid, oldGenres, answer).orEmpty()
        Log.d(
            TAG,
            "Namesake repair: '${row.artistName}' was ${row.mbid} $oldGenres, " +
                "now $newMbid ${answer.genres} by ${answer.evidence}"
        )
        val plan = if (uris.isEmpty()) null else planGenreChanges(uris, wrong, answer.genres)
        transactions.inTransaction {
            resolver.storeIdentity(row.artistName, newMbid, answer.genres)
            plan?.let { applyGenreChanges(it) }
        }
        resolver.announceIdentityChange(row.artistName)
        return Repair.CORRECTED
    }

    /**
     * The artists a cache row describes. An id-keyed row names the artists with that MBID,
     * plus every other uri of the same name: those share genres through
     * backfillArtistGenres, which would copy a removed genre straight back from them.
     */
    private suspend fun artistsOf(cacheKey: String, idKeyed: Boolean): List<ArtistEntity> {
        if (!idKeyed) return dao.getArtistsByLowercaseName(cacheKey)
        val byId = dao.getArtistsByMbid(cacheKey)
        val sameNames = byId.map { it.name.lowercase() }.distinct().flatMap { dao.getArtistsByLowercaseName(it) }
        return (byId + sameNames).distinctBy { it.uri }
    }

    private class GenreChanges(
        val uris: List<String>,
        val wrong: List<String>,
        val right: List<String>,
        val keptOnTracks: Set<String>,
        val removeFromCoCredited: Map<String, List<String>>
    )

    /**
     * Reads everything the write needs before the transaction. A co-credited artist keeps
     * a wrong genre that its own cached answer carries, and one with no cached answer is
     * not touched, because its genres then came from Music Assistant. The tracks keep the
     * genres any co-credited artist carries, since they may be its genres on that track.
     *
     * Each co-credited artist is taken with every uri of its name, like [artistsOf], because
     * backfillArtistGenres would copy a genre left on one of them straight back.
     */
    private suspend fun planGenreChanges(uris: List<String>, wrong: List<String>, right: List<String>): GenreChanges {
        val wrongStored = genreSpellings.spellingsToStore(wrong)
        val coCredited = dao.getCoCreditedArtists(uris).filter { it.uri !in uris }
        val removeFromCoCredited = mutableMapOf<String, List<String>>()
        val keptOnTracks = mutableSetOf<String>()
        coCredited.distinctBy { it.name.lowercase() }.forEach { artist ->
            val own = ownGenreKeys(artist) ?: return@forEach
            keptOnTracks += own
            val remove = wrongStored.filter { genreKey(it) !in own }
            if (remove.isEmpty()) return@forEach
            // The artist's own uri is taken directly: SQLite's lower() folds only ASCII, so
            // the name lookup finds nothing for a name like "Ólafur Arnalds".
            (listOf(artist) + dao.getArtistsByLowercaseName(artist.name.lowercase()))
                .filter { it.uri !in uris }
                .distinctBy { it.uri }
                .forEach { removeFromCoCredited[it.uri] = remove }
        }
        return GenreChanges(
            uris = uris,
            wrong = wrongStored,
            right = genreSpellings.spellingsToStore(right),
            keptOnTracks = keptOnTracks,
            removeFromCoCredited = removeFromCoCredited
        )
    }

    private suspend fun applyGenreChanges(changes: GenreChanges) {
        val fromTracks = changes.wrong.filter { genreKey(it) !in changes.keptOnTracks }
        if (fromTracks.isNotEmpty()) dao.deleteTrackGenresOfArtists(changes.uris, fromTracks)
        if (changes.wrong.isNotEmpty()) changes.uris.forEach { dao.deleteArtistGenresNamed(it, changes.wrong) }
        changes.removeFromCoCredited.forEach { (uri, genres) -> dao.deleteArtistGenresNamed(uri, genres) }
        if (changes.right.isEmpty()) return
        val trackUris = dao.getTrackUrisOfArtists(changes.uris)
        changes.right.forEach { genre ->
            dao.insertGenre(GenreEntity(name = genre))
            changes.uris.forEach { dao.insertArtistGenre(ArtistGenreEntity(artistUri = it, genreName = genre)) }
            trackUris.forEach { dao.insertTrackGenre(TrackGenreEntity(trackUri = it, genreName = genre)) }
        }
    }

    /** Genre keys of the artist's own cached answer, or null when it has none. */
    private suspend fun ownGenreKeys(artist: ArtistEntity): Set<String>? =
        resolver.cachedGenres(artist.name, artist.mbid)?.mapTo(mutableSetOf(), ::genreKey)

    private companion object {
        const val TAG = "GenreEnricher"

        /** Stored in the identity revision shared with the recording re-check (revision 1). */
        const val REVISION = 2

        /** Plays older than this barely move any score, and their cache rows expire within it. */
        const val PLAYED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        /** Runs in a row without an answer for one row before the repair moves past it. */
        const val MAX_ROW_FAILURES = 3

        val MBID_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/**
 * The MBID a re-check corrects the cached answer to, or null when it corrects nothing.
 * Only an id or a recording is evidence enough to overrule a cached answer: a name alone
 * picks the best-scoring namesake, so for the Reunion Island producer Labelle, whose
 * recording the search missed, it would write the US group LaBelle's disco and soul over
 * the right genres. A re-check that identifies nobody, or the same artist, corrects
 * nothing either. Decided by the owner on 2026-11-12.
 */
internal fun correctedIdentity(cachedMbid: String, recheck: GenreResolution): String? {
    if (recheck.outcome == GenreOutcome.UNAVAILABLE) return null
    if (recheck.evidence != IdentityEvidence.MBID && recheck.evidence != IdentityEvidence.RECORDING) return null
    return recheck.mbid?.takeIf { it != cachedMbid }
}

/**
 * The genres a re-check takes back from an artist: the ones the cached answer gave that
 * the new answer does not. Null when MusicBrainz did not answer, so nothing may be
 * concluded yet. Empty unless [correctedIdentity] names a different artist.
 */
internal fun misattributedGenres(cachedMbid: String, cachedGenres: List<String>, recheck: GenreResolution): List<String>? {
    if (recheck.outcome == GenreOutcome.UNAVAILABLE) return null
    if (correctedIdentity(cachedMbid, recheck) == null) return emptyList()
    val kept = recheck.genres.mapTo(mutableSetOf(), ::genreKey)
    return cachedGenres.filter { genreKey(it) !in kept }
}
