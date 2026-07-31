package net.asksakis.massdroidv2.domain.recommendation

import androidx.annotation.VisibleForTesting

/**
 * Static genre -> family mapping, covering what Music Assistant, MusicBrainz and
 * ID3 tags actually report. Deterministic family comparison replaces token
 * heuristics wherever both sides carry mapped tags: "techno" and "indie rock"
 * disagree by FAMILY, not by fragile substring luck. Unmapped tags fall back to
 * the loose token overlap at the call site.
 *
 * Families are a coarse safety net (is this the same musical world?), not a
 * taxonomy: border genres are placed with the scene they surface with
 * (shoegaze/dream pop with rock, psytrance with electronic).
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
        "electropop", "glitch", "house", "idm", "minimal", "progressive trance",
        "psytrance", "synth pop", "synthpop", "tech house", "techno", "trance",
        // The synthwave scene. "wave" is NOT in GLUED_SUFFIX_FAMILY on purpose
        // (it spans new wave / darkwave / synthwave, which sit in three different
        // families), so every member is listed here by hand. Measured on a real
        // library: synthwave was the most-dropped lead tag of all, 14 artists.
        "chillwave", "dreamwave", "electroclash", "new retro wave", "retrowave",
        "synthwave"
    )
    // Synth pop / electropop sit with ELECTRONIC, not pop: they surface with
    // the electronic scene, and keeping them under "pop" let
    // darkwave/synthpop acts (NNHMN, Das Beat, Alphaville) into a vintage
    // jazz-pop mix through their weakest tag.
    family("pop", "ballad", "disco", "pop")
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
        "industrial", "industrial rock",
        // Cold/minimal synth is the darkwave side of the synth revival, not the
        // synthwave side: it surfaces with Ash Code / NNHMN / Hante., not with
        // The Midnight. Co-occurrence on the real library agrees independently
        // (minimal synth -> goth 44%).
        "coldwave", "minimal synth", "minimal wave"
    )
    // Genres no suffix rule can reach. "blackgaze"/"doomgaze" are metal despite
    // the shoegaze root ("gaze" is not a suffix rule: it would also catch
    // shoegaze, which is rock). "psychill" is psychedelic ambient, not trance.
    family("metal", "blackgaze", "doomgaze")
    family("chill", "psychill")
    family("world", "bossa nova")
    // "noise pop" would fall to pop on the last word, but it is the Jesus and
    // Mary Chain lineage: indie/rock with noise, not pop.
    family("rock", "noise pop")
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
 * the curated ones): exact curated entry -> last significant word looked
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

/**
 * Families that sit next to each other closely enough that a mix anchored on
 * one should still accept the other: shared repertoire, shared players, shared
 * audience. Deliberately tiny and symmetric. It exists to stop false drops the
 * dominant-family rule would otherwise cause (a retro-soul act like Lake Street
 * Dive or Paloma Faith being thrown out of a vintage jazz/swing mix), NOT to
 * re-open the leaks the rule closes: chill and electronic are NOT adjacent,
 * which is what kept trance and IDM out of a lounge mix.
 */
private val ADJACENT_FAMILIES: Map<String, Set<String>> = mapOf(
    "jazz" to setOf("soul", "blues"),
    "soul" to setOf("jazz", "blues"),
    "blues" to setOf("jazz", "soul"),
    "folk" to setOf("country"),
    "country" to setOf("folk")
)

/** [families] plus the families that neighbour them (see [ADJACENT_FAMILIES]). */
@VisibleForTesting
internal fun withAdjacentFamilies(families: Set<String>): Set<String> =
    if (families.isEmpty()) families
    else families + families.flatMap { ADJACENT_FAMILIES[it].orEmpty() }

/**
 * The family of the FIRST mapped tag, i.e. what the artist mostly is.
 * MusicBrainz and Music Assistant both report genres weight-descending and the
 * resolvers preserve that order, so tag #1 is the dominant genre and later tags
 * are side notes.
 * Comparing the WHOLE family set let an artist into a mix through their
 * weakest tag (Robert Miles `trance, electronic, ambient` entered a lounge mix
 * on "ambient"; NNHMN `darkwave, electronic, synthpop` entered a swing mix on
 * "synthpop"), so the gate uses this instead. Null when no tag is mapped.
 */
@VisibleForTesting
internal fun dominantFamily(genres: Iterable<String>): String? =
    genres.firstNotNullOfOrNull { familyOf(normalizeGenre(it).replace('-', ' ')) }

/**
 * The first genre that [dominantFamily] would resolve on, i.e. what the artist
 * mostly IS, kept as the genre itself rather than its family. Used to name a mix
 * for the user ("Post punk mix ready"), where "rock" would be uselessly broad.
 *
 * Skips leading unmapped tags on purpose: mood/format tags like "instrumental" or
 * "soundtrack" often sort first but say nothing about the genre. Null when no tag
 * maps at all, and the caller should then say nothing rather than guess.
 */
@VisibleForTesting
internal fun dominantGenre(genres: Iterable<String>): String? =
    genres.firstOrNull { familyOf(normalizeGenre(it).replace('-', ' ')) != null }
        ?.let { normalizeGenre(it) }

/**
 * Reorder an UNORDERED genre list so that the best-represented family comes
 * first, making [dominantFamily] meaningful on it.
 *
 * Resolved genres arrive weight-descending, but the DB's `artist_genres` is a set
 * (alphabetical, deduplicated), and reading "the first tag" there is reading the
 * alphabet: IAMX is stored as `alternative, electronic, house, psychedelic,
 * synthpop, trance`, so it counted as ROCK because "alternative" sorts first,
 * even though four of its six tags are electronic. Ties keep the original
 * order, so the result is deterministic.
 */
@VisibleForTesting
internal fun orderByFamilyFrequency(genres: List<String>): List<String> {
    if (genres.size < 2) return genres
    val counts = genres.groupingBy { familyOf(normalizeGenre(it).replace('-', ' ')) }.eachCount()
    return genres.sortedByDescending { counts[familyOf(normalizeGenre(it).replace('-', ' '))] ?: 0 }
}
