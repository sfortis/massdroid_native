package net.asksakis.massdroidv2.data.musicbrainz

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last two steps of the description chain shared by artists and albums: from the url
 * relations of a MusicBrainz entity to the summary of its English Wikipedia article.
 *
 * A Wikipedia link in the relations is used as it is. Otherwise the Wikidata link leads to
 * the English article's title. The title is never guessed from a name: "Warpaint_(album)"
 * is the article on war paint, and the band's album is "Warpaint (Warpaint album)", which
 * only Wikidata knows.
 *
 * Also the GET both resolvers use, so every request carries the same User-Agent, which
 * MusicBrainz requires. A failed request is told apart from an answer that there is
 * nothing, because only the second may be cached: a 503 from a busy MusicBrainz cached as
 * "no article" hid the album text for a month (2026-10-23).
 */
@Singleton
class WikipediaSummaries @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    /** The outcome of one GET. */
    sealed interface Fetched {
        data class Body(val text: String) : Fetched

        /** 404: the thing asked for does not exist. A real answer. */
        data object Missing : Fetched

        /** Anything else: no network, a timeout, a server error, a rate limit. Worth asking again. */
        data class Failed(val rateLimited: Boolean, val retryAfterSeconds: Long?) : Fetched
    }

    /** The outcome of following url relations to an article summary. */
    sealed interface Summary {
        data class Text(val text: String) : Summary

        /** No article, or one too short to describe anything. */
        data object None : Summary

        /** A request on the way failed, so whether there is an article is not known. */
        data object Failed : Summary
    }

    /**
     * The summary of the English article [relations] lead to. An article whose summary is
     * shorter than [minLength] counts as none: that is a stub rather than a description.
     */
    fun summaryFor(relations: JsonArray?, minLength: Int): Summary {
        var wikidataId: String? = null
        var wikipediaTitle: String? = null
        for (relation in relations.orEmpty()) {
            val url = relation.jsonObject["url"]?.jsonObject?.get("resource")
                ?.jsonPrimitive?.content ?: continue
            when {
                url.contains("wikidata.org/wiki/") -> wikidataId = url.substringAfterLast('/')
                url.contains("wikipedia.org/wiki/") -> wikipediaTitle = url.substringAfterLast('/')
            }
        }
        val title = wikipediaTitle ?: wikidataId?.let { id ->
            when (val fetched = fetch(
                "$WIKIDATA_BASE?action=wbgetentities&ids=$id&props=sitelinks&format=json"
            )) {
                is Fetched.Body -> enwikiTitle(fetched.text, id) ?: return Summary.None
                Fetched.Missing -> return Summary.None
                is Fetched.Failed -> return Summary.Failed
            }
        } ?: return Summary.None
        val encoded = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        return when (val fetched = fetch("$WIKIPEDIA_BASE/$encoded")) {
            is Fetched.Body -> extract(fetched.text)?.takeIf { it.length >= minLength }
                ?.let { Summary.Text(it) } ?: Summary.None
            Fetched.Missing -> Summary.None
            is Fetched.Failed -> Summary.Failed
        }
    }

    /** GET [url]. Blocking: call it off the main thread. */
    fun fetch(url: String): Fetched = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        okHttpClient.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.string()?.let { Fetched.Body(it) }
                    ?: Fetched.Failed(rateLimited = false, retryAfterSeconds = null)
                response.code == HTTP_NOT_FOUND -> Fetched.Missing
                else -> {
                    Log.w(TAG, "GET ${response.code} for ${url.substringBefore('?')}")
                    Fetched.Failed(
                        rateLimited = response.code == HTTP_UNAVAILABLE,
                        retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}")
        Fetched.Failed(rateLimited = false, retryAfterSeconds = null)
    }

    /** GET [url] and return the body, or null on any failure or miss. */
    fun get(url: String): String? = (fetch(url) as? Fetched.Body)?.text

    private fun enwikiTitle(body: String, wikidataId: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
            ?.get(wikidataId)?.jsonObject?.get("sitelinks")?.jsonObject
            ?.get("enwiki")?.jsonObject?.get("title")?.jsonPrimitive?.content
    }.getOrNull()

    private fun extract(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["extract"]?.jsonPrimitive?.content?.trim()
    }.getOrNull()

    private companion object {
        const val TAG = "WikipediaSummaries"
        const val WIKIDATA_BASE = "https://www.wikidata.org/w/api.php"
        const val WIKIPEDIA_BASE = "https://en.wikipedia.org/api/rest_v1/page/summary"
        const val USER_AGENT = "MassDroid/2.x ( https://github.com/sfortis/massdroid_native )"
        const val HTTP_NOT_FOUND = 404
        const val HTTP_UNAVAILABLE = 503
    }
}
