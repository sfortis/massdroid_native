package net.asksakis.massdroidv2.data.database

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer

/**
 * Schema 20 to 21: merges the spellings of one genre into a single name.
 *
 * Genre names were stored lowercased and trimmed only, so "synthpop", "synth-pop"
 * and "synth pop" were three genres in every table. On a real library on
 * 2026-10-05 that split 518 names over 508 genres, the largest being synthpop
 * (926 track rows, 183 artist rows), synth-pop (193 and 123) and synth pop (33
 * and 9). The app now treats two names as one genre when Music Assistant does
 * (see `genreKey`), and this migration brings the stored rows in line, so the
 * database holds one spelling per genre from here on.
 *
 * Kept in its own file rather than inline in `AppModule` because it is the only
 * migration that reads data and decides in Kotlin, and its planning step is a
 * pure function that the unit tests exercise directly. There is no instrumented
 * or Robolectric test in this project, so the SQL below is not run by any test.
 *
 * Only `genres`, `track_genres` and `artist_genres` are rewritten. The other
 * places a genre name appears are left on purpose:
 * - `musicbrainz_artist_tags.tags` and `ma_similar_artists.similar_genres` are
 *   comma lists caching what MusicBrainz and the server answered. They expire on
 *   their own, every reader now compares their names by key, and rewriting them
 *   would make the cache disagree with its source.
 * - `artist_track_cache.tracks_json` and `ma_similar_track_cache.tracks_json` are
 *   serialized server tracks with a 14-day lifetime, read the same way.
 * - The DataStore cool-down windows (`recent_mix_genres`,
 *   `recent_seed_cluster_genres`) and the Discover cache file are not in this
 *   database. Their readers compare by key, so an old spelling still matches.
 */
internal object GenreSpellingMigration {

    private const val TAG = "GenreMigration"

    /** One stored genre name and how many rows use it. */
    @VisibleForTesting
    internal data class GenreUsage(val name: String, val trackRows: Int, val artistRows: Int)

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_KEY_CHARS = Regex("[^a-z0-9]")

    /**
     * The genre key as schema 21 defined it. A frozen copy of the live `genreKey`
     * on purpose: a later change to that rule must not change what this migration
     * did to a database that already ran it, nor what it does to one that runs it
     * late. Music Assistant's `create_safe_string(..., replace_space=True)`:
     * lowercase, trim, strip accents, keep [a-z0-9]; the lowercased name when
     * nothing is left.
     */
    @VisibleForTesting
    internal fun frozenGenreKey(name: String): String {
        val lowered = name.trim().lowercase()
        val unaccented = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
        return unaccented.replace(NON_KEY_CHARS, "").ifEmpty { lowered }
    }

    /**
     * Which stored names to fold into which, as old name to surviving name.
     *
     * Names are grouped by [frozenGenreKey]. In a group of several, the name with
     * the most rows (track plus artist) survives, because it is the spelling most
     * of the library already carries; a tie goes to the lexically smallest name,
     * so the result never depends on row order. Groups of one are left alone and
     * do not appear in the result.
     */
    @VisibleForTesting
    internal fun planMerges(usage: List<GenreUsage>): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        usage
            .filter { it.name.isNotBlank() }
            .groupBy { frozenGenreKey(it.name) }
            .values
            .filter { it.size > 1 }
            .forEach { spellings ->
                val survivor = spellings.minWith(
                    compareByDescending<GenreUsage> { it.trackRows + it.artistRows }.thenBy { it.name }
                ).name
                spellings.filter { it.name != survivor }.forEach { renames[it.name] = survivor }
            }
        return renames
    }

    /**
     * Every name found in any of the three tables, with its row counts. Read from
     * the link tables too, not only `genres`, so a link whose `genres` row is
     * missing (foreign keys are not enforced while a migration runs) is still
     * counted and merged.
     */
    private const val USAGE_QUERY = """
        SELECT `name`, SUM(`t`), SUM(`a`) FROM (
            SELECT `name` AS `name`, 0 AS `t`, 0 AS `a` FROM `genres`
            UNION ALL SELECT `genre_name`, 1, 0 FROM `track_genres`
            UNION ALL SELECT `genre_name`, 0, 1 FROM `artist_genres`
        ) GROUP BY `name`
    """

    val MIGRATION_20_21: Migration = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val usage = mutableListOf<GenreUsage>()
            db.query(USAGE_QUERY).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    usage += GenreUsage(name, cursor.getInt(1), cursor.getInt(2))
                }
            }
            val renames = planMerges(usage)
            for ((from, to) in renames) {
                // The surviving name first, so the rewritten links have a parent.
                db.execSQL("INSERT OR IGNORE INTO `genres` (`name`) VALUES (?)", arrayOf<Any>(to))
                // Copy, then delete: a track or artist that carries both spellings
                // already has the surviving pair, and an UPDATE would collide with
                // it on the primary key. INSERT OR IGNORE keeps exactly one.
                db.execSQL(
                    "INSERT OR IGNORE INTO `track_genres` (`track_uri`, `genre_name`) " +
                        "SELECT `track_uri`, ? FROM `track_genres` WHERE `genre_name` = ?",
                    arrayOf<Any>(to, from)
                )
                db.execSQL("DELETE FROM `track_genres` WHERE `genre_name` = ?", arrayOf<Any>(from))
                db.execSQL(
                    "INSERT OR IGNORE INTO `artist_genres` (`artist_uri`, `genre_name`) " +
                        "SELECT `artist_uri`, ? FROM `artist_genres` WHERE `genre_name` = ?",
                    arrayOf<Any>(to, from)
                )
                db.execSQL("DELETE FROM `artist_genres` WHERE `genre_name` = ?", arrayOf<Any>(from))
                db.execSQL("DELETE FROM `genres` WHERE `name` = ?", arrayOf<Any>(from))
            }
            Log.i(TAG, "Merged ${renames.size} genre spellings into ${renames.values.toSet().size} genres")
        }
    }
}
