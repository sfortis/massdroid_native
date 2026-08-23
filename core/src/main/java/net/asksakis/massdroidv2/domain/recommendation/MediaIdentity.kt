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
 * Identity of a recording by what it IS rather than by which uri serves it:
 * lead artist and title, lowercased with punctuation and spacing flattened.
 *
 * Needed because the same recording legitimately exists under several uris. On a
 * real library, 5 of the 22 tracks the listener had explicitly disliked were also
 * stored under a second uri (once as `deezer://`, once as `library://`, or as two
 * releases of the same song), so a uri-keyed rejection let the other copy straight
 * back in. Music Assistant also resolves a requested track to a different version
 * of its own accord.
 *
 * Blank when neither part is usable, which callers must treat as "no identity" and
 * fall back to the uri rather than matching everything.
 */
fun trackIdentityKey(artist: String?, title: String?): String {
    val a = flattenTrackText(artist.orEmpty())
    val t = flattenTrackText(title.orEmpty())
    return if (a.isNotBlank() && t.isNotBlank()) "$a|$t" else ""
}

/**
 * Lowercases and flattens punctuation and spacing, so "Sigur Rós" and "sigur ros"
 * or "Hoppipolla (Remastered)" and "hoppipolla remastered" compare equal. The
 * shared primitive behind [trackIdentityKey] and the artist bucket key.
 */
fun flattenTrackText(value: String): String =
    value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()


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
