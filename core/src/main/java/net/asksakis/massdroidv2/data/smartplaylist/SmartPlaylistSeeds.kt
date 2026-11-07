package net.asksakis.massdroidv2.data.smartplaylist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.asksakis.massdroidv2.domain.model.SMART_PLAYLIST_MAX_SEEDS
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeedKind

private const val SEED_NAMES = "seed_names"
private const val SEED_TRACKS = "seed_track_uris"
private const val SEED_ARTISTS = "seed_artist_uris"
private val SEED_LISTS = listOf(SEED_TRACKS, SEED_ARTISTS, "seed_album_uris", "seed_playlist_uris")

/**
 * Seed edits on the rules of a Music Assistant smart playlist.
 *
 * The rules stay the raw object the server sent, and an edit touches only the one seed list
 * and `seed_names`: `update_rules` replaces the whole object, so a typed copy that skipped a
 * field it does not know would erase that field on the server.
 *
 * Only a seed-based playlist may take a seed. As soon as any seed is present the plugin
 * ignores the artist, album, genre and folder filters, so a seed added to a filter-based
 * playlist would silently turn it into a different playlist.
 */
object SmartPlaylistSeeds {

    sealed interface AddResult {
        data class Added(val rules: JsonObject) : AddResult
        data object AlreadyPresent : AddResult
        data object Full : AddResult
    }

    fun seedCount(rules: JsonObject): Int = seeds(rules).size

    fun isSeedBased(rules: JsonObject): Boolean = seedCount(rules) > 0

    fun addSeed(rules: JsonObject, seed: SmartPlaylistSeed): AddResult {
        val present = seeds(rules)
        if (seed.uri in present) return AddResult.AlreadyPresent
        if (present.size >= SMART_PLAYLIST_MAX_SEEDS) return AddResult.Full
        val listKey = listKey(seed.kind)
        val list = rules[listKey] as? JsonArray ?: JsonArray(emptyList())
        val names = rules[SEED_NAMES] as? JsonObject ?: JsonObject(emptyMap())
        return AddResult.Added(
            JsonObject(
                rules + mapOf(
                    listKey to JsonArray(list + JsonPrimitive(seed.uri)),
                    SEED_NAMES to JsonObject(names + (seed.uri to JsonPrimitive(seed.name)))
                )
            )
        )
    }

    /** The rules of a new playlist that starts from [seed] alone; the server fills in the rest. */
    fun newRules(seed: SmartPlaylistSeed): JsonObject = JsonObject(
        mapOf(
            listKey(seed.kind) to JsonArray(listOf(JsonPrimitive(seed.uri))),
            SEED_NAMES to JsonObject(mapOf(seed.uri to JsonPrimitive(seed.name)))
        )
    )

    private fun listKey(kind: SmartPlaylistSeedKind): String = when (kind) {
        SmartPlaylistSeedKind.TRACK -> SEED_TRACKS
        SmartPlaylistSeedKind.ARTIST -> SEED_ARTISTS
    }

    /** Every seed across the four lists the way the plugin counts them: no blanks, no repeats. */
    private fun seeds(rules: JsonObject): List<String> =
        SEED_LISTS.flatMap { uris(rules, it) }.filter { it.isNotEmpty() }.distinct()

    private fun uris(rules: JsonObject, key: String): List<String> =
        (rules[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
