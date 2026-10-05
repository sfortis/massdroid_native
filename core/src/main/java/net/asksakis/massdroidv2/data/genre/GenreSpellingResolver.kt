package net.asksakis.massdroidv2.data.genre

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.recommendation.canonicalGenreSpellings
import net.asksakis.massdroidv2.domain.recommendation.genreKey
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides how a genre name is spelled in the database, so each genre is stored
 * under one name.
 *
 * Genres reach the database from several sources that spell them differently:
 * Music Assistant track, album and artist metadata, and MusicBrainz tags. Stored
 * as they came, one genre ended up under several names. On a real library on
 * 2026-10-05, synthpop, synth-pop and synth pop were three genres with 926, 193
 * and 33 track rows. Schema 21 merged the existing rows; this keeps new ones
 * merged. Two names are the same genre when their [genreKey] is equal, which is
 * the rule Music Assistant itself applies.
 *
 * Every write of a genre name goes through [spellingToStore]. A name whose key is
 * already known is stored under the spelling the database has for that key, and
 * a new key is stored as it arrived (normalized) and becomes the spelling for
 * later writes. Reads that look a row up by name go through [spellingToQuery].
 *
 * The key to spelling map is loaded from the database once per process and then
 * kept in memory. It is never invalidated, and does not need to be: a stale entry
 * only means a spelling that is no longer in the table is used again, and every
 * writer inserts the `genres` row (insert-or-ignore) before linking to it.
 *
 * Call it before opening a transaction, not inside one: the first call reads the
 * database under a lock.
 */
@Singleton
class GenreSpellingResolver @Inject constructor(
    private val dao: PlayHistoryDao
) {
    private val loadLock = Mutex()

    @Volatile
    private var spellingByKey: ConcurrentHashMap<String, String>? = null

    /**
     * The name to write for [raw], or null when it is blank. Registers the key on
     * first sight, atomically, so two sources writing "synth-pop" and "synth pop"
     * at the same moment still agree on one spelling.
     */
    suspend fun spellingToStore(raw: String): String? {
        val normalized = normalizeGenre(raw)
        if (normalized.isEmpty()) return null
        return spellings().putIfAbsent(genreKey(normalized), normalized) ?: normalized
    }

    /** [spellingToStore] for a list, one entry per genre, in input order. */
    suspend fun spellingsToStore(raw: Iterable<String>): List<String> =
        raw.mapNotNull { spellingToStore(it) }.distinct()

    /**
     * The stored spelling of [raw], for a query that matches `genre_name` exactly.
     * Falls back to the normalized input when the genre is unknown, and does not
     * register it, since a lookup must not decide how a later write is spelled.
     *
     * This is what lets a name from outside the database still find its rows: an
     * Android Auto browse id, a Discover tile cached before schema 21, or a
     * provider genre.
     */
    suspend fun spellingToQuery(raw: String): String {
        val normalized = normalizeGenre(raw)
        return spellings()[genreKey(normalized)] ?: normalized
    }

    private suspend fun spellings(): ConcurrentHashMap<String, String> {
        spellingByKey?.let { return it }
        return loadLock.withLock {
            spellingByKey ?: ConcurrentHashMap(
                // After schema 21 every key has one stored spelling. The choice
                // only matters if a duplicate ever slips back in, and then it
                // follows the same rule as the migration: the most used wins.
                canonicalGenreSpellings(dao.getGenreUsage().associate { it.name to it.uses })
            ).also { spellingByKey = it }
        }
    }
}
