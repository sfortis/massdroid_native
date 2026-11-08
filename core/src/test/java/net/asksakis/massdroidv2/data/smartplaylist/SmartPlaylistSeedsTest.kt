package net.asksakis.massdroidv2.data.smartplaylist

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.smartplaylist.SmartPlaylistSeeds.AddResult
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeedKind
import org.junit.Test

/**
 * A seed goes into a smart playlist's rules without touching anything else in them, at most
 * ten seeds across the four lists, and never twice.
 */
class SmartPlaylistSeedsTest {

    private fun rules(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val track = SmartPlaylistSeed(SmartPlaylistSeedKind.TRACK, "library://track/7", "Miracle")
    private val artist = SmartPlaylistSeed(SmartPlaylistSeedKind.ARTIST, "library://artist/59", "WhoMadeWho")

    private val seeded = rules(
        """
        {"seed_track_uris": ["library://track/1"], "seed_artist_uris": [],
         "seed_names": {"library://track/1": "First"},
         "limit": 50, "exclude_recently_played_days": 7, "a_field_from_a_newer_server": {"x": 1}}
        """
    )

    private fun added(result: AddResult): JsonObject = (result as AddResult.Added).rules

    /** update_rules replaces the whole object, so a dropped key is a setting erased on the server. */
    @Test
    fun `every key the app does not edit is written back unchanged`() {
        val out = added(SmartPlaylistSeeds.addSeed(seeded, track))

        assertThat(out["limit"]).isEqualTo(seeded["limit"])
        assertThat(out["exclude_recently_played_days"]).isEqualTo(seeded["exclude_recently_played_days"])
        assertThat(out["a_field_from_a_newer_server"]).isEqualTo(seeded["a_field_from_a_newer_server"])
        assertThat(out["seed_artist_uris"]).isEqualTo(seeded["seed_artist_uris"])
    }

    @Test
    fun `a track is appended to the track seeds and named`() {
        val out = added(SmartPlaylistSeeds.addSeed(seeded, track))

        assertThat(out["seed_track_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("library://track/1", "library://track/7").inOrder()
        val names = out["seed_names"]!!.jsonObject
        assertThat(names["library://track/1"]!!.jsonPrimitive.content).isEqualTo("First")
        assertThat(names["library://track/7"]!!.jsonPrimitive.content).isEqualTo("Miracle")
    }

    @Test
    fun `an artist goes to the artist seeds`() {
        val out = added(SmartPlaylistSeeds.addSeed(seeded, artist))

        assertThat(out["seed_artist_uris"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("library://artist/59")
        assertThat(out["seed_track_uris"]).isEqualTo(seeded["seed_track_uris"])
    }

    @Test
    fun `a seed already in any list is not added again`() {
        val asAlbumSeed = rules("""{"seed_album_uris": ["library://track/7"]}""")

        assertThat(SmartPlaylistSeeds.addSeed(asAlbumSeed, track)).isEqualTo(AddResult.AlreadyPresent)
    }

    /** The plugin's MAX_SEEDS counts all four lists together. */
    @Test
    fun `ten seeds across the lists make the playlist full`() {
        val ten = rules(
            """
            {"seed_track_uris": ["t1", "t2", "t3", "t4"], "seed_artist_uris": ["a1", "a2", "a3"],
             "seed_album_uris": ["b1", "b2"], "seed_playlist_uris": ["p1"]}
            """
        )
        val nine = rules("""{"seed_track_uris": ["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"]}""")

        assertThat(SmartPlaylistSeeds.addSeed(ten, track)).isEqualTo(AddResult.Full)
        assertThat(SmartPlaylistSeeds.addSeed(nine, track)).isInstanceOf(AddResult.Added::class.java)
    }

    /** A seed would make the plugin drop these filters, so such a playlist is never offered. */
    @Test
    fun `a filter-based playlist is not seed-based`() {
        val filters = rules("""{"genres": ["House"], "seed_track_uris": [], "seed_names": {}}""")

        assertThat(SmartPlaylistSeeds.isSeedBased(filters)).isFalse()
        assertThat(SmartPlaylistSeeds.isSeedBased(seeded)).isTrue()
        assertThat(SmartPlaylistSeeds.seedCount(seeded)).isEqualTo(1)
    }

    @Test
    fun `a new playlist starts from its one seed`() {
        val out = SmartPlaylistSeeds.newRules(artist)

        assertThat(out.keys).containsExactly("seed_artist_uris", "seed_names")
        assertThat(out["seed_artist_uris"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("library://artist/59")
        assertThat(out["seed_names"]!!.jsonObject["library://artist/59"]!!.jsonPrimitive.content).isEqualTo("WhoMadeWho")
    }

    /** The plugin counts all_seed_uris(), which drops blanks and repeats; a raw count refused real room. */
    @Test
    fun `repeated and blank entries do not count toward the ten`() {
        val padded = rules(
            """
            {"seed_track_uris": ["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t1", ""]}
            """
        )

        assertThat(SmartPlaylistSeeds.seedCount(padded)).isEqualTo(9)
        assertThat(SmartPlaylistSeeds.addSeed(padded, track)).isInstanceOf(AddResult.Added::class.java)
    }
}
