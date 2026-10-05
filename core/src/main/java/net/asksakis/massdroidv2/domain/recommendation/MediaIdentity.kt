package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import java.text.Normalizer

fun Artist.canonicalKey(): String? = MediaIdentity.canonicalArtistKey(itemId = itemId, uri = uri)
fun Album.canonicalKey(): String? = MediaIdentity.canonicalAlbumKey(itemId = itemId, uri = uri)
fun Track.canonicalKey(): String? = MediaIdentity.canonicalTrackKey(itemId = itemId, uri = uri)

/**
 * Whether this set of blocked artist keys covers [artistUri].
 *
 * The set holds canonical keys, so a provider URI has to be normalised before it can be
 * looked up. Five screens each wrote that normalisation out by hand, which is how the library
 * list and the repository ended up answering the same question two different ways.
 */
fun Set<String>.blocksArtist(artistUri: String?): Boolean {
    if (isEmpty() || artistUri == null) return false
    val key = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return false
    return key in this
}

@JvmName("artistScoresToMap")
fun List<ArtistScore>.toScoreMap(): Map<String, Double> = associate { it.artistUri to it.score }

/** Keyed by [genreKey], so a lookup with any spelling of the genre finds its score. */
@JvmName("genreScoresToMap")
fun List<GenreScore>.toScoreMap(): Map<String, Double> = associate { genreKey(it.genre) to it.score }

/**
 * The display and storage form of a genre name: trimmed and lowercased, with the
 * punctuation and spacing left as they are. Use it to show or store a name. Use
 * [genreKey] to decide whether two names are the same genre.
 */
fun normalizeGenre(genre: String): String = genre.trim().lowercase()

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_KEY_CHARS = Regex("[^a-z0-9]")
private const val ASCII_LIMIT = 128

/**
 * Identity of a genre: two names are the same genre exactly when their keys are equal.
 *
 * This mirrors the rule Music Assistant 2.10.5 uses to match genre names and aliases,
 * `create_safe_string(name, lowercase=True, replace_space=True)` in
 * music_assistant_models/helpers.py: lowercase and trim, transliterate to ASCII,
 * then drop every character outside [a-z0-9], spaces included. So "synth-pop",
 * "synthpop" and "synth pop" all share the key "synthpop", and the app merges
 * exactly what the server merges.
 *
 * It is needed because the plain lowercase form counted spellings separately
 * everywhere. On a real library on 2026-10-05, 518 genre names held only 508
 * distinct keys: synthpop had 926 track rows and 183 artist rows, synth-pop 193
 * and 123, synth pop 33 and 9, and the same split existed for post punk,
 * post rock, lo fi, darkwave, singer songwriter and four more. Genre Radio,
 * Smart Mix and Insights each saw three small genres where there was one.
 *
 * Transliteration is approximated by removing accents (Unicode NFD, then the
 * combining marks), which covers Latin script: "électro" and "electro" match.
 * Music Assistant's full anyascii step is not replicated: letters that do not
 * decompose (ø, æ, ß) are dropped rather than spelled out, and a name in a
 * non-Latin script has no ASCII letters left at all. When nothing is left, the
 * key falls back to [normalizeGenre] so such a genre still has an identity of
 * its own instead of colliding with every other one on an empty key.
 */
fun genreKey(name: String): String {
    val lowered = name.trim().lowercase()
    // Plain ASCII has nothing to decompose, and genre names almost always are.
    // This runs per track in the mix builders, so the Normalizer is skipped there.
    val unaccented = if (lowered.all { it.code < ASCII_LIMIT }) {
        lowered
    } else {
        Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()
    }
    return unaccented.replace(NON_KEY_CHARS, "").ifEmpty { normalizeGenre(name) }
}

/** The names of [genres] with one entry per [genreKey], keeping the first spelling seen. */
fun distinctGenres(genres: Iterable<String>): List<String> = genres.distinctBy(::genreKey)

/**
 * The spelling each genre is shown and stored under, keyed by [genreKey].
 *
 * [usesBySpelling] maps every spelling to how often the library uses it (rows in
 * `track_genres` plus `artist_genres`, or artists for provider genres). The most
 * used spelling wins, so the merge keeps the name most of the library already
 * carries. Ties go to the lexically smallest spelling, so the same input always
 * gives the same answer. Blank spellings are ignored.
 */
fun canonicalGenreSpellings(usesBySpelling: Map<String, Int>): Map<String, String> =
    usesBySpelling.entries
        .filter { it.key.isNotBlank() }
        .groupBy { genreKey(it.key) }
        .mapValues { (_, spellings) ->
            spellings.minWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            ).key
        }
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
 *
 * [scoreMap] is keyed by [genreKey], as [toScoreMap] builds it.
 */
fun genreAffinity(
    genres: Iterable<String>,
    scoreMap: Map<String, Double>,
    topN: Int = GENRE_AFFINITY_TOP_N
): Double {
    // Track genres come from the server, the score map from the database, so the
    // two meet by key: a "synth-pop" track must score against "synthpop". Two
    // spellings on one track are one genre, so they count once.
    val positives = genres
        .map(::genreKey)
        .distinct()
        .mapNotNull { scoreMap[it] }
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
