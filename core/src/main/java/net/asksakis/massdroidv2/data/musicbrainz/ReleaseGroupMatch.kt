package net.asksakis.massdroidv2.data.musicbrainz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.domain.recommendation.genreKey

/** One release group from a MusicBrainz search, reduced to what the match needs. */
internal data class ReleaseGroupCandidate(
    val id: String,
    val title: String,
    /** MusicBrainz primary type, lowercase: album, single, ep, broadcast, other. */
    val primaryType: String?,
    val artistNames: List<String>,
    val firstReleaseYear: Int?,
    val score: Int,
)

/**
 * Which MusicBrainz release group a Music Assistant album is, if any.
 *
 * A name search alone picks the wrong one. Searching "Warpaint" by Warpaint returned
 * "Warpaint on Warpaint" (type Other) at score 100 and the album itself at 99, so taking
 * the top hit described the wrong release. A candidate is therefore accepted only when its
 * title and one of its credited artists are the same names as the album's, compared by
 * [comparisonKey], and its type fits the album's. When the year is known, a candidate first
 * released that year wins; a reissue's year often differs, so no candidate is refused for it.
 */
internal object ReleaseGroupMatch {

    /** MusicBrainz's own match score below which a candidate is not considered. */
    const val MIN_SCORE = 90

    fun parse(json: Json, body: String): List<ReleaseGroupCandidate> = runCatching {
        json.parseToJsonElement(body).jsonObject["release-groups"]?.jsonArray.orEmpty().mapNotNull { element ->
            val group = element as? JsonObject ?: return@mapNotNull null
            val id = group["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReleaseGroupCandidate(
                id = id,
                title = group["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                primaryType = group["primary-type"]?.jsonPrimitive?.contentOrNull?.lowercase(),
                artistNames = group["artist-credit"]?.jsonArray.orEmpty().mapNotNull { credit ->
                    (credit as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                },
                firstReleaseYear = group["first-release-date"]?.jsonPrimitive?.contentOrNull
                    ?.take(YEAR_LENGTH)?.toIntOrNull(),
                score = group["score"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    fun pick(
        candidates: List<ReleaseGroupCandidate>,
        artistName: String,
        albumName: String,
        albumType: String?,
        year: Int?,
    ): ReleaseGroupCandidate? {
        val wantedTitle = comparisonKey(albumName)
        val wantedArtist = comparisonKey(artistName)
        if (wantedTitle.isEmpty() || wantedArtist.isEmpty()) return null
        val types = typesFor(albumType)
        val matching = candidates.filter { candidate ->
            candidate.score >= MIN_SCORE &&
                comparisonKey(candidate.title) == wantedTitle &&
                candidate.artistNames.any { comparisonKey(it) == wantedArtist } &&
                candidate.primaryType in types
        }
        if (year != null) matching.firstOrNull { it.firstReleaseYear == year }?.let { return it }
        return matching.maxByOrNull { it.score }
    }

    /**
     * The MusicBrainz types an album of this Music Assistant type can be. A compilation is
     * an album there, with "Compilation" as a secondary type. An unknown type accepts the
     * three that hold music.
     */
    private fun typesFor(albumType: String?): Set<String> = when (albumType?.lowercase()) {
        "album", "compilation" -> setOf("album")
        "single" -> setOf("single")
        "ep" -> setOf("ep")
        else -> setOf("album", "single", "ep")
    }

    /**
     * A name as the match compares it: what a provider adds to a title in brackets or after
     * a dash ("(Deluxe Edition)", "[Remastered]", "- Single") is dropped, and the rest goes
     * through [genreKey], which ignores case, accents and punctuation.
     */
    fun comparisonKey(name: String): String = genreKey(withoutProviderSuffix(name))

    /**
     * [name] without what a provider adds in brackets or after a dash. Used as the search
     * title too, because MusicBrainz titles carry none of it. Falls back to [name] when
     * nothing else is left.
     */
    fun withoutProviderSuffix(name: String): String =
        name.replace(BRACKETED, " ").replace(PROVIDER_SUFFIX, " ").trim().ifEmpty { name }

    private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
    private val PROVIDER_SUFFIX = Regex("""\s+-\s+(single|ep)\s*$""", RegexOption.IGNORE_CASE)
    private const val YEAR_LENGTH = 4
}
