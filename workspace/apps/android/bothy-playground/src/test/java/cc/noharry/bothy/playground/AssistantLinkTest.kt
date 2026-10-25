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

    @Test
    fun theAnsweredCardCountsDownUnlessTouchedThenStaysUntilClosed() {
        val before = lastTurn.after(AssistantView())
        val answered = before.copy(request = "明天天气", reply = "多云", updatedAt = 5_000L).after(before)
        val folds = answered.closesAt(held = null)
        assertEquals(5_000L + answered.holdMs(), folds)
        assertFalse(answered.open(now = folds, dismissed = null))

        assertEquals("touched: no countdown", 0L, answered.closesAt(held = "明天天气"))
        assertTrue(answered.open(now = folds + 60_000L, dismissed = null, held = "明天天气"))
        assertFalse("closed with its X", answered.open(now = folds, dismissed = "明天天气", held = "明天天气"))
        assertEquals("while 小博 works it does not count", 0L, answered.copy(running = true).closesAt(held = null))
    }

    @Test
    fun theCardGoesWithADismissalButNotWithAnIdleEnd() {
        val answered = AssistantView(request = "明天天气", reply = "多云")

        assertFalse("never dismissed", answered.dismissedSince(seen = 0L))
        assertTrue(answered.copy(dismissedAt = 9_000L).dismissedSince(seen = 0L))
        assertFalse("the same dismissal, seen", answered.copy(dismissedAt = 9_000L).dismissedSince(seen = 9_000L))
    }

    @Test
    fun theCountdownWaitsUntil小博StopsTalking() {
        val before = lastTurn.after(AssistantView())
        val talking = before.copy(request = "明天天气", reply = "多云", speaking = true, updatedAt = 5_000L).after(before)
        assertEquals(0L, talking.closesAt(held = null))
        assertTrue("open however long it talks", talking.open(now = 500_000L, dismissed = null))
        val done = talking.copy(speaking = false, updatedAt = 30_000L).after(talking)
        assertEquals("the wait starts as it stops", 30_000L + done.holdMs(), done.closesAt(held = null))
    }

    @Test
    fun talkingAboutNothingNewDoesNotBringBackAFoldedCard() {
        val before = lastTurn.after(AssistantView())
        val answered = before.copy(request = "打开空调", reply = "开了", updatedAt = 5_000L).after(before)
        val later = 5_000L + answered.holdMs() + 60_000L
        // A minute after the card folded, the wake's 「我在」 carries the same, long-done request.
        val woken = answered.copy(listening = true, speaking = true, updatedAt = later).after(answered)

        assertFalse(woken.open(now = later, dismissed = null))
        assertEquals(answered.workedAt, woken.workedAt)
    }
}
