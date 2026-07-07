package net.asksakis.massdroidv2.domain.recommendation

import androidx.annotation.VisibleForTesting

/**
 * Static genre -> family mapping over the Last.fm whitelist the app already
 * uses ([net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver] ALLOWED_GENRES)
 * plus common MA server genres. Deterministic family comparison replaces token
 * heuristics wherever both sides carry mapped tags: "techno" and "indie rock"
 * disagree by FAMILY, not by fragile substring luck. Unmapped tags fall back to
 * the loose token overlap at the call site.
 *
 * Families are a coarse safety net (is this the same musical world?), not a
 * taxonomy: border genres are placed with the scene they surface with in
 * Last.fm similars (shoegaze/dream pop with rock, psytrance with electronic).
 */
@Suppress("StringLiteralDuplication")
private val GENRE_FAMILY: Map<String, String> = buildMap {
    fun family(name: String, vararg genres: String) = genres.forEach { put(it, name) }
    family(
        "rock",
        "alternative", "alternative rock", "art rock", "blues rock", "britpop",
        "classic rock", "dream pop", "folk rock", "garage rock", "glam rock",
        "grunge", "hard rock", "indie", "indie pop", "indie rock", "krautrock",
        "new wave", "noise rock", "pop rock", "post punk", "post rock",
        "progressive", "progressive rock", "psychedelic", "psychedelic rock",
        "rock", "rockabilly", "shoegaze", "soft rock", "southern rock",
        "space rock", "stoner rock"
    )
    family(
        "punk",
        "emo", "hardcore", "hardcore punk", "melodic hardcore", "pop punk",
        "post hardcore", "punk", "punk rock", "screamo"
    )
    family(
        "metal",
        "alternative metal", "atmospheric black metal", "black metal",
        "brutal death metal", "death metal", "deathcore", "depressive black metal", "doom metal",
        "folk metal", "gothic metal", "grindcore", "heavy metal",
        "industrial metal", "mathcore", "melodic death metal", "melodic metal",
        "metal", "metalcore", "nu metal", "post metal", "power metal",
        "progressive metal", "sludge", "speed metal", "symphonic metal",
        "technical death metal", "thrash metal", "viking metal"
    )
    family(
        "electronic",
        "breakbeat", "breakcore", "club", "dance", "deep house",
        "drum and bass", "dubstep", "electro", "electronic", "electronica",
        "glitch", "house", "idm", "minimal", "progressive trance", "psytrance",
        "tech house", "techno", "trance"
    )
    family("pop", "ballad", "disco", "electropop", "pop", "synth pop", "synthpop")
    family("hip hop", "hip hop", "rap", "underground hip hop")
    family("jazz", "acid jazz", "fusion", "jazz", "jazz fusion", "nu jazz", "smooth jazz", "swing")
    family("classical", "baroque", "classical", "contemporary classical", "neoclassical")
    family(
        "folk",
        "acoustic", "americana", "celtic", "folk", "indie folk", "neofolk",
        "singer songwriter"
    )
    family("country", "alt country", "country")
    family("blues", "blues")
    family("soul", "funk", "rhythm and blues", "rnb", "soul")
    family("reggae", "dub", "reggae", "ska")
    family("world", "latin", "mpb", "world")
    family(
        "chill",
        "ambient", "chillout", "dark ambient", "downtempo", "easy listening",
        "lounge", "new age", "trip hop"
    )
    family("experimental", "avant garde", "drone", "experimental", "noise")
    family(
        "goth",
        "dark electro", "darkwave", "ebm", "ethereal", "goth", "gothic rock",
        "industrial", "industrial rock"
    )
}

// Glued-suffix fallback for single-word genres outside the curated map
// ("synthpop", "dubstep", "blackgaze"-style coinages). Order matters: more
// specific suffixes first ("punk" before "rock" never collides here because
// endsWith checks the full ordered list). Ambiguous suffixes ("wave" spans new
// wave/darkwave/synthwave, "core" spans punk and metal) are deliberately absent.
private val GLUED_SUFFIX_FAMILY: List<Pair<String, String>> = listOf(
    "punk" to "punk",
    "rock" to "rock",
    "metal" to "metal",
    "step" to "electronic",
    "tronica" to "electronic",
    "techno" to "electronic",
    "house" to "electronic",
    "trance" to "electronic",
    "hop" to "hip hop",
    "pop" to "pop",
    "jazz" to "jazz",
    "folk" to "folk",
    "billy" to "rock"
)

/**
 * Family of one normalized genre, or null. Resolution chain, from exact to
 * structural (so it generalizes to ANY user's provider/ID3 genres, not just
 * the Last.fm whitelist): exact curated entry -> last significant word looked
 * up in the same map ("deep tech house" -> "house" -> electronic, "greek
 * rock" -> rock) -> glued suffix ("synthpop" -> pop).
 */
private fun familyOf(normalized: String): String? {
    GENRE_FAMILY[normalized]?.let { return it }
    val lastWord = normalized.substringAfterLast(' ')
    if (lastWord != normalized) GENRE_FAMILY[lastWord]?.let { return it }
    return GLUED_SUFFIX_FAMILY.firstOrNull { (suffix, _) ->
        normalized.length > suffix.length && normalized.endsWith(suffix)
    }?.second
}

/** Families of the mapped tags in [genres]; unmapped tags contribute nothing. */
@VisibleForTesting
internal fun genreFamilies(genres: Iterable<String>): Set<String> =
    genres.mapNotNull { familyOf(normalizeGenre(it).replace('-', ' ')) }.toSet()
