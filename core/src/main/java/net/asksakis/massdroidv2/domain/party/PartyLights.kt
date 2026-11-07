package net.asksakis.massdroidv2.domain.party

/**
 * The timing of the Party screen's lights. Every function takes the milliseconds the
 * animation has run, so one clock drives all of them and the speed does not depend on the
 * display's frame rate.
 */
object PartyLights {
    /** One full turn of the mirror ball. */
    const val BALL_TURN_MS = 9_000L

    /** One full turn of the light beams. */
    const val BEAM_TURN_MS = 24_000L

    const val STROBE_CYCLE_MS = 1_000L

    /**
     * The strobe's opacity at [elapsedMs]: two flashes per second, peaking at [firstPeak] 3% and
     * at [secondPeak] 53% into the second, and dark between them. Two flashes a second stays
     * below the three a second that WCAG 2.3.1 allows.
     */
    fun strobeAlpha(elapsedMs: Long, firstPeak: Float, secondPeak: Float): Float {
        val phase = (elapsedMs % STROBE_CYCLE_MS).toFloat()
        return flash(phase, FIRST_FLASH_MS, firstPeak) + flash(phase, SECOND_FLASH_MS, secondPeak)
    }

    /** A flash peaking at [peakAt]: it rises over [FLASH_RISE_MS] and falls over [FLASH_FALL_MS]. */
    private fun flash(phase: Float, peakAt: Float, peak: Float): Float = when {
        phase < peakAt - FLASH_RISE_MS || phase > peakAt + FLASH_FALL_MS -> 0f
        phase <= peakAt -> peak * (phase - (peakAt - FLASH_RISE_MS)) / FLASH_RISE_MS
        else -> peak * (peakAt + FLASH_FALL_MS - phase) / FLASH_FALL_MS
    }

    private const val FIRST_FLASH_MS = 30f
    private const val SECOND_FLASH_MS = 530f
    private const val FLASH_RISE_MS = 30f
    private const val FLASH_FALL_MS = 60f
}

/**
 * The soft specks of light that drift across the Party screen. Each one has a fixed place,
 * size, speed and starting point in its cycle, drawn from a seeded generator, so the pattern
 * is the same on every visit.
 */
class PartySpecks(val count: Int = DEFAULT_COUNT) {
    /** The start position, as a fraction of the screen's width and height. */
    val startX = FloatArray(count)
    val startY = FloatArray(count)

    /** The diameter in dp. */
    val sizeDp = FloatArray(count)
    private val durationMs = LongArray(count)
    private val offsetMs = LongArray(count)

    init {
        var state = SEED
        fun next(): Double {
            state = state * 16_807L % 2_147_483_647L
            return state.toDouble() / 2_147_483_647L
        }
        for (i in 0 until count) {
            startX[i] = ((MIN_X_PERCENT + next() * (100 - MIN_X_PERCENT)) / 100).toFloat()
            startY[i] = (next() * MAX_Y_PERCENT / 100).toFloat()
            sizeDp[i] = (MIN_SIZE_DP + next() * SIZE_RANGE_DP).toFloat()
            offsetMs[i] = (next() * MAX_OFFSET_MS).toLong()
            durationMs[i] = (MIN_DURATION_MS + next() * DURATION_RANGE_MS).toLong()
        }
    }

    /** How far speck [index] is through its drift at [elapsedMs], from 0 to 1. */
    fun progress(index: Int, elapsedMs: Long): Float =
        ((elapsedMs + offsetMs[index]) % durationMs[index]).toFloat() / durationMs[index]

    companion object {
        const val DEFAULT_COUNT = 18

        /** How far a speck drifts over one cycle, in dp: to the left and a little down. */
        const val DRIFT_X_DP = -150f
        const val DRIFT_Y_DP = 30f

        /** A speck's opacity at [progress]: it fades in, holds, and fades out. */
        fun opacity(progress: Float): Float = when {
            progress < FADE_SHARE -> MAX_OPACITY * progress / FADE_SHARE
            progress > 1 - FADE_SHARE -> MAX_OPACITY * (1 - progress) / FADE_SHARE
            else -> MAX_OPACITY
        }

        private const val SEED = 11L
        private const val MIN_X_PERCENT = 10.0
        private const val MAX_Y_PERCENT = 95.0
        private const val MIN_SIZE_DP = 5.0
        private const val SIZE_RANGE_DP = 10.0
        private const val MAX_OFFSET_MS = 6_000.0
        private const val MIN_DURATION_MS = 4_000.0
        private const val DURATION_RANGE_MS = 4_000.0
        private const val FADE_SHARE = 0.15f
        private const val MAX_OPACITY = 0.9f
    }
}
