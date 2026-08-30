package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genre lookup against MusicBrainz, for artists nothing else can describe.
 *
 * Music Assistant hands back similar artists as PROVIDER items (Deezer here),
 * and those carry no genres and no MBID even when asked for individually, so
 * half to two thirds of a Smart Mix candidate pool used to reach the genre gate
 * unjudgeable. MusicBrainz covers them: on the exact artists that leaked into a
 * shoegaze mix it answered for 13 of 16, and answered correctly (Lost
 * Frequencies -> house/dance/edm, Morandi -> pop, The Telescopes -> shoegaze).
 *
 * Deliberate choices:
 * - **Search, not lookup.** The candidates have no MBID, so the entity is found
 *   by name. A hit is accepted only at [MIN_MATCH_SCORE], because a weak name
 *   match would poison the gate with a stranger's genres.
 * - **Two requests: find the entity, then read its GENRES.** The search
 *   response also carries `tags`, and using those to save a call was a mistake:
 *   tags are free-text and include nationalities, so Ramones came back as
 *   `punk rock, pop punk, punk, estados unidos` and a cluster was built on a
 *   country. The `genres` list is the curated taxonomy and gives
 *   `punk rock, pop punk, punk, power pop`.
 * - **Empty is an answer.** An artist MusicBrainz has nothing on is cached as
 *   empty, so the 1 req/s budget is never spent twice on the same dead end.
 * - Genres are taken as MusicBrainz reports them, with no allow-list. The list
 *   is a curated ~2000-entry taxonomy, not crowd tags, so there is no noise to
 *   filter out - and filtering is what made whole non-western scenes invisible.
 */
