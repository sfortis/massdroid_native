package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.repository.GenrePlayRow

/**
 * Which genre families may ANCHOR a mix, i.e. become the cluster a whole mix is
 * built around.
 *
 * The seed pool contains fringe families that exist there mostly as residue of the
 * engine's own output: mixes served a few hip hop tracks over the months, each
 * passive non-skip counted as a play, and one day the Variety rotation anchored a
 * whole mix on Guts for a listener who never once chose hip hop. Measured on that
 * library: hip hop was 0.47% of 365-day listening and experimental 0.86%, and both
 * produced mixes the listener rejected outright, while every family at or above ~1%
 * (jazz 1.06%, goth 2.01%, folk, metal, pop) was a genuine taste.
 *
 * Two ways in:
 * - The family holds at least [minShare] of the window's plays. Counted with a
 *   MAJORITY family per track, not per tag: many rock/electronic acts carry an
 *   "experimental" side tag, and counting tags let experimental report 1.7% when
 *   tracks that ARE experimental accounted for 0.86%.
 * - The family has at least [organicDoorPlays] plays the listener chose themselves
 *   (`origin = organic`). This is what lets a NEW taste anchor within days instead
 *   of after the ~200 plays the share floor would demand, and mix-served plays
 *   cannot open it.
 *
 * Recency weighting (BLL) was measured and REJECTED for this: it put jazz at 0.60%
 * and goth at 0.55% (both genuine) while lifting reggae to 1.37%, because the most
 * recent plays are precisely what the last mixes served.
 *
 * An empty result means the history is too thin to judge, and the caller must treat
 * it as "no restriction" rather than "nothing may anchor".
 */
fun anchorFamilies(
    rows: List<GenrePlayRow>,
    organicRows: List<GenrePlayRow>,
    minShare: Double,
    organicDoorPlays: Int
): Set<String> {
    val shares = familyPlayShares(rows)
    val total = shares.values.sum()
    if (total <= 0) return emptySet()
    val byShare = shares.filterValues { it.toDouble() / total >= minShare }.keys
    val byOrganic = familyPlayShares(organicRows).filterValues { it >= organicDoorPlays }.keys
    return byShare + byOrganic
}

/**
 * Plays per family, with each track voting for ONE family: the majority family of
 * its tags (ties resolved by tag order, matching [dominantFamily]'s preference for
 * the leading tag).
 */
private fun familyPlayShares(rows: List<GenrePlayRow>): Map<String, Int> {
    if (rows.isEmpty()) return emptyMap()
    val out = HashMap<String, Int>()
    rows.groupBy { it.trackUri }.forEach { (_, trackRows) ->
        val votes = LinkedHashMap<String, Int>()
        for (row in trackRows) {
            val family = genreFamilies(listOf(row.genre)).firstOrNull() ?: continue
            votes[family] = (votes[family] ?: 0) + 1
        }
        val family = votes.maxByOrNull { it.value }?.key ?: return@forEach
        val plays = trackRows.maxOf { it.plays }
        out[family] = (out[family] ?: 0) + plays
    }
    return out
}
