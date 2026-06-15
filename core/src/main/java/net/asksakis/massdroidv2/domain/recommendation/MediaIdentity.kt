package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore

fun Artist.canonicalKey(): String? = MediaIdentity.canonicalArtistKey(itemId = itemId, uri = uri)
fun Album.canonicalKey(): String? = MediaIdentity.canonicalAlbumKey(itemId = itemId, uri = uri)
fun Track.canonicalKey(): String? = MediaIdentity.canonicalTrackKey(itemId = itemId, uri = uri)

@JvmName("artistScoresToMap")
fun List<ArtistScore>.toScoreMap(): Map<String, Double> = associate { it.artistUri to it.score }

@JvmName("genreScoresToMap")
fun List<GenreScore>.toScoreMap(): Map<String, Double> = associate { normalizeGenre(it.genre) to it.score }

/** Single canonical form for genre names across the app. */
fun normalizeGenre(genre: String): String = genre.trim().lowercase()

/**
 * Affinity of a set of genres against a (possibly log-domain) genre score map.
 *
 * BLL genre scores are an *activation* measure (how much / how recently a genre
 * was played), NOT a preference. A loved-but-binged genre can come out NEGATIVE
 * purely from `ln()` of a small activation sum; treating that as dislike is
 * wrong, so we clamp each genre to >= 0 here. Real dislikes (skips, blocks) are
 * handled on a separate channel via suppressed/blocked exclusion and are left
 * untouched.
 *
 * We average the strongest few genres instead of summing all of them, so an
 * over-tagged artist (e.g. 6 Spotify tags) does not out-score a precisely-tagged
 * one just by having more tags.
 */
fun genreAffinity(
    genres: Iterable<String>,
    scoreMap: Map<String, Double>,
    topN: Int = GENRE_AFFINITY_TOP_N
): Double {
    val positives = genres
        .mapNotNull { scoreMap[normalizeGenre(it)] }
        .filter { it > 0.0 }
        .sortedDescending()
    if (positives.isEmpty()) return 0.0
    val top = positives.take(topN)
    return top.sum() / top.size
}

private const val GENRE_AFFINITY_TOP_N = 2

object MediaIdentity {

    fun canonicalArtistKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalAlbumKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalTrackKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun artistKeyFromUri(uri: String?): String? = canonicalArtistKey(uri = uri)

    fun canonicalMediaKey(itemId: String? = null, uri: String? = null): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val normalizedUri = normalizeUri(raw)
            if (normalizedUri.isNotEmpty()) return normalizedUri
        }

        val direct = itemId?.trim().orEmpty()
        return direct.ifEmpty { null }
    }

    private fun normalizeUri(raw: String): String {
        return raw
            .substringBefore('#')
            .substringBefore('?')
            .trim()
    }
}
