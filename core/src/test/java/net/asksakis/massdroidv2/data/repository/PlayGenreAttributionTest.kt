package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Test

/**
 * A play is recorded with the genres resolved here, on the track and on its primary artist.
 * A guest's genres must not land there: Gunship collected Dave Lombardo's death metal from
 * one song they share, and Smart Mix then treated Gunship as a metal act.
 */
class PlayGenreAttributionTest {

    private val wsClient = mockk<MaWebSocketClient>(relaxed = true)
    private val resolver = mockk<MusicBrainzGenreResolver>(relaxed = true) {
        every { identityChanges } returns MutableSharedFlow()
    }

    private fun repository(): PlayerRepositoryImpl {
        every { wsClient.events } returns MutableSharedFlow()
        every { wsClient.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { smartListeningEnabled } returns MutableStateFlow(false)
        }
        val smartListening = mockk<SmartListeningRepository>(relaxed = true) {
            every { blockedArtistUris } returns MutableStateFlow(emptySet())
        }
        val eventBus = mockk<SessionEventBus>(relaxed = true) {
            every { resets } returns MutableSharedFlow()
        }
        return PlayerRepositoryImpl(
            wsClient = wsClient,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json { ignoreUnknownKeys = true },
            playHistoryRepository = mockk<PlayHistoryRepository>(relaxed = true),
            settingsRepository = settings,
            smartListeningRepository = smartListening,
            musicBrainzGenreResolver = resolver,
            sessionEventBus = eventBus,
            queueItemsCoordinator = mockk(relaxed = true),
        )
    }

    private fun musicBrainz(name: String, vararg genres: String) {
        coEvery { resolver.resolve(name, any(), any()) } returns genres.toList()
    }

    private fun track(vararg providerGenres: String) = Track(
        itemId = "1",
        provider = "deezer",
        name = "Monster in Paradise",
        uri = "deezer://track/1",
        artistItemId = "10",
        artistProvider = "deezer",
        artistUri = "deezer://artist/10",
        genres = providerGenres.toList(),
    )

    private val gunshipWithGuest = listOf(
        "deezer://artist/10" to "Gunship",
        "deezer://artist/20" to "Dave Lombardo",
    )

    @Test
    fun `a guest's MusicBrainz genres do not join the primary artist's`() = runBlocking<Unit> {
        musicBrainz("Gunship", "synthwave", "electronic")
        musicBrainz("Dave Lombardo", "death metal", "groove metal")

        val genres = repository().enrichTrackGenresForHistory(track(), gunshipWithGuest).genres

        assertThat(genres).containsExactly("synthwave", "electronic")
    }

    @Test
    fun `a primary artist unknown to MusicBrainz keeps the track's own genres, not the guest's`() = runBlocking<Unit> {
        musicBrainz("Gunship")
        musicBrainz("Dave Lombardo", "death metal", "groove metal")

        val genres = repository().enrichTrackGenresForHistory(track("synthwave"), gunshipWithGuest).genres

        assertThat(genres).containsExactly("synthwave")
    }

    @Test
    fun `the Music Assistant artist fallback asks only for the primary artist`() = runBlocking<Unit> {
        musicBrainz("Gunship")
        musicBrainz("Dave Lombardo")
        coEvery { wsClient.sendCommand("music/tracks/get", any(), any(), any(), any()) } returns
            buildJsonObject {
                put("item_id", "1")
                put("provider", "deezer")
                put("name", "Monster in Paradise")
                put("uri", "deezer://track/1")
                putJsonArray("artists") {
                    addArtist("10", "Gunship")
                    addArtist("20", "Dave Lombardo")
                }
            }
        coEvery { wsClient.sendCommand("music/artists/get", any(), any(), any(), any()) } answers {
            val itemId = secondArg<JsonObject?>()?.get("item_id")?.jsonPrimitive?.content
            artist(itemId.orEmpty(), if (itemId == "20") "death metal" else "synthwave")
        }

        val genres = repository().enrichTrackGenresForHistory(track(), gunshipWithGuest).genres

        assertThat(genres).containsExactly("synthwave")
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addArtist(itemId: String, name: String) =
        addJsonObject {
            put("item_id", itemId)
            put("provider", "deezer")
            put("name", name)
            put("uri", "deezer://artist/$itemId")
        }

    private fun artist(itemId: String, genre: String) = buildJsonObject {
        put("item_id", itemId)
        put("provider", "deezer")
        put("name", itemId)
        put("uri", "deezer://artist/$itemId")
        putJsonObject("metadata") { putJsonArray("genres") { add(genre) } }
    }
}
