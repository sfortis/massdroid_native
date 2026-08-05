package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Artist biographies, with no API key anywhere.
 *
 * Music Assistant only enriches metadata for LIBRARY items - it says so itself
 * ("Metadata can only be updated for library items"), and a provider-only
 * artist therefore arrives with no description, no genres and no MBID at all.
 * On a real library that is 4917 of 5766 artists, so the artist screen was blank
 * for almost everything the listener opened.
 *
 * The chain that fixes it needs no key: MusicBrainz finds the artist BY NAME
 * (measured: 93% of provider-only artists), which yields an MBID, whose
 * relations carry a Wikidata or Wikipedia link, whose article summary is the
 * bio. Measured end to end on that same population: 62%, against 0% today and
 * 81% for Last.fm with a key.
 *
 * The first step is a name search and names are not unique, so the match is
 * only accepted at MusicBrainz's own top score - the same bar
 * [MusicBrainzGenreResolver] uses before it will attach genres to an artist.
 */
@Singleton
class ArtistBioResolver @Inject constructor(
    private val dao: PlayHistoryDao,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val rateLimiter: MusicBrainzRateLimiter,
    private val genreResolver: MusicBrainzGenreResolver,
) {

    /**
     * The artist's biography, or null when nothing describes them.
     *
     * Answers from cache when it can, including a cached "nothing found", so a
     * screen reopened does not re-walk the chain.
     */
    suspend fun resolve(artistName: String, mbid: String? = null): String? {
        val key = cacheKey(artistName, mbid)
        if (key.isEmpty()) return null
        cached(key)?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            val resolvedMbid = mbid?.takeIf { it.isNotBlank() }
                ?: genreResolver.findMbidForBio(artistName)
                ?: run { store(key, "") ; return@withContext null }
            val bio = describe(resolvedMbid).orEmpty()
            store(key, bio)
            bio.ifEmpty { null }
        }
    }

    private suspend fun cached(key: String): String? {
        val row = try {
            dao.getMusicBrainzTags(key)
        } catch (_: Exception) {
            null
        } ?: return null
        val at = row.bioFetchedAt ?: return null
        val ttl = if (row.bio.isNullOrBlank()) EMPTY_CACHE_MS else CACHE_MS
        if (System.currentTimeMillis() - at > ttl) return null
        return row.bio.orEmpty()
    }

    private suspend fun store(key: String, bio: String) {
        try {
            dao.upsertArtistBio(key, bio, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "bio cache write failed for '$key': ${e.message}")
        }
    }

    /**
     * What MusicBrainz can say about this artist, best first.
     *
     * The Wikipedia article it links to is a real description. When there is no
     * link - measured at 38% of the artists this route is asked about - the
     * disambiguation comment is the fallback: it is one line, written by an
     * editor to tell namesakes apart ("French cold wave", "1970s African
     * psychedelic band"), and one accurate line beats an empty screen.
     *
     * One request serves both: the artist lookup carries the disambiguation and
     * the relations together.
     */
    private suspend fun describe(mbid: String): String? {
        val artist = mbGet("$MB_BASE/artist/$mbid?inc=url-rels&fmt=json")?.jsonObject ?: return null
        var wikidataId: String? = null
        var wikipediaTitle: String? = null
        for (relation in artist["relations"]?.jsonArray.orEmpty()) {
            val url = relation.jsonObject["url"]?.jsonObject?.get("resource")
                ?.jsonPrimitive?.content ?: continue
            when {
                url.contains("wikidata.org/wiki/") -> wikidataId = url.substringAfterLast('/')
                url.contains("wikipedia.org/wiki/") -> wikipediaTitle = url.substringAfterLast('/')
            }
        }
        val title = wikipediaTitle ?: wikidataId?.let { enwikiTitleFor(it) }
        title?.let { wikipediaSummary(it) }?.let { return it }
        // No article. The disambiguation is not held to the prose threshold: it
        // is meant to be short, and rejecting it for that would defeat the point.
        return artist["disambiguation"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun enwikiTitleFor(wikidataId: String): String? {
        val body = httpGet(
            "$WIKIDATA_BASE?action=wbgetentities&ids=$wikidataId&props=sitelinks&format=json"
        ) ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
                ?.get(wikidataId)?.jsonObject?.get("sitelinks")?.jsonObject
                ?.get("enwiki")?.jsonObject?.get("title")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun wikipediaSummary(title: String): String? {
        val encoded = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val body = httpGet("$WIKIPEDIA_BASE/$encoded") ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["extract"]?.jsonPrimitive?.content
                ?.trim()?.takeIf { it.length >= MIN_MEANINGFUL_BIO }
        }.getOrNull()
    }

    /** MusicBrainz calls go through the shared 1 req/s gate; Wikipedia has none. */
    private suspend fun mbGet(url: String) = run {
        rateLimiter.acquire()
        httpGet(url)?.let { body -> runCatching { json.parseToJsonElement(body) }.getOrNull() }
    }

    private fun httpGet(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}")
        null
    }

    private fun cacheKey(artistName: String, mbid: String?): String =
        mbid?.trim()?.takeIf { it.isNotEmpty() } ?: artistName.trim().lowercase()

    private companion object {
        const val TAG = "ArtistBio"
        const val MB_BASE = "https://musicbrainz.org/ws/2"
        const val WIKIDATA_BASE = "https://www.wikidata.org/w/api.php"
        const val WIKIPEDIA_BASE = "https://en.wikipedia.org/api/rest_v1/page/summary"
        const val USER_AGENT = "MassDroid/2.x ( https://github.com/sfortis/massdroid_native )"
        // Wikipedia summaries are stable prose; a miss is retried sooner in case
        // an article appears.
        const val CACHE_MS = 90L * 24 * 60 * 60 * 1000
        const val EMPTY_CACHE_MS = 14L * 24 * 60 * 60 * 1000
        // Below this it is a stub, not a description worth a screen slot.
        const val MIN_MEANINGFUL_BIO = 150
    }
}
