package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * What the resolver is allowed to write down after an empty-handed lookup.
 *
 * An empty cache entry silences an artist for a fortnight, so it must only be
 * written when MusicBrainz actually said there was nothing. A rate-limited or
 * failed request is not an answer, and recording one as "this artist has no
 * genres" removed the artist from every mix built in that window: two days of
 * field logs held 38 such give-ups against 88 real answers.
 */
class MusicBrainzGenreResolverTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)

    // No waiting in a unit test: the real limiter paces requests 1.5s apart and
    // backs off ten seconds on a 503, which is correct in the field and pointless
    // here, where nothing is actually being asked.
    private val rateLimiter = mockk<MusicBrainzRateLimiter>(relaxed = true)

    private fun resolverAnswering(code: Int, body: String): MusicBrainzGenreResolver {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Service Unavailable")
                    .body(body.toResponseBody())
                    .build()
            }
        }
        return MusicBrainzGenreResolver(dao, client, Json { ignoreUnknownKeys = true }, rateLimiter)
    }

    @Test
    fun `a rate-limited lookup is not remembered as an artist without genres`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val resolver = resolverAnswering(code = 503, body = "")

        val genres = resolver.resolve("Some Obscure Producer")

        assertThat(genres).isEmpty()
        // The point of the test: nothing is written, so the next enrichment run
        // asks again instead of treating the outage as a fact for fourteen days.
        coVerify(exactly = 0) { dao.upsertMusicBrainzTags(any()) }
    }

    @Test
    fun `an artist MusicBrainz does not know is remembered as such`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val resolver = resolverAnswering(code = 200, body = """{"artists":[]}""")

        val genres = resolver.resolve("Nobody At All")

        assertThat(genres).isEmpty()
        // This one is an answer, and caching it saves asking again every build.
        coVerify(exactly = 1) {
            dao.upsertMusicBrainzTags(match<MusicBrainzArtistTagsEntity> { it.tags.isEmpty() })
        }
    }

    /** Answers in order, one per call, the last one repeating. */
    private fun resolverAnsweringInTurn(vararg answers: Pair<Int, String>): MusicBrainzGenreResolver {
        val client = mockk<OkHttpClient>()
        var index = 0
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val (code, body) = answers[minOf(index, answers.size - 1)]
            index++
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Service Unavailable")
                    .body(body.toResponseBody())
                    .build()
            }
        }
        return MusicBrainzGenreResolver(dao, client, Json { ignoreUnknownKeys = true }, rateLimiter)
    }

    private val knownArtist = """{"artists":[{"id":"11111111-1111-1111-1111-111111111111","name":"Gare Du Nord","score":100}]}"""

    @Test
    fun `a 503 that clears on a later retry still yields genres`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        // The name search fails twice and passes on the third try; the genre fetch passes at once.
        val resolver = resolverAnsweringInTurn(
            503 to "", 503 to "", 200 to knownArtist,
            200 to """{"genres":[{"name":"jazz","count":5},{"name":"lounge","count":2}]}"""
        )

        val answer = resolver.resolveDetailed("Gare Du Nord")

        assertThat(answer.outcome).isEqualTo(GenreOutcome.GENRES)
        assertThat(answer.genres).containsExactly("jazz", "lounge").inOrder()
        coVerify(exactly = 2) { rateLimiter.backOff(any()) }
        coVerify(atLeast = 1) { rateLimiter.noteSuccess() }
    }

    @Test
    fun `the three empties are told apart`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags(any()) } returns null

        val noTags = resolverAnsweringInTurn(200 to knownArtist, 200 to """{"genres":[]}""")
            .resolveDetailed("Gare Du Nord")
        val notFound = resolverAnswering(200, """{"artists":[]}""").resolveDetailed("Nobody At All")
        val unavailable = resolverAnswering(503, "").resolveDetailed("Some Obscure Producer")

        assertThat(noTags.outcome).isEqualTo(GenreOutcome.NO_GENRES)
        assertThat(notFound.outcome).isEqualTo(GenreOutcome.NOT_FOUND)
        assertThat(unavailable.outcome).isEqualTo(GenreOutcome.UNAVAILABLE)
        assertThat(listOf(noTags, notFound, unavailable).map { it.genres }).containsExactly(emptyList<String>(), emptyList<String>(), emptyList<String>())
    }
}
