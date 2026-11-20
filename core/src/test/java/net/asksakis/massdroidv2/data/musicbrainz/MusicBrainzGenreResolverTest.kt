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
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    /** Records each requested URL and answers by path: genres for an artist lookup, [search] for a search. */
    private fun resolverRecording(urls: MutableList<String>, search: String, genres: String): MusicBrainzGenreResolver {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val url = request.url.toString()
            urls += url
            val body = if ("inc=genres" in url) genres else search
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }
        }
        return MusicBrainzGenreResolver(dao, client, Json { ignoreUnknownKeys = true }, rateLimiter)
    }

    /** "Wild" had its own id and still got a power metal namesake's genres from a name search. */
    @Test
    fun `a known MBID is looked up directly, never by name`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        val namesake = """{"artists":[{"id":"99999999-9999-9999-9999-999999999999","name":"Wild","score":100}]}"""
        val resolver = resolverRecording(urls, namesake, """{"genres":[{"name":"indie pop","count":3}]}""")

        val answer = resolver.resolveDetailed("Wild", mbid = "22222222-2222-2222-2222-222222222222")

        assertThat(answer.genres).containsExactly("indie pop")
        assertThat(answer.mbid).isEqualTo("22222222-2222-2222-2222-222222222222")
        assertThat(urls).hasSize(1)
        assertThat(urls.single()).contains("artist/22222222-2222-2222-2222-222222222222")
    }

    @Test
    fun `a recheck ignores the cached answer and writes nothing`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns MusicBrainzArtistTagsEntity(
            artistName = "aes dana", mbid = "4b1591fd", tags = "black metal,metal", fetchedAt = System.currentTimeMillis()
        )
        val urls = mutableListOf<String>()
        val resolver = resolverRecording(urls, """{"artists":[]}""", """{"genres":[{"name":"psybient","count":4}]}""")

        val answer = resolver.recheck("aes dana", "Aes Dana", mbid = "ed5cd4a2-0000-0000-0000-000000000000", trackHint = null)

        assertThat(answer.genres).containsExactly("psybient")
        assertThat(answer.mbid).isEqualTo("ed5cd4a2-0000-0000-0000-000000000000")
        // The caller stores it with its own writes (storeIdentity), in one transaction.
        coVerify(exactly = 0) { dao.upsertMusicBrainzTags(any()) }
    }

    /** With no recording and no id, a shared name gives no genres instead of a namesake's. */
    @Test
    fun `an ambiguous name is not found rather than misidentified`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        val search = """{"artists":[{"id":"a","name":"AES Dana","score":100},{"id":"b","name":"Aes Dana","score":95}]}"""
        val resolver = resolverRecording(urls, search, """{"genres":[{"name":"black metal","count":4}]}""")

        val answer = resolver.resolveDetailed("Aes Dana")

        assertThat(answer.outcome).isEqualTo(GenreOutcome.NOT_FOUND)
        assertThat(answer.genres).isEmpty()
        assertThat(urls.none { "inc=genres" in it }).isTrue()
    }

    @Test
    fun `an ambiguous name is cached as such, so the enricher does not ask again`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val search = """{"artists":[{"id":"a","name":"AES Dana","score":100},{"id":"b","name":"Aes Dana","score":95}]}"""
        resolverRecording(mutableListOf(), search, "{}").resolveDetailed("Aes Dana")

        coVerify {
            dao.upsertMusicBrainzTags(match<MusicBrainzArtistTagsEntity> { it.artistName == "aes dana" && it.mbid == "ambiguous" })
        }
    }

    /**
     * A page lookup without a recording cached the name as ambiguous. The history path,
     * which always has the track, must still be able to identify the artist by it instead
     * of reading that empty answer for two weeks.
     */
    @Test
    fun `a recording still decides a name cached as ambiguous`() = runBlocking {
        coEvery { dao.getMusicBrainzTags("aes dana") } returns MusicBrainzArtistTagsEntity(
            artistName = "aes dana", mbid = "ambiguous", tags = "", fetchedAt = System.currentTimeMillis()
        )
        val urls = mutableListOf<String>()
        val recording = """{"recordings":[{"score":100,"artist-credit":[{"artist":{"id":"ed5cd4a2-0000-0000-0000-000000000000","name":"AES Dana"}}]}]}"""
        val resolver = resolverRecording(urls, recording, """{"genres":[{"name":"psybient","count":4}]}""")

        val answer = resolver.resolveDetailed("Aes Dana", trackHint = "Snöflinga")
        val withoutTrack = resolver.resolveDetailed("Aes Dana")

        assertThat(answer.genres).containsExactly("psybient")
        assertThat(urls.first()).contains("recording")
        assertThat(withoutTrack.outcome).isEqualTo(GenreOutcome.CACHED_EMPTY)
    }

    /** Records each URL and answers each with [respond], which returns a status and a body. */
    private fun resolverWith(urls: MutableList<String>, respond: (String) -> Pair<Int, String>): MusicBrainzGenreResolver {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val url = request.url.toString()
            urls += url
            val (code, body) = respond(url)
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(body.toResponseBody()).build()
            }
        }
        return MusicBrainzGenreResolver(dao, client, Json { ignoreUnknownKeys = true }, rateLimiter)
    }

    /** A 404 used to read as "no answer", and the repair stopped on it for good. */
    @Test
    fun `an MBID MusicBrainz no longer has is an answer, not an outage`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val answer = resolverWith(mutableListOf()) { 404 to "" }.resolveDetailed("Gone", mbid = "33333333-3333-3333-3333-333333333333")
        assertThat(answer.outcome).isEqualTo(GenreOutcome.NOT_FOUND)
    }

    @Test
    fun `a quote or backslash in a name is escaped inside the phrase`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        resolverWith(urls) { 200 to """{"artists":[]}""" }.resolveDetailed("""The "Band" \ Co""")
        val query = urls.single().toHttpUrl().queryParameter("query")
        assertThat(query).isEqualTo("""artist:"The \"Band\" \\ Co"""")
    }

    /** Two namesakes can each have a song of that title; then the recording decides nothing. */
    @Test
    fun `a recording credited to two artists of the name identifies neither`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        val recordings = """{"recordings":[
            {"score":100,"artist-credit":[{"artist":{"id":"one","name":"Wild"}}]},
            {"score":95,"artist-credit":[{"artist":{"id":"two","name":"Wild"}}]}]}"""
        val answer = resolverWith(urls) { url ->
            when {
                "/recording" in url -> 200 to recordings
                else -> 200 to """{"artists":[{"id":"one","name":"Wild","score":100},{"id":"two","name":"Wild","score":98}]}"""
            }
        }.resolveDetailed("Wild", trackHint = "Love")
        assertThat(answer.outcome).isEqualTo(GenreOutcome.NOT_FOUND)
        assertThat(urls.none { "inc=genres" in it }).isTrue()
    }

    /**
     * Neve on the S25: two bands of the name have "It's Over Now", and the second one can
     * rank below other matches. The search must reach it to see that the title is shared.
     */
    @Test
    fun `a namesake's recording below the third result still makes the title shared`() = runBlocking {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val credits = listOf("la-band", "la-band", "la-band", "uk-trio").map { id ->
            """{"score":100,"artist-credit":[{"artist":{"id":"$id","name":"Neve"}}]}"""
        }
        val answer = resolverWith(mutableListOf()) { url ->
            val limit = Regex("[?&]limit=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: credits.size
            when {
                "/recording" in url -> 200 to """{"recordings":[${credits.take(limit).joinToString(",")}]}"""
                else -> 200 to """{"artists":[{"id":"la-band","name":"Neve","score":100},{"id":"uk-trio","name":"Neve","score":98}]}"""
            }
        }.identifyByRecording("Neve", "It's Over Now")
        assertThat(answer).isEqualTo(Lookup.Missing)
    }

    @Test
    fun `the bio stays while the answer names the same artist and goes with a namesake`() = runBlocking<Unit> {
        val withBio = MusicBrainzArtistTagsEntity("aes dana", "old", "metal", 0L, bio = "A French band.", bioFetchedAt = 5L)
        coEvery { dao.getMusicBrainzTags("aes dana") } returns withBio
        val resolver = resolverWith(mutableListOf()) { 200 to "{}" }

        resolver.storeIdentity("aes dana", "old", listOf("metal"))
        resolver.storeIdentity("aes dana", "new", listOf("ambient"))

        coVerify { dao.upsertMusicBrainzTags(match<MusicBrainzArtistTagsEntity> { it.mbid == "old" && it.bio == "A French band." }) }
        coVerify { dao.upsertMusicBrainzTags(match<MusicBrainzArtistTagsEntity> { it.mbid == "new" && it.bio == null }) }
    }

    /**
     * A lookup that replaces an answer with another artist announces it at once; a
     * repair's [MusicBrainzGenreResolver.storeIdentity] waits for its transaction and
     * announces with announceIdentityChange.
     */
    @Test
    fun `replacing an answer with another artist is announced, after the transaction for a repair`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags("aes dana") } returns MusicBrainzArtistTagsEntity("aes dana", "old", "metal", 0L)
        val resolver = resolverWith(mutableListOf()) { 200 to "{}" }
        val announced = mutableListOf<String>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            resolver.identityChanges.collect { announced += it }
        }

        resolver.storeIdentity("aes dana", "new", listOf("ambient"))
        assertThat(announced).isEmpty()
        resolver.announceIdentityChange("aes dana")

        job.cancel()
        assertThat(announced).containsExactly("aes dana")
    }

    @Test
    fun `the answer says what identified the artist`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val genres = """{"genres":[{"name":"ambient","count":1}]}"""
        val byMbid = resolverWith(mutableListOf()) { 200 to genres }.recheck("k", "Aes Dana", mbid = "ed5cd4a2-0eb1-4e3b-a9b5-73547a6447da", trackHint = null)
        val recording = """{"recordings":[{"score":100,"artist-credit":[{"artist":{"id":"r","name":"AES Dana"}}]}]}"""
        val byRecording = resolverWith(mutableListOf()) { url -> 200 to (if ("inc=genres" in url) genres else recording) }
            .recheck("k", "Aes Dana", mbid = null, trackHint = "Snöflinga")
        val name = """{"artists":[{"id":"n","name":"AES Dana","score":100}]}"""
        val byName = resolverWith(mutableListOf()) { url -> 200 to (if ("inc=genres" in url) genres else name) }
            .recheck("k", "Aes Dana", mbid = null, trackHint = null)

        assertThat(byMbid.evidence).isEqualTo(IdentityEvidence.MBID)
        assertThat(byRecording.evidence).isEqualTo(IdentityEvidence.RECORDING)
        assertThat(byName.evidence).isEqualTo(IdentityEvidence.NAME)
    }

    /** A recording was already tried for this row, so asking again would only repeat it. */
    @Test
    fun `a name left ambiguous after a recording is not asked again`() = runBlocking {
        coEvery { dao.getMusicBrainzTags("aes dana") } returns MusicBrainzArtistTagsEntity(
            artistName = "aes dana", mbid = "ambiguous-recording", tags = "", fetchedAt = System.currentTimeMillis()
        )
        val urls = mutableListOf<String>()
        val answer = resolverWith(urls) { 200 to "{}" }.resolveDetailed("Aes Dana", trackHint = "Snöflinga")
        assertThat(answer.outcome).isEqualTo(GenreOutcome.CACHED_EMPTY)
        assertThat(urls).isEmpty()
    }

    @Test
    fun `a name left ambiguous without a recording is still worth asking for a caller with one`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(
            MusicBrainzArtistTagsEntity("aes dana", "ambiguous", "", System.currentTimeMillis())
        )
        val resolver = resolverWith(mutableListOf()) { 200 to "{}" }
        val withTrack = MusicBrainzGenreResolver.ArtistRef("Aes Dana", trackHint = "Snöflinga")
        val withoutTrack = MusicBrainzGenreResolver.ArtistRef("Aes Dana")

        assertThat(resolver.stillWorthAsking(listOf(withTrack))).containsExactly(withTrack)
        assertThat(resolver.stillWorthAsking(listOf(withoutTrack))).isEmpty()
    }

    /**
     * The id Music Assistant reports goes into a musicbrainz.org URL path. A value that is
     * not a UUID would call another endpoint, so it is ignored and the name decides.
     */
    @Test
    fun `an id that is not a MusicBrainz id never reaches a URL`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        val search = """{"artists":[{"id":"44444444-4444-4444-4444-444444444444","name":"Wild","score":100}]}"""
        val resolver = resolverWith(urls) { url -> 200 to (if ("inc=genres" in url) """{"genres":[]}""" else search) }

        val answer = resolver.resolveDetailed("Wild", mbid = "../recording/x?query=y")

        assertThat(urls.none { "recording/x" in it || "query=y" in it }).isTrue()
        assertThat(urls.first()).contains("artist?query=")
        assertThat(answer.evidence).isEqualTo(IdentityEvidence.NAME)
    }

    @Test
    fun `only the UUID form is taken as a MusicBrainz id`() {
        assertThat(validMbidOrNull(" 49A5F5B9-44F3-4DB2-A9A8-DB8773DE4EDC ")).isEqualTo("49a5f5b9-44f3-4db2-a9a8-db8773de4edc")
        assertThat(validMbidOrNull("../recording/49a5f5b9-44f3-4db2-a9a8-db8773de4edc")).isNull()
        assertThat(validMbidOrNull("49a5f5b9-44f3-4db2-a9a8-db8773de4edc?inc=x")).isNull()
        assertThat(validMbidOrNull("")).isNull()
        assertThat(validMbidOrNull(null)).isNull()
    }
}
