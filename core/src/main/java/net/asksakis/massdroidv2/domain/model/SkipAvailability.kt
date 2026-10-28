package net.asksakis.massdroidv2.domain.model

/**
 * Whether next and previous would move playback, so the player screens can draw a button
 * that does nothing as unavailable.
 *
 * The rule follows what the server does with the two commands, not what the Music Assistant
 * frontend draws. The frontend reads the queue's `can_next_previous` source flag, which is
 * true whenever the queue is running, so it offers next on a radio that is the only item in
 * the queue, and the server then ignores the press.
 */
data class SkipAvailability(val previous: Boolean, val next: Boolean) {
    companion object {
        val BOTH = SkipAvailability(previous = true, next = true)
    }
}

/**
 * The server answers next with the following item, or with the first one when repeat all is
 * on, and does nothing on the last item otherwise. Don't Stop the Music and radio sources
 * add their tracks to the queue before it runs out, so the item count already includes them.
 *
 * The server answers previous with the item before, and on the first item it plays that item
 * again from the start. A live stream has no duration and nothing to restart, so previous
 * is unavailable on a first item without one.
 *
 * A queue that is not loaded yet, or that another source has taken over, leaves both buttons
 * available. In that state the server, or the source, decides what a press does.
 */
fun QueueState?.skipAvailability(): SkipAvailability {
    if (this == null || !active) return SkipAvailability.BOTH
    if (totalItems <= 0) return SkipAvailability(previous = false, next = false)
    val hasNextItem = currentIndex < totalItems - 1
    val hasPreviousItem = currentIndex > 0
    val canRestart = (currentItem?.duration ?: 0.0) > 0.0
    return SkipAvailability(
        previous = hasPreviousItem || canRestart,
        next = hasNextItem || repeatMode == RepeatMode.ALL
    )
}
