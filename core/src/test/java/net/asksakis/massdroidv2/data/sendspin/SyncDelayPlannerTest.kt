package net.asksakis.massdroidv2.data.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDelayPlannerTest {

    private fun SyncDelayPlan.member(id: String) = members.first { it.playerId == id }

    @Test
    fun `moves a late static member earlier and a late phone back onto the timestamp`() {
        // The measured case from 2026-10-02: the Pi 23 ms late at static 173, the
        // phone 15 ms late at sync delay 0.
        val plan = SyncDelayPlanner.plan(
            listOf(
                MemberMeasurement("pi", 23_000L, DelayControl.Static(currentMs = 173)),
                MemberMeasurement("phone", 15_000L, DelayControl.Signed(currentMs = 0)),
            )
        )

        assertEquals(0L, plan.targetUs)
        assertEquals(196, plan.member("pi").newValueMs)
        assertEquals(-15, plan.member("phone").newValueMs)
        assertEquals(0L, plan.member("pi").residualUs)
        assertEquals(0L, plan.member("phone").residualUs)
    }

    @Test
    fun `an early static member at zero moves the target instead of going negative`() {
        val plan = SyncDelayPlanner.plan(
            listOf(
                MemberMeasurement("early", -50_000L, DelayControl.Static(currentMs = 0)),
                MemberMeasurement("phone", 10_000L, DelayControl.Signed(currentMs = 0)),
            )
        )

        assertEquals(-50_000L, plan.targetUs)
        assertEquals(0, plan.member("early").newValueMs)
        assertEquals(-60, plan.member("phone").newValueMs)
        assertEquals(0L, plan.member("phone").residualUs)
    }

    @Test
    fun `a signed member that cannot reach reports what it misses by`() {
        val plan = SyncDelayPlanner.plan(
            listOf(
                MemberMeasurement("far", 1_500_000L, DelayControl.Signed(currentMs = 0)),
                MemberMeasurement("static", 0L, DelayControl.Static(currentMs = 0, maxMs = 100)),
            )
        )

        // far can reach 500..1500 ms, static only -100..0 ms: no shared band.
        val far = plan.member("far")
        val static = plan.member("static")
        assertEquals(-1000, far.newValueMs)
        assertTrue("far residual ${far.residualUs}", far.residualUs!! > 0)
        assertTrue("static residual ${static.residualUs}", static.residualUs!! < 0)
    }

    @Test
    fun `a member set on the device does not move the target and is reported`() {
        val plan = SyncDelayPlanner.plan(
            listOf(
                MemberMeasurement("esphome", 120_000L, DelayControl.None),
                MemberMeasurement("pi", 40_000L, DelayControl.Static(currentMs = 0)),
            )
        )

        assertEquals(0L, plan.targetUs)
        assertNull(plan.member("esphome").newValueMs)
        assertEquals(120_000L, plan.member("esphome").residualUs)
        assertEquals(40, plan.member("pi").newValueMs)
    }

    @Test
    fun `a member that was not heard keeps its delay`() {
        val plan = SyncDelayPlanner.plan(
            listOf(
                MemberMeasurement("silent", null, DelayControl.Static(currentMs = 62)),
                MemberMeasurement("phone", 20_000L, DelayControl.Signed(currentMs = 5)),
            )
        )

        assertNull(plan.member("silent").newValueMs)
        assertNull(plan.member("silent").residualUs)
        assertEquals(-15, plan.member("phone").newValueMs)
    }
}
