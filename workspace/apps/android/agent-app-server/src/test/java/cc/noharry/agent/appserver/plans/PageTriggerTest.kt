package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.protocol.PlanTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The triggers a plan made on a page (`plans/create`) may have: a time ahead, or a weekly time; not a watch. */
class PageTriggerTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aOnceMustBeAheadAndWithinAYear() {
        assertNull(pageTriggerError(PlanTrigger.Once(now + 60_000), now))
        assertEquals("the time has passed", pageTriggerError(PlanTrigger.Once(now), now))
        assertEquals("more than a year ahead", pageTriggerError(PlanTrigger.Once(now + 367 * day), now))
    }

    @Test
    fun aRepeatNeedsATimeOfDayAndDaysOfTheWeek() {
        assertNull(pageTriggerError(PlanTrigger.Repeat(8 * 60, setOf(1, 2, 3, 4, 5)), now))
        assertNotNull(pageTriggerError(PlanTrigger.Repeat(1500, setOf(1)), now))
        assertNotNull(pageTriggerError(PlanTrigger.Repeat(-1, setOf(1)), now))
        assertNotNull(pageTriggerError(PlanTrigger.Repeat(8 * 60, emptySet()), now))
        assertNotNull(pageTriggerError(PlanTrigger.Repeat(8 * 60, setOf(0, 8)), now))
    }
}
