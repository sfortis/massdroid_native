package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When next and previous are drawn as available.
 *
 * The buttons used to be available at all times. With a radio as the only item of the queue
 * both of them did nothing, because the server has no other item to move to and a live
 * stream has no start to go back to.
 */
class SkipAvailabilityTest {

    private fun queue(
        index: Int,
        total: Int,
        duration: Double = 200.0,
        repeat: RepeatMode = RepeatMode.OFF,
        active: Boolean = true
    ) = QueueState(
        queueId = "q",
        active = active,
        currentIndex = index,
        totalItems = total,
        repeatMode = repeat,
        currentItem = QueueItem(queueItemId = "i", duration = duration)
    )

    @Test
    fun `a radio alone in the queue offers neither button`() {
        assertThat(queue(index = 0, total = 1, duration = 0.0).skipAvailability())
            .isEqualTo(SkipAvailability(previous = false, next = false))
    }

    @Test
    fun `the first track offers previous because the server restarts it`() {
        assertThat(queue(index = 0, total = 5).skipAvailability())
            .isEqualTo(SkipAvailability(previous = true, next = true))
    }

    @Test
    fun `the last track offers no next without repeat all`() {
        assertThat(queue(index = 4, total = 5).skipAvailability().next).isFalse()
    }

    @Test
    fun `repeat all offers next on the last track because the server wraps around`() {
        assertThat(queue(index = 4, total = 5, repeat = RepeatMode.ALL).skipAvailability().next).isTrue()
    }

    @Test
    fun `repeat one does not count, because a skip leaves the track`() {
        assertThat(queue(index = 4, total = 5, repeat = RepeatMode.ONE).skipAvailability().next).isFalse()
    }

    @Test
    fun `a live stream after other items still offers previous`() {
        assertThat(queue(index = 2, total = 3, duration = 0.0).skipAvailability())
            .isEqualTo(SkipAvailability(previous = true, next = false))
    }

    @Test
    fun `an empty queue offers neither button`() {
        assertThat(queue(index = 0, total = 0).skipAvailability())
            .isEqualTo(SkipAvailability(previous = false, next = false))
    }

    @Test
    fun `a queue another source has taken over leaves both to that source`() {
        assertThat(queue(index = 0, total = 1, duration = 0.0, active = false).skipAvailability())
            .isEqualTo(SkipAvailability.BOTH)
    }

    @Test
    fun `no queue yet leaves both available`() {
        val none: QueueState? = null
        assertThat(none.skipAvailability()).isEqualTo(SkipAvailability.BOTH)
    }
}
