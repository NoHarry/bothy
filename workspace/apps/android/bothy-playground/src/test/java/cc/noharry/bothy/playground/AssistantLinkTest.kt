package cc.noharry.bothy.playground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the head unit opens 小博's card from Bothy's reports. */
class AssistantLinkTest {
    private val lastTurn = AssistantView(request = "到华强北还要多久？", reply = "还有 1.7 公里。", updatedAt = 1_000L)

    @Test
    fun theFirstReportSinceStartingOpensNoCardForATurnDoneBefore() {
        val woken = lastTurn.copy(listening = true).after(AssistantView())
        assertEquals(0L, woken.workedAt)
        assertFalse(woken.open(now = 1_500L, dismissed = null))
        assertTrue("only the slim bar", woken.hearing(now = 1_500L, dismissed = null))
    }

    @Test
    fun theFirstReportWhileStillRunningOpensTheCard() {
        val running = lastTurn.copy(running = true).after(AssistantView())
        assertTrue(running.open(now = 1_500L, dismissed = null))
    }

    @Test
    fun aNewRequestOrAnswerOpensTheCardAndHearingAloneKeepsItsTime() {
        val before = lastTurn.after(AssistantView())
        val asked = before.copy(request = "附近有充电站吗", updatedAt = 5_000L).after(before)
        assertEquals(5_000L, asked.workedAt)
        assertTrue(asked.open(now = 6_000L, dismissed = null))
        val heard = asked.copy(listening = true, heard = "再", updatedAt = 9_000L).after(asked)
        assertEquals(5_000L, heard.workedAt)
    }
}
