package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _identityChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * Cache keys whose answer now names a different MusicBrainz artist than before.
     * Holders of genres read from the old answer drop them on this.
     */
    val identityChanges: SharedFlow<String> = _identityChanges.asSharedFlow()

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
            cachedRows(keys)
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
            cachedRows(byKey.keys.toList())
        } catch (_: Exception) {
            // Unable to read the cache: better to ask again than to skip
            // everything and report nothing to do.
            return artists.toList()
        }
        val now = System.currentTimeMillis()
        val answered = rows.mapNotNullTo(mutableSetOf()) { row ->
            val ttl = if (row.tags.isBlank()) EMPTY_CACHE_MS else CACHE_MS
            // A name left ambiguous by a lookup that had no recording is not settled
            // for a caller that has one (see resolveDetailed).
            val decidableByRecording = row.mbid == AMBIGUOUS_NAME && !byKey[row.artistName]?.trackHint.isNullOrBlank()
            row.artistName.takeIf { now - row.fetchedAt <= ttl && !decidableByRecording }
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
    ): List<String> = resolveDetailed(artistName, mbid, trackHint).genres

    /**
     * [resolve], plus WHICH kind of answer it was. An enrichment run that ends
     * "4 of 242 enriched" says nothing about the other 238 unless the empties are
     * told apart: artists MusicBrainz has no tags for, artists it does not know,
     * and requests that never completed and will be asked again.
     */
    suspend fun resolveDetailed(
        artistName: String,
        mbid: String? = null,
        trackHint: String? = null
    ): GenreResolution {
        val key = cacheKey(artistName, mbid)
        if (key.isEmpty()) return GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
        cachedGenres(artistName, mbid)?.let {
            // A name found ambiguous without a recording is settled only for callers that
            // have none either. The history path always brings one, and must not be held to
            // an answer a page lookup made without it, for the two weeks an empty answer lives.
            val decidableNow = it.isEmpty() && !trackHint.isNullOrBlank() && cachedAsAmbiguous(key)
            if (!decidableNow) {
                return GenreResolution(it, if (it.isEmpty()) GenreOutcome.CACHED_EMPTY else GenreOutcome.GENRES)
            }
        }
        return withContext(Dispatchers.IO) { lookUp(key, artistName, mbid, trackHint) }
    }

    /**
     * [resolveDetailed] without reading or writing the cache, for an answer that is
     * already cached and may be wrong. [GenreResolution.mbid] says which artist
     * answered. The caller decides what to keep and stores it with [storeIdentity],
     * together with its own writes.
     */
    suspend fun recheck(
        cacheKey: String,
        artistName: String,
        mbid: String?,
        trackHint: String?
    ): GenreResolution = withContext(Dispatchers.IO) { lookUp(cacheKey, artistName, mbid, trackHint, store = false) }

    /**
     * Replaces the answer under [cacheKey] with [mbid] and its [genres], for a caller
     * that found the cached answer named the wrong artist. Run it inside the caller's
     * transaction, so the cache and the genres the caller fixes cannot disagree.
     */
    suspend fun storeIdentity(cacheKey: String, mbid: String, genres: List<String>) {
        cache(cacheKey, mbid, genres, announce = false)
    }

    /**
     * Tells [identityChanges] that [cacheKey] now names another artist. For a caller of
     * [storeIdentity], after its transaction committed: announced earlier, a holder could
     * refill its genres from the old row before the new one is visible.
     */
    fun announceIdentityChange(cacheKey: String) {
        _identityChanges.tryEmit(cacheKey)
    }

    /**
     * Identifies the artist, fetches its genres and caches the answer under [key].
     *
     * Whether an empty answer is remembered depends on which kind of nothing it
     * was. MusicBrainz answering "no such artist", or "this artist has no genres",
     * is a fact worth caching for weeks. A request that never completed, because
     * the rate limiter gave up after a 503 or the network failed, is not a fact at
     * all, and caching it as one used to hide the artist from every mix for the
     * next fortnight. Two days of logs held 38 such give-ups.
     */
    private suspend fun lookUp(
        key: String,
        artistName: String,
        mbid: String?,
        trackHint: String?,
        store: Boolean = true
    ): GenreResolution {
        val identified = when (val identity = identify(artistName, mbid, trackHint)) {
            is Lookup.Found -> identity.value
            Lookup.Missing -> {
                if (store) cache(key, mbid = "", genres = emptyList())
                return GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
            }
            Lookup.Ambiguous -> {
                // Remembered like a miss, so the enricher does not ask again, but marked by
                // whether a recording was tried: if not, a later lookup that brings one can
                // still decide it (resolveDetailed, stillWorthAsking).
                val marker = if (trackHint.isNullOrBlank()) AMBIGUOUS_NAME else AMBIGUOUS_AFTER_RECORDING
                if (store) cache(key, mbid = marker, genres = emptyList())
                return GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
            }
            Lookup.Unavailable -> return GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE)
        }
        return when (val fetched = fetchGenres(identified.mbid)) {
            is Lookup.Found -> {
                if (store) cache(key, identified.mbid, fetched.value)
                GenreResolution(
                    fetched.value,
                    if (fetched.value.isEmpty()) GenreOutcome.NO_GENRES else GenreOutcome.GENRES,
                    identified.mbid,
                    identified.evidence
                )
            }
            // The id no longer exists on MusicBrainz (merged or deleted): nothing to
            // describe the artist with, which is an answer, not an outage.
            Lookup.Missing -> {
                if (store) cache(key, mbid = "", genres = emptyList())
                GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
            }
            else -> GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE)
        }
    }

    /**
     * Which MusicBrainz artist this is, best evidence first.
     *
     * 1. An id from Music Assistant is exact. Only library items carry one;
     *    provider items report `external_ids: []` (verified against the server).
     *    It used to be used only as the cache key while the lookup searched by
     *    name, so a library artist with a known id could still get a namesake's
     *    genres: "Wild" got power metal and "PAPA" nu metal.
     * 2. [trackHint], a recording we know this artist for, because a namesake
     *    does not share the recording.
     * 3. Name alone, which is not an identity. [pickArtistCandidate] refuses a
     *    name that several artists share.
     *
     * Missing is returned only when every step that ran actually answered.
     */
    private suspend fun identify(artistName: String, mbid: String?, trackHint: String?): Lookup<Identified> {
        validMbidOrNull(mbid)?.let { return Lookup.Found(Identified(it, IdentityEvidence.MBID)) }
        val byRecording = trackHint?.takeIf { it.isNotBlank() }
            ?.let { findMbidByRecording(artistName, it) }
            ?: Lookup.Missing
        if (byRecording is Lookup.Found) return Lookup.Found(Identified(byRecording.value, IdentityEvidence.RECORDING))
        return when (val byName = findMbid(artistName)) {
            is Lookup.Found -> Lookup.Found(Identified(byName.value, IdentityEvidence.NAME))
            Lookup.Unavailable -> Lookup.Unavailable
            // Only authoritative when every step that ran actually answered.
            Lookup.Missing -> if (byRecording == Lookup.Unavailable) Lookup.Unavailable else Lookup.Missing
            Lookup.Ambiguous -> if (byRecording == Lookup.Unavailable) Lookup.Unavailable else Lookup.Ambiguous
        }
    }

    private data class Identified(val mbid: String, val evidence: IdentityEvidence)

    /**
     * The artist credited with [trackName], when we know one of their recordings.
     *
     * This is the disambiguator a bare name cannot be. Two artists share a name;
     * they do not share a recording. Returns null when the recording is unknown
     * or the credit is ambiguous, and the caller falls back to the name search.
     */
    private suspend fun findMbidByRecording(artistName: String, trackName: String): Lookup<String> {
        val url = "$BASE/recording".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", "recording:\"${phrase(trackName)}\" AND artist:\"${phrase(artistName)}\"")
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", "3")
            ?.build() ?: return Lookup.Missing
        val body = when (val answer = request(url.toString())) {
            is Lookup.Found -> answer.value
            Lookup.Missing -> return Lookup.Missing
            else -> return Lookup.Unavailable
        }
        val recordings = json.parseToJsonElement(body).jsonObject["recordings"]?.jsonArray.orEmpty()
        // Only a credit whose NAME still matches is usable: a recording can credit
        // several artists (features, remixes), and picking the wrong one would
        // reintroduce exactly the bug this exists to prevent. Every matching recording
        // is read, because two namesakes can each have a song of that title, and then
        // the recording decides nothing.
        val ids = recordings.flatMapTo(linkedSetOf()) { recording ->
            val obj = recording.jsonObject
            if ((obj["score"]?.jsonPrimitive?.intOrNull ?: 0) < MIN_MATCH_SCORE) return@flatMapTo emptyList()
            obj["artist-credit"]?.jsonArray.orEmpty().mapNotNull { credit ->
                val artist = credit.jsonObject["artist"]?.jsonObject ?: return@mapNotNull null
                val named = artist["name"]?.jsonPrimitive?.content.equals(artistName, ignoreCase = true)
                artist["id"]?.jsonPrimitive?.content?.takeIf { named && it.isNotEmpty() }
            }
        }
        if (ids.size > 1) {
            Log.d(TAG, "'$artistName': recording '$trackName' is credited to ${ids.size} artists of that name")
            return Lookup.Missing
        }
        val id = ids.singleOrNull() ?: return Lookup.Missing
        Log.d(TAG, "'$artistName' identified via recording '$trackName' -> $id")
        return Lookup.Found(id)
    }

    private suspend fun cachedAsAmbiguous(key: String): Boolean =
        try {
            dao.getMusicBrainzTags(key)?.mbid == AMBIGUOUS_NAME
        } catch (_: Exception) {
            false
        }

    private suspend fun cache(key: String, mbid: String, genres: List<String>, announce: Boolean = true) {
        try {
            // The bio shares this row and was fetched for its MBID: it stays while the
            // answer names the same artist, and goes with a namesake's genres.
            val previous = dao.getMusicBrainzTags(key)
            val sameArtist = previous != null && previous.mbid == mbid
            dao.upsertMusicBrainzTags(
                MusicBrainzArtistTagsEntity(
                    artistName = key,
                    mbid = mbid,
                    tags = genres.joinToString(","),
                    fetchedAt = System.currentTimeMillis(),
                    bio = previous?.bio.takeIf { sameArtist },
                    bioFetchedAt = previous?.bioFetchedAt.takeIf { sameArtist }
                )
            )
            if (announce && previous != null && !sameArtist && previous.mbid.isNotEmpty() && mbid.isNotEmpty()) {
                announceIdentityChange(key)
            }
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
        // Several candidates are requested rather than one, because the top hit is
        // often a namesake: searching "Labelle" returns "LaBelle" (the American soul
        // group) at 100 and the "Labelle" being played at 86. Seeing the others is
        // how a name that several artists share is recognised as ambiguous.
        val url = "$BASE/artist".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", "artist:\"${phrase(artistName)}\"")
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", SEARCH_CANDIDATES.toString())
            ?.build() ?: return Lookup.Missing
        val body = when (val answer = request(url.toString())) {
            is Lookup.Found -> answer.value
            Lookup.Missing -> return Lookup.Missing
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
        if (isAmbiguousName(scored, artistName, EXACT_NAME_MIN_SCORE)) {
            Log.d(TAG, "'$artistName': several artists carry this name, a recording or an MBID has to decide")
            return Lookup.Ambiguous
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
            Lookup.Missing -> return Lookup.Missing
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
        // is a "come back later", not a failure, so it is retried after a pause
        // that doubles each time (see MusicBrainzRateLimiter.backOff) and only
        // then reported as [Lookup.Unavailable], which the caller must not record
        // as "this artist has no genres".
        repeat(RATE_LIMIT_RETRIES + 1) { attempt ->
            rateLimiter.acquire()
            val result = try {
                // MusicBrainz rejects requests without a descriptive User-Agent.
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            rateLimiter.noteSuccess()
                            response.body?.string()
                        }
                        response.code == HTTP_SERVICE_UNAVAILABLE -> {
                            rateLimiter.backOff(response.header("Retry-After")?.toLongOrNull())
                            null
                        }
                        response.code == HTTP_BAD_REQUEST || response.code == HTTP_NOT_FOUND -> {
                            // The request itself cannot be answered: an id that no longer
                            // exists, or a query MusicBrainz cannot parse. Asking again
                            // gives the same answer, so this one is final.
                            Log.w(TAG, "HTTP ${response.code} for $url")
                            return Lookup.Missing
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
            if (attempt == RATE_LIMIT_RETRIES) Log.w(TAG, "rate-limited ${attempt + 1} times, giving up on $url")
        }
        return Lookup.Unavailable
    }

    /**
     * [text] for a quoted Lucene phrase. A `"` or `\` in an artist or track name ended the
     * phrase early, and MusicBrainz answered 400 to the broken query.
     */
    private fun phrase(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Cache identity: the MusicBrainz id when we have one, otherwise the name.
     * Keying everything by name would make two different artists sharing a name
     * share one cache entry, which is the very thing the id is here to prevent.
     */
    private fun cacheKey(artistName: String, mbid: String?): String =
        mbid?.trim()?.takeIf { it.isNotEmpty() } ?: artistName.trim().lowercase()

    /**
     * An artist to look up: the id if Music Assistant knew one, else the name. [trackHint]
     * is a recording of theirs, when the caller has one (see [stillWorthAsking]).
     */
    data class ArtistRef(val name: String, val mbid: String? = null, val trackHint: String? = null)

    /**
     * Cache rows for [keys], read in chunks. The gap walk passes thousands of
     * keys (8,409 on a real library), and Android 11 and older allow 999 bound
     * variables per statement: the single query threw there, the callers caught
     * it, and every answered artist was queued for MusicBrainz again.
     */
    private suspend fun cachedRows(keys: List<String>): List<MusicBrainzArtistTagsEntity> =
        keys.chunked(SQL_IN_CHUNK).flatMap { dao.getMusicBrainzTagsFor(it) }

    private companion object {
        const val TAG = "MusicBrainzGenre"
        /** Bound variables per `IN` query, under the 999 SQLite allows on older Android. */
        const val SQL_IN_CHUNK = 500
        const val BASE = "https://musicbrainz.org/ws/2"
        const val USER_AGENT = "MassDroid/2.x ( https://github.com/sfortis/massdroid_native )"
        // MusicBrainz genres are stable, so entries are kept for 90 days; a miss
        // is retried after 14 days in case the artist is new.
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
         * Bar for a candidate that carries the name we searched (ignoring case).
         * Lower than [MIN_MATCH_SCORE] on purpose: the name already agrees, so the
         * fuzzy score is corroboration rather than the deciding evidence. Two such
         * candidates make the name ambiguous (see [pickArtistCandidate]).
         */
        const val EXACT_NAME_MIN_SCORE = 80
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404

        /**
         * Stored as the MBID of a name that several artists carry, looked up without a
         * recording; a later lookup with one may still decide it. See [Lookup.Ambiguous].
         */
        const val AMBIGUOUS_NAME = "ambiguous"

        /** The same, after a recording was tried and did not decide it either. Settled. */
        const val AMBIGUOUS_AFTER_RECORDING = "ambiguous-recording"
        const val RATE_LIMIT_RETRIES = 3
    }
}

/** Which kind of answer a genre lookup produced; see [MusicBrainzGenreResolver.resolveDetailed]. */
enum class GenreOutcome {
    /** Genres found (fresh or cached). */
    GENRES,
    /** MusicBrainz knows the artist and lists no genres. A fact, cached. */
    NO_GENRES,
    /** MusicBrainz does not know the artist. A fact, cached. */
    NOT_FOUND,
    /** No answer (rate limited, HTTP error, network). Not cached; asked again later. */
    UNAVAILABLE,
    /** An earlier run already recorded an empty answer; not asked this time. */
    CACHED_EMPTY,
}

/**
 * [mbid] is the MusicBrainz artist that answered, and [evidence] what identified it; both
 * null when none did or the answer came from the cache.
 */
data class GenreResolution(
    val genres: List<String>,
    val outcome: GenreOutcome,
    val mbid: String? = null,
    val evidence: IdentityEvidence? = null
)

/** What identified an artist on MusicBrainz, strongest first. */
enum class IdentityEvidence {
    /** The id Music Assistant reported for the library item. */
    MBID,

    /** A recording of theirs that we hold, credited to that name. */
    RECORDING,

    /** The name alone, by its score lead over namesakes ([NAME_TIE_MARGIN]). */
    NAME
}

/** One result of a MusicBrainz artist search. */
internal data class MbArtistCandidate(val id: String, val name: String, val score: Int)

/** The form of a MusicBrainz id: a lowercase UUID. */
internal val MBID_FORM = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * [value] as a MusicBrainz id when it has that form, else null. An id Music Assistant
 * reports goes into a musicbrainz.org URL path, so a value like "../recording/x" or
 * "x?query=y" from a misconfigured or hostile server would call another endpoint and
 * store whatever came back as genres or a bio. Such a value is ignored, and the lookup
 * goes on as if no id were known.
 */
internal fun validMbidOrNull(value: String?): String? = value?.trim()?.lowercase()?.takeIf { MBID_FORM.matches(it) }

/**
 * How far the best of several candidates carrying the same name must lead the next
 * one for the name to pick it. Measured on musicbrainz.org on 2026-11-12, the
 * candidates being played lead by 13 ("AES Dana" over a black metal "Aes Dana") and
 * 11 ("Asura" over a grindcore namesake), and a well-known artist leads its
 * namesakes by far more. Accepted cost, decided by the owner: "LaBelle" leads the
 * Reunion Island "Labelle" by 14, so a caller with no recording to go by gets the US
 * soul group. Callers that have a recording are decided by it before the name.
 */
internal const val NAME_TIE_MARGIN = 10

/**
 * Which search result is actually the artist we asked for, or null when the name
 * alone cannot tell.
 *
 * A name is not an identity, and the spelling decides nothing: preferring the
 * candidate spelled exactly like the artist made "Aes Dana" a French black metal
 * band and "ASURA" a Japanese power metal band, and turned an ambient listener's
 * Smart Mix into metal. Among the candidates that carry the name (ignoring case,
 * at [exactNameMinScore] or above), the best one is taken only when it leads the
 * next by [NAME_TIE_MARGIN]; closer than that, the name is ambiguous and nothing is
 * picked, because an artist with no genres is merely undescribed while a
 * misidentified one poisons every mix built on it.
 */
internal fun pickArtistCandidate(
    candidates: List<MbArtistCandidate>,
    artistName: String,
    exactNameMinScore: Int = 80,
    minMatchScore: Int = 90
): String? {
    if (isAmbiguousName(candidates, artistName, exactNameMinScore)) return null
    candidates.filter { it.carriesName(artistName, exactNameMinScore) }.maxByOrNull { it.score }?.let { return it.id }
    val best = candidates.maxByOrNull { it.score } ?: return null
    return if (best.score >= minMatchScore) best.id else null
}

/**
 * Whether several candidates carry [artistName] (ignoring case, at [exactNameMinScore]
 * or above) without the best leading the next by [NAME_TIE_MARGIN].
 */
internal fun isAmbiguousName(candidates: List<MbArtistCandidate>, artistName: String, exactNameMinScore: Int = 80): Boolean {
    val scores = candidates.filter { it.carriesName(artistName, exactNameMinScore) }.map { it.score }.sortedDescending()
    return scores.size > 1 && scores[0] - scores[1] < NAME_TIE_MARGIN
}

private fun MbArtistCandidate.carriesName(artistName: String, minScore: Int): Boolean =
    name.equals(artistName, ignoreCase = true) && score >= minScore

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

    /** The server answered with several artists of that name, and the name cannot tell them apart. */
    data object Ambiguous : Lookup<Nothing>
}
