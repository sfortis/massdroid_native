package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The hold after a 503 doubles while the 503s keep coming. A fixed ten seconds
 * retried once was usually still inside the server's busy spell: 86 give-ups in
 * one field day, at one to three requests a minute from our side.
 */
class MusicBrainzRateLimiterTest {

    @Test
    fun `the hold doubles on consecutive rate limits and stops at the cap`() {
        assertThat(MusicBrainzRateLimiter.penaltyMs(1, null)).isEqualTo(10_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(2, null)).isEqualTo(20_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(3, null)).isEqualTo(40_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(4, null)).isEqualTo(60_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(40, null)).isEqualTo(60_000L)
    }

    @Test
    fun `a longer Retry-After from the server wins, a shorter one does not shrink the hold`() {
        assertThat(MusicBrainzRateLimiter.penaltyMs(1, retryAfterSeconds = 30)).isEqualTo(30_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(3, retryAfterSeconds = 5)).isEqualTo(40_000L)
        assertThat(MusicBrainzRateLimiter.penaltyMs(1, retryAfterSeconds = 600)).isEqualTo(60_000L)
    }
}
