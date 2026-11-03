package cc.noharry.bothy.app.phone

import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTimelineTest {
    @Test
    fun aTurnIsTheQuestionOneRunLineAndTheReply() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗"),
                ConversationItem.MessageItem(2, TranscriptRole.THINKING, "先查路线"),
                ConversationItem.ToolCallItem(3, toolName = "navigation_route", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
                ConversationItem.MessageItem(4, TranscriptRole.STATUS, "Running navigation_route"),
                ConversationItem.MessageItem(5, TranscriptRole.ASSISTANT, "路线大约 42 分钟", final = true),
                ConversationItem.RuntimePhaseItem(6, "Runtime result COMPLETED", "路线大约 42 分钟"),
            ),
            running = false,
        )
        assertEquals(listOf("asked-1", "run-1", "item-5"), rows.map { it.key })
        val run = rows[1] as PhoneRow.Run
        assertEquals(2, run.steps.size)
        assertEquals("COMPLETED", run.result)
        assertFalse(run.live)
    }

    @Test
    fun theTurnStillGoingHasItsRunLineBeforeAnyStep() {
        val rows = phoneRows(listOf(ConversationItem.TurnHeader(1, "You", "在吗", "在吗")), running = true)
        assertTrue((rows.last() as PhoneRow.Run).live)
    }

    @Test
    fun anApprovalIsShownInItsTurn() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "删文件", "删文件"),
                ConversationItem.ConfirmationItem(2, pendingId = "p", mode = cc.noharry.bothy.app.session.ConfirmationMode.ASK_EVERY_TIME, createdAt = 0L),
            ),
            running = true,
        )
        assertEquals(listOf("asked-1", "run-1", "item-2"), rows.map { it.key })
    }
}
