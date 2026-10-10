package net.asksakis.massdroidv2.domain.recommendation

import kotlin.math.abs
import kotlin.math.pow

/**
 * How long it takes a track score to lose half of its weight.
 *
 * A track score used to be a plain running sum with no notion of time, so a
 * single skip kept a track out of mixes for good. Measured on a real database on
 * 2026-10-05: 530 tracks were suppressed by one skip and no listen, 1158
 * suppressed tracks had no feedback rows left (retention had pruned them) while
 * the score stayed, and 2202 of 19580 tracks (11%) were out of mixes, a number
 * that could only grow. With sixty days a signal still counts for half after two
 * months and for an eighth after six, so an old opinion gives way to new
 * listening without being forgotten at once.
 */
const val TRACK_SCORE_HALF_LIFE_DAYS = 60.0

/**
 * A track whose effective score is below this is left out of generated mixes.
 *
 * The harshest single skip scores -0.60, so one skip on its own can never cross
 * this line. Two negative signals inside roughly two months do: two hard skips
 * on the same day reach -1.20, while the same two skips 120 days apart reach
 * exactly -0.75 and stay in. The old line at -0.15 sat above every skip tier but
 * the mildest, which is how one skip came to suppress a track permanently.
 */
const val TRACK_SUPPRESSION_THRESHOLD = -0.75

/**
 * Below this magnitude a faded track score no longer changes anything, so a track
 * with no plays left may be removed by the cleanup.
 *
 * The cleanup used to remove only tracks whose STORED score was exactly 0, but the
 * fading is applied on read, so a track that ever received a signal stayed in the
 * database for good, and so did its artists. 0.05 is far from every line the score
 * is compared with: the suppression line at -0.75 and the seed floor at 0.30.
 */
const val TRACK_SCORE_FORGET_THRESHOLD = 0.05

private const val MILLIS_PER_DAY = 86_400_000.0
private const val HALF_LIFE_MS = TRACK_SCORE_HALF_LIFE_DAYS * MILLIS_PER_DAY
private const val HALF = 0.5

/**
 * The score a track carries [now], given the [stored] value written at
 * [updatedAt]. Positive and negative scores fade alike, toward zero.
 *
 * The decay is applied on read because SQLite on Android has no `pow` or `exp`,
 * so it cannot be expressed in a query. A clock that moved backwards is treated
 * as no time having passed rather than as a score growing.
 */
fun effectiveTrackScore(stored: Double, updatedAt: Long, now: Long): Double {
    if (stored == 0.0) return 0.0
    val ageMs = (now - updatedAt).coerceAtLeast(0L)
    return stored * HALF.pow(ageMs / HALF_LIFE_MS)
}

/**
 * The value to store when a signal worth [delta] arrives [now]: the faded score
 * plus the new signal. The caller stores it with `score_updated_at = now`, so
 * the fading restarts from the moment of the latest signal.
 */
fun addTrackSignal(stored: Double, updatedAt: Long, delta: Double, now: Long): Double =
    effectiveTrackScore(stored, updatedAt, now) + delta

/**
 * Whether a track is kept out of generated mixes. An explicit "Not for me"
 * ([dislikedAt] set) suppresses the track whatever its score and never fades;
 * otherwise the [effective] score decides.
 */
fun isTrackSuppressed(effective: Double, dislikedAt: Long?): Boolean =
    dislikedAt != null || effective < TRACK_SUPPRESSION_THRESHOLD

/**
 * The lowest STORED score that can still have an effective score of at least
 * [minEffective], for use as an exact SQL prefilter.
 *
 * Fading only moves a score toward zero. A stored score below a non-negative
 * floor therefore can never fade up to it, and the stored floor equals the
 * effective one. Below zero the reverse holds: a strongly negative score fades
 * up toward zero and can pass a negative floor, so no stored score can be
 * excluded in advance.
 */
fun storedTrackScoreFloor(minEffective: Double): Double =
    if (minEffective >= 0.0) minEffective else -Double.MAX_VALUE

/**
 * Whether a track's score has faded so far that forgetting the track loses
 * nothing. Positive and negative scores are judged alike, by magnitude.
 */
fun isTrackScoreForgotten(stored: Double, updatedAt: Long, now: Long): Boolean =
    abs(effectiveTrackScore(stored, updatedAt, now)) < TRACK_SCORE_FORGET_THRESHOLD
