package net.asksakis.massdroidv2.domain.recommendation

/**
 * How many of the most recently enqueued items Music Assistant keeps as Autoplay seeds.
 *
 * MA 2.10 holds `enqueued_media_items` as a FIFO of ten (`queue_loader.py`). A mix is sent as
 * one `play_media` of individual tracks, so after it the FIFO holds the last ten tracks of the
 * list. When the mix ends, Autoplay samples its refill seeds from those ten, and the server's
 * play log also marks exactly those ten as plays the user chose (`user_initiated`).
 */
internal const val MA_AUTOPLAY_SEED_WINDOW = 10

/**
 * The most loved tracks the tail may hold, so a mix does not end on favourites only and
 * Autoplay continues from a blend of known and new tracks of the mix's kind.
 */
internal const val MAX_LOVED_IN_TAIL = MA_AUTOPLAY_SEED_WINDOW / 2

/**
 * Moves the mix's most trusted tracks to its end, so Music Assistant continues from them.
 *
 * The last ten tracks of a mix become the server's Autoplay seeds (see
 * [MA_AUTOPLAY_SEED_WINDOW]). Left to the interleave, those slots hold whatever the draw put
 * there, often a discovery pick. On 2026-10-16 one such pick, a French band, seeded an evening
 * of French artists the listener kept blocking, because Autoplay asked the provider for tracks
 * similar to it.
 *
 * The set of tracks is unchanged; only their order moves. The opener keeps its place, because it
 * was chosen for its own reason (see [MixEngine.buildFromCandidates]). Of the rest, the eligible
 * tracks are taken in [trustOrder] until the tail holds [tailSize] of them, with at most
 * [lovedMax] for which [isLoved] holds. Taking tracks out of the body shortens the distances the
 * interleave kept between tracks by one artist, so the body and then the tail are re-spaced with
 * the same [artistGap], counted across the point where they meet.
 *
 * @param isEligible whether a track may be an Autoplay seed at all.
 * @param trustOrder sorts eligible tracks from most to least trusted.
 * @param artistGap how many tracks must pass before an artist plays again, as the interleave
 *   used it; it shrinks to what the artists at hand allow.
 */
@Suppress("LongParameterList")
internal fun <T> orderAutoplaySeedTail(
    mix: List<T>,
    artistKeyOf: (T) -> String,
    isEligible: (T) -> Boolean,
    isLoved: (T) -> Boolean,
    trustOrder: Comparator<T>,
    artistGap: Int,
    tailSize: Int = MA_AUTOPLAY_SEED_WINDOW,
    lovedMax: Int = MAX_LOVED_IN_TAIL,
): List<T> {
    if (mix.size < 2 || tailSize <= 0) return mix
    val opener = mix.first()
    val rest = mix.drop(1)
    val ranked = rest.withIndex()
        .filter { isEligible(it.value) }
        .sortedWith(compareBy(trustOrder) { it.value })
    val picked = mutableSetOf<Int>()
    var loved = 0
    for ((index, track) in ranked) {
        if (picked.size >= tailSize) break
        if (isLoved(track)) {
            if (loved >= lovedMax) continue
            loved++
        }
        picked += index
    }
    if (picked.isEmpty()) return mix

    val body = rest.filterIndexed { index, _ -> index !in picked }
    val tail = rest.filterIndexed { index, _ -> index in picked }
    val placed = mutableListOf(artistKeyOf(opener))
    val spacedBody = spaceArtists(body, placed, gapFor(body, artistGap, artistKeyOf), artistKeyOf)
    val spacedTail = spaceArtists(tail, placed, gapFor(tail, artistGap, artistKeyOf), artistKeyOf)
    return listOf(opener) + spacedBody + spacedTail
}

/** [artistGap], shortened to what the distinct artists in [items] can satisfy. */
private fun <T> gapFor(items: List<T>, artistGap: Int, artistKeyOf: (T) -> String): Int =
    minOf(artistGap, (items.map(artistKeyOf).distinct().size - 1).coerceAtLeast(0))

/**
 * Reorders [items] so an artist does not return within [gap] tracks, when the items allow it.
 *
 * Greedy and order-preserving: each step takes the first remaining track whose artist is not
 * among the last [gap] placed. When every remaining artist is, it takes the one placed longest
 * ago. [placed] holds the artists already placed before [items] and receives each new one.
 */
private fun <T> spaceArtists(
    items: List<T>,
    placed: MutableList<String>,
    gap: Int,
    artistKeyOf: (T) -> String,
): List<T> {
    val remaining = items.toMutableList()
    val out = ArrayList<T>(items.size)
    while (remaining.isNotEmpty()) {
        val recent = placed.takeLast(gap)
        val index = remaining.indexOfFirst { artistKeyOf(it) !in recent }.takeIf { it >= 0 }
            ?: remaining.indices.minBy { recent.lastIndexOf(artistKeyOf(remaining[it])) }
        val next = remaining.removeAt(index)
        out += next
        placed += artistKeyOf(next)
    }
    return out
}