@Singleton
class MusicBrainzGenreResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val rateLimiter: MusicBrainzRateLimiter
) {
    /**
     * Cached genres for [artistName]: weight-ordered, empty when MusicBrainz has
     * nothing, null when we have never asked (or the entry has expired).
     */
    suspend fun cachedGenres(artistName: String, mbid: String? = null): List<String>? {
        val key = cacheKey(artistName, mbid)
        if (key.isEmpty()) return null
        val row = try {
            dao.getMusicBrainzTags(key)
        } catch (_: Exception) {
            null
        } ?: return null
        val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
        if (System.currentTimeMillis() - row.fetchedAt > ttl) return null
        return row.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Cached genres for many artists at once, for callers that judge a whole
     * pool (the Smart Mix cluster is ~200 artists and cannot afford a query
     * each). Artists never looked up, or whose entry expired, are absent.
     */
    suspend fun cachedGenresFor(artists: Collection<ArtistRef>): Map<String, List<String>> {
        val keys = artists.map { cacheKey(it.name, it.mbid) }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        val rows = try {
            dao.getMusicBrainzTagsFor(keys)
        } catch (_: Exception) {
            return emptyMap()
        }
        val now = System.currentTimeMillis()
        return rows.mapNotNull { row ->
            val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
            if (now - row.fetchedAt > ttl) return@mapNotNull null
            val tags = row.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isEmpty()) null else row.artistName to tags
        }.toMap()
    }

    /**
     * Which of [artists] are still worth asking MusicBrainz about.
     *
     * An artist MusicBrainz has already told us it does not know is a settled
     * question, not outstanding work: [cachedGenresFor] hides those because a
     * caller wanting genres wants genres, and a caller deciding what to look up
     * needs the opposite. Without this the enricher re-listed the same artists
     * on every start and reported none enriched, which reads as a broken engine
     * (measured: all 586 of the reported gaps had already been answered).
     *
     * Takes and returns [ArtistRef] rather than cache keys on purpose. The key
     * format is this class's business; a caller that had to reproduce it would
     * be one edit away from silently matching nothing.
     */
    suspend fun stillWorthAsking(artists: Collection<ArtistRef>): List<ArtistRef> {
        if (artists.isEmpty()) return emptyList()
        val byKey = artists.associateBy { cacheKey(it.name, it.mbid) }
            .filterKeys { it.isNotEmpty() }
        if (byKey.isEmpty()) return emptyList()
        val rows = try {
            dao.getMusicBrainzTagsFor(byKey.keys.toList())
        } catch (_: Exception) {
            // Unable to read the cache: better to ask again than to skip
            // everything and report nothing to do.
            return artists.toList()
        }
        val now = System.currentTimeMillis()
        val answered = rows.mapNotNullTo(mutableSetOf()) { row ->
            val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
            row.artistName.takeIf { now - row.fetchedAt <= ttl }
        }
        return byKey.filterKeys { it !in answered }.values.toList()
    }

    /**
     * Fetch and cache genres for [artistName]. Rate-limited to MusicBrainz's
     * 1 req/s, so callers must treat this as background work, never inline in a
     * mix build.
     */
    suspend fun resolve(
        artistName: String,
        mbid: String? = null,
        trackHint: String? = null
    ): List<String> {
        val key = cacheKey(artistName, mbid)
        if (key.isEmpty()) return emptyList()
        cachedGenres(artistName, mbid)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Identity, best evidence first.
            //
            // 1. An id from Music Assistant is exact. Only library items carry one;
            //    provider items report `external_ids: []` (verified against the
            //    server), so most artists reach here without it.
            // 2. [trackHint] - a recording we know this artist for - is the next
            //    best thing, because a namesake does not share the recording.
            //    Measured: "Labelle" name-searches to the American soul group
            //    LaBelle (score 100, disco/funk/soul) while the artist actually
            //    played was the Reunion Island electronic producer (score 86, so
            //    the score bar could never have saved it). The wrong genres then
            //    became a whole mix's envelope.
            // 3. Name alone, which is not an identity and is used only as a last
            //    resort.
            //
            // Whether an empty answer is remembered depends on which kind of
            // nothing it was. MusicBrainz answering "no such artist", or "this
            // artist has no genres", is a fact worth caching for weeks. A request
            // that never completed, because the rate limiter gave up after a 503
            // or the network failed, is not a fact at all, and caching it as one
            // used to hide the artist from every mix for the next fortnight. Two
            // days of logs held 38 such give-ups.
            val byRecording = trackHint?.takeIf { it.isNotBlank() }
                ?.let { findMbidByRecording(artistName, it) }
                ?: Lookup.Missing
            val resolvedMbid = when (byRecording) {
                is Lookup.Found -> byRecording.value
                else -> when (val byName = findMbid(artistName)) {
                    is Lookup.Found -> byName.value
                    Lookup.Unavailable -> return@withContext emptyList()
                    Lookup.Missing -> {
                        // Only authoritative when every step actually answered.
                        if (byRecording != Lookup.Unavailable) {
                            cache(key, mbid = "", genres = emptyList())
                        }
                        return@withContext emptyList()
                    }
                }
            }
            when (val fetched = fetchGenres(resolvedMbid)) {
                is Lookup.Found -> {
                    cache(key, resolvedMbid, fetched.value)
                    fetched.value
                }
                else -> emptyList()
            }
        }
    }

    /**
     * The artist credited with [trackName], when we know one of their recordings.
     *
     * This is the disambiguator a bare name cannot be. Two artists share a name;
     * they do not share a recording. Returns null when the recording is unknown
     * or the credit is ambiguous, and the caller falls back to the name search.
     */
    private suspend fun findMbidByRecording(artistName: String, trackName: String): Lookup<String> {
        val url = "$BASE/recording".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", "recording:\"$trackName\" AND artist:\"$artistName\"")
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", "3")
            ?.build() ?: return Lookup.Missing
        val body = when (val answer = request(url.toString())) {
            is Lookup.Found -> answer.value
            else -> return Lookup.Unavailable
        }
        val recordings = json.parseToJsonElement(body).jsonObject["recordings"]?.jsonArray.orEmpty()
        for (recording in recordings) {
            val obj = recording.jsonObject
            if ((obj["score"]?.jsonPrimitive?.intOrNull ?: 0) < MIN_MATCH_SCORE) continue
            val credits = obj["artist-credit"]?.jsonArray.orEmpty()
            // Only a credit whose NAME still matches is usable: a recording can
            // credit several artists (features, remixes) and picking the wrong
            // one would reintroduce exactly the bug this exists to prevent.
            val match = credits.firstOrNull { credit ->
                val artist = credit.jsonObject["artist"]?.jsonObject ?: return@firstOrNull false
                artist["name"]?.jsonPrimitive?.content
                    .equals(artistName, ignoreCase = true)
            }?.jsonObject?.get("artist")?.jsonObject ?: continue
            val id = match["id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: continue
            Log.d(TAG, "'$artistName' identified via recording '$trackName' -> $id")
            return Lookup.Found(id)
        }
        return Lookup.Missing
    }

    private suspend fun cache(key: String, mbid: String, genres: List<String>) {
        try {
            dao.upsertMusicBrainzTags(
                MusicBrainzArtistTagsEntity(
                    artistName = key,
                    mbid = mbid,
                    tags = genres.joinToString(","),
                    fetchedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed for '$key': ${e.message}")
        }
    }

    /**
     * The name -> MBID step, for callers that need the id rather than the genres.
     * Exposed so the bio resolver does not reimplement the search, the score bar
     * or the rate limiting.
     */
    suspend fun findMbidForBio(artistName: String): String? =
        (findMbid(artistName) as? Lookup.Found)?.value

    private suspend fun findMbid(artistName: String): Lookup<String> {
        // Quoting the name makes this a phrase match; without it Lucene splits
        // on whitespace and "Pale Saints" matches any artist called "Saints".
        //
        // Several candidates are requested rather than one, because MusicBrainz
        // scores a NEAR name above an EXACT one often enough to matter: searching
        // "Labelle" returns "LaBelle" (the American soul group) at 100 and the
        // exact "Labelle" at 86. Taking the top hit picked the namesake, and no
        // score threshold could have caught it - only one result cleared the bar,
        // and it was the wrong one.
        val url = "$BASE/artist".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", "artist:\"$artistName\"")
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", SEARCH_CANDIDATES.toString())
            ?.build() ?: return Lookup.Missing
        val body = when (val answer = request(url.toString())) {
            is Lookup.Found -> answer.value
            else -> return Lookup.Unavailable
        }
        val artists = json.parseToJsonElement(body).jsonObject["artists"]?.jsonArray.orEmpty()
        if (artists.isEmpty()) return Lookup.Missing

        val scored = artists.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            MbArtistCandidate(
                id = id,
                name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
        val picked = pickArtistCandidate(scored, artistName, EXACT_NAME_MIN_SCORE, MIN_MATCH_SCORE)
            ?: run {
                Log.d(TAG, "'$artistName': no usable match in ${scored.size} candidates")
                return Lookup.Missing
            }
        return Lookup.Found(picked)
    }

    private suspend fun fetchGenres(mbid: String): Lookup<List<String>> {
        val body = when (val answer = request("$BASE/artist/$mbid?inc=genres&fmt=json")) {
            is Lookup.Found -> answer.value
            else -> return Lookup.Unavailable
        }
        // An empty list here is an answer: this artist exists and carries no genre
        // tags, which is the common case for obscure ones and worth remembering.
        return Lookup.Found(
            json.parseToJsonElement(body).jsonObject["genres"]?.let { readTags(it) }.orEmpty()
        )
    }

    /** Weight-ordered names from a MusicBrainz `tags`/`genres` array. */
    private fun readTags(element: kotlinx.serialization.json.JsonElement): List<String> =
        element.jsonArray
            .mapNotNull { entry ->
                val obj = entry.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isEmpty()) null else name to (obj["count"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            .sortedByDescending { it.second }
            .take(MAX_TAGS)
            .map { it.first }

    private suspend fun request(url: String): Lookup<String> {
        // MusicBrainz answers 503 when it considers the caller over the rate
        // limit, and it counts more strictly than one-per-second suggests: a
        // background enrichment run hit it repeatedly at a 1.1s interval. A 503
        // is a "come back later", not a failure, so it is retried once after a
        // pause and then reported as [Lookup.Unavailable], which the caller must
        // not record as "this artist has no genres".
        repeat(RATE_LIMIT_RETRIES + 1) { attempt ->
            rateLimiter.acquire()
            val result = try {
                // MusicBrainz rejects requests without a descriptive User-Agent.
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body?.string()
                        response.code == HTTP_SERVICE_UNAVAILABLE -> {
                            rateLimiter.backOff(response.header("Retry-After")?.toLongOrNull())
                            null
                        }
                        else -> {
                            // Not an answer about this artist either, so it is a
                            // reason to ask again later, not to conclude anything.
                            Log.w(TAG, "HTTP ${response.code} for $url")
                            return Lookup.Unavailable
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                return Lookup.Unavailable
            }
            if (result != null) return Lookup.Found(result)
            if (attempt == RATE_LIMIT_RETRIES) Log.w(TAG, "rate-limited, giving up on $url")
        }
        return Lookup.Unavailable
    }

    /**
     * Cache identity: the MusicBrainz id when we have one, otherwise the name.
     * Keying everything by name would make two different artists sharing a name
     * share one cache entry, which is the very thing the id is here to prevent.
     */
    private fun cacheKey(artistName: String, mbid: String?): String =
        mbid?.trim()?.takeIf { it.isNotEmpty() } ?: artistName.trim().lowercase()

    /** An artist to look up: the id if Music Assistant knew one, else the name. */
    data class ArtistRef(val name: String, val mbid: String? = null)

    private companion object {
        const val TAG = "MusicBrainzGenre"
        const val BASE = "https://musicbrainz.org/ws/2"
        const val USER_AGENT = "MassDroid/2.x ( https://github.com/sfortis/massdroid_native )"
        // MusicBrainz genres are stable, so entries are kept far longer than the
        // Last.fm tag cache; a miss is retried sooner in case the artist is new.
        const val CACHE_MS = 90L * 24 * 60 * 60 * 1000
        const val EMPTY_CACHE_MS = 14L * 24 * 60 * 60 * 1000
        const val MAX_TAGS = 4
        // Search scores are 0..100 and an exact name match scores 100. Anything
        // materially below that is a different artist.
        const val MIN_MATCH_SCORE = 90

        /**
         * Candidates pulled from a name search. One was not enough to notice a
         * namesake outscoring the exact match.
         */
        const val SEARCH_CANDIDATES = 5

        /**
         * Bar for accepting an exact-case name match. Lower than [MIN_MATCH_SCORE]
         * on purpose: the name already agreed character for character, so the
         * fuzzy score is corroboration rather than the deciding evidence.
         */
        const val EXACT_NAME_MIN_SCORE = 80
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val RATE_LIMIT_RETRIES = 1
    }
}

/** One result of a MusicBrainz artist search. */
internal data class MbArtistCandidate(val id: String, val name: String, val score: Int)

/**
 * Which search result is actually the artist we asked for.
 *
 * MusicBrainz scores a NEAR name above an EXACT one often enough to matter, and no
 * score threshold can fix that because the wrong hit is the one clearing the bar.
 * Measured on the real service: searching "Labelle" returns the American soul group
 * "LaBelle" at 100 and the exact "Labelle" (a Reunion Island electronic producer, the
 * one actually being played) at 86. Taking the top hit gave a whole Smart Mix the
 * genres disco/funk/soul and filled it with French chanson.
 *
 * So an exact-case name match wins outright, and the fuzzy score decides only when
 * nothing matches exactly.
 */
internal fun pickArtistCandidate(
    candidates: List<MbArtistCandidate>,
    artistName: String,
    exactNameMinScore: Int = 80,
    minMatchScore: Int = 90
): String? {
    candidates.firstOrNull { it.name == artistName && it.score >= exactNameMinScore }
        ?.let { return it.id }
    val best = candidates.maxByOrNull { it.score } ?: return null
    return if (best.score >= minMatchScore) best.id else null
}

/**
 * What a MusicBrainz call produced.
 *
 * The distinction that matters is between the two ways of getting nothing.
 * [Missing] is the server saying there is nothing, which is a fact and can be
 * remembered. [Unavailable] is no answer at all, which is only a reason to ask
 * again later.
 */
internal sealed interface Lookup<out T> {
    data class Found<T>(val value: T) : Lookup<T>

    /** The server answered, and there is nothing to find. */
    data object Missing : Lookup<Nothing>

    /** No answer: rate limited, an HTTP error, or the network failed. */
    data object Unavailable : Lookup<Nothing>
}
