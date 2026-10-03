package net.asksakis.massdroidv2.data.sendspin

import kotlin.math.roundToInt

/** How a group member's playback position can be moved. */
sealed interface DelayControl {
    /** MA `sendspin_static_delay`: a larger value plays EARLIER (the client subtracts it). */
    data class Static(val currentMs: Int, val minMs: Int = 0, val maxMs: Int = 5000) : DelayControl

    /** A signed delay where a larger value plays LATER: MA's sync delay, or this phone's own. */
    data class Signed(val currentMs: Int, val minMs: Int = -1000, val maxMs: Int = 1000) : DelayControl

    /** The delay is set on the device itself (ESPHome, for one); it can only be reported. */
    data object None : DelayControl
}

/** One member as measured: [lagUs] after the server timestamp, or null if it was not heard. */
data class MemberMeasurement(val playerId: String, val lagUs: Long?, val control: DelayControl)

/**
 * What to do with one member. [newValueMs] is the delay to write, null when
 * nothing is written. [residualUs] is how far from the target the member still
 * plays afterwards, null when it was not heard.
 */
data class MemberPlan(
    val playerId: String,
    val lagUs: Long?,
    val control: DelayControl,
    val newValueMs: Int?,
    val residualUs: Long?,
)

data class SyncDelayPlan(val targetUs: Long, val members: List<MemberPlan>)

/**
 * Chooses the delay of every member so that all of them play at one common
 * instant, as close to the server timestamp as their delay ranges allow.
 *
 * Every adjustable member can reach a band of targets; the target is the
 * timestamp (0) clamped into the band they all share. When they share none,
 * the target sits between the bounds and each member gets as close as its
 * range allows, and the residual says by how much it misses. Members that
 * cannot be adjusted do not move the target; they are reported with their
 * residual, since the user has to set them on the device.
 */
object SyncDelayPlanner {

    fun plan(members: List<MemberMeasurement>): SyncDelayPlan {
        val bands = members.mapNotNull { m -> m.lagUs?.let { reachable(it, m.control) } }
        val low = bands.maxOfOrNull { it.first } ?: 0L
        val high = bands.minOfOrNull { it.last } ?: 0L
        val target = if (low <= high) 0L.coerceIn(low, high) else (low + high) / 2
        return SyncDelayPlan(target, members.map { planMember(it, target) })
    }

    /** The targets a member with this lag can be moved to, in microseconds. */
    private fun reachable(lagUs: Long, control: DelayControl): LongRange? = when (control) {
        // new = current + (lag - target) / 1000, within [min, max]
        is DelayControl.Static ->
            (lagUs - (control.maxMs - control.currentMs) * 1000L)..(lagUs - (control.minMs - control.currentMs) * 1000L)
        // new = current + (target - lag) / 1000, within [min, max]
        is DelayControl.Signed ->
            (lagUs + (control.minMs - control.currentMs) * 1000L)..(lagUs + (control.maxMs - control.currentMs) * 1000L)
        DelayControl.None -> null
    }

    private fun planMember(m: MemberMeasurement, target: Long): MemberPlan {
        val lag = m.lagUs ?: return MemberPlan(m.playerId, null, m.control, null, null)
        return when (val c = m.control) {
            is DelayControl.Static -> {
                val value = (c.currentMs + (lag - target) / 1000.0).roundToInt().coerceIn(c.minMs, c.maxMs)
                val after = lag - (value - c.currentMs) * 1000L
                MemberPlan(m.playerId, lag, c, value, after - target)
            }
            is DelayControl.Signed -> {
                val value = (c.currentMs + (target - lag) / 1000.0).roundToInt().coerceIn(c.minMs, c.maxMs)
                val after = lag + (value - c.currentMs) * 1000L
                MemberPlan(m.playerId, lag, c, value, after - target)
            }
            DelayControl.None -> MemberPlan(m.playerId, lag, c, null, lag - target)
        }
    }
}
