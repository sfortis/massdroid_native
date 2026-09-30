package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the server answers to a search against what the app then shows.
 *
 * The answers are real ones, captured from a live MA server with the arguments
 * `MusicRepositoryImpl.search` sends, and replaying them catches the failure that reached a
 * reporter in #77: the parsing drops an item it cannot read and logs it, so the app quietly
 * shows fewer results than the server found, and nothing about the screen says so.
 *
 * The captures are several MB and describe one server at one moment, so they are not kept in
 * the repo. Take them with the `search-parity.py` helper alongside the other local scripts,
 * which writes them to `core/src/test/resources/search`. Without them this test reports
 * itself as skipped rather than as passing, because a comparison with nothing to compare is
 * not a result.
 *
 * A difference is not automatically a bug. A track the server marks `is_playable: false` is
 * dropped on purpose. The test asserts that the count of dropped items equals the count of
 * items there is a stated reason to drop, so anything dropped for any other reason fails.
 */
class SearchParityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** The response keys, paired with the uris [SearchResult] ends up holding for each. */
    private val keys = listOf(
        "artists" to { r: SearchResult -> r.artists.map { it.uri } },
        "albums" to { r: SearchResult -> r.albums.map { it.uri } },
        "tracks" to { r: SearchResult -> r.tracks.map { it.uri } },
        "playlists" to { r: SearchResult -> r.playlists.map { it.uri } },
        "radio" to { r: SearchResult -> r.radios.map { it.uri } },
        "audiobooks" to { r: SearchResult -> r.audiobooks.map { it.uri } },
        "podcasts" to { r: SearchResult -> r.podcasts.map { it.uri } }
    )

    private fun resourceOrNull(name: String): String? =
        javaClass.getResourceAsStream("/search/$name")?.bufferedReader()?.use { it.readText() }

    private fun resource(name: String): String =
        requireNotNull(resourceOrNull(name)) { "Missing capture /search/$name" }

    private fun repository(answer: JsonElement?, capturedArgs: kotlin.collections.MutableList<JsonObject?>? = null):
        MusicRepositoryImpl {
        // Relaxed because the repository subscribes to the event flow when it is built;
        // nothing in the search path reads it.
        val ws = mockk<MaWebSocketClient>(relaxed = true)
        every { ws.externalServerUrl() } returns "https://mass.asksakis.net"
        every { ws.serverSchemaVersion() } returns 65
        every { ws.isOffLanImageHost(any()) } returns false
        val args = slot<JsonObject>()
        coEvery {
            ws.sendCommand(any(), capture(args), any(), any(), any())
        } answers {
            capturedArgs?.add(args.captured)
            answer
        }
        val playerRepository = mockk<PlayerRepository>(relaxed = true)
        return MusicRepositoryImpl(
            wsClient = ws,
            imageResolver = ImageUrlResolver(ws),
            json = json,
            playerRepository = Lazy { playerRepository }
        )
    }

    /**
     * The uris the server sent under [key], in the order it sent them, leaving out the ones
     * the app is meant to drop: a track the server itself says cannot be played.
     */
    private fun expectedUris(answer: JsonObject, key: String): List<String> =
        (answer[key] as? JsonArray)
            .orEmpty()
            .map { it.jsonObject }
            .filterNot { it["is_playable"]?.jsonPrimitive?.content == "false" }
            .map { it["uri"]?.jsonPrimitive?.content.orEmpty() }

    @Test
    fun `every captured search reaches the app with the items the server sent`() = runTest {
        val captures = resourceOrNull("index.json")
        assumeTrue("No captures; run the search-parity helper against a live server", captures != null)
        val index = json.parseToJsonElement(captures!!).jsonArray
        assertThat(index).isNotEmpty()

        val differences = mutableListOf<String>()
        index.forEach { entry ->
            val capture = entry.jsonObject
            val query = capture["query"]!!.jsonPrimitive.content
            val answer = json.parseToJsonElement(resource(capture["file"]!!.jsonPrimitive.content)).jsonObject
            val serverCounts = capture["serverCounts"]!!.jsonObject

            // A capture either came from the first call the screen makes, or from the deeper
            // one it makes per media type afterwards; both are replayed as they were asked.
            val mediaType = capture["mediaType"]?.jsonPrimitive?.content?.let { MediaType.fromApi(it) }
            val limit = capture["limit"]?.jsonPrimitive?.int
            val result = if (mediaType != null && limit != null) {
                repository(answer).search(query, listOf(mediaType), limit)
            } else {
                repository(answer).search(query)
            }
            keys.forEach { (key, uris) ->
                val expected = expectedUris(answer, key)
                val shown = uris(result)
                if (shown != expected) {
                    val sent = serverCounts[key]!!.jsonPrimitive.int
                    val missing = expected - shown.toSet()
                    differences += "\"$query\" $key: server sent $sent, app kept ${shown.size} " +
                        "(${expected.size} playable); missing ${missing.take(3)}"
                }
            }
        }
        assertThat(differences).isEmpty()
    }

    @Test
    fun `the audiobook in the library arrives with the author the server gave it`() = runTest {
        val answer = resourceOrNull("alice-in-wonderland.json")
        assumeTrue("No captures; run the search-parity helper against a live server", answer != null)

        val result = repository(json.parseToJsonElement(answer!!).jsonObject)
            .search("alice in wonderland")

        // Named rather than counted, because the count alone passed while #77 was open: the
        // whole audiobook list was empty and the server had sent one.
        val book = result.audiobooks.single()
        assertThat(book.name).isEqualTo("Alice in Wonderland")
        assertThat(book.authors).containsExactly("Lewis Carroll")
    }

    @Test
    fun `the search the app sends asks for every searchable type`() = runTest {
        val sent = mutableListOf<JsonObject?>()
        repository(JsonObject(emptyMap()), sent).search("anything")

        val args = sent.single()!!
        assertThat(args["search_query"]!!.jsonPrimitive.content).isEqualTo("anything")
        assertThat(args["limit"]!!.jsonPrimitive.int).isEqualTo(25)
        assertThat(args["media_types"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("artist", "album", "track", "playlist", "radio", "audiobook", "podcast")
    }
}
