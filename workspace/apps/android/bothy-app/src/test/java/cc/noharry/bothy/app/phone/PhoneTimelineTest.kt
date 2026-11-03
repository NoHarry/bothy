package cc.noharry.bothy.app.phone

import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTimelineTest {
    @Test
    fun aFinishedTurnFoldsItsProcessAndKeepsTheAnswerOutside() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗"),
                ConversationItem.MessageItem(2, TranscriptRole.THINKING, "先查路线"),
                ConversationItem.ToolCallItem(3, toolName = "navigation_route", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
                ConversationItem.MessageItem(4, TranscriptRole.STATUS, "Running navigation_route"),
                ConversationItem.MessageItem(5, TranscriptRole.ASSISTANT, "路线大约 42 分钟", final = true),
                ConversationItem.ToolCallItem(6, toolName = "finish", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
                ConversationItem.RuntimePhaseItem(7, "Runtime result COMPLETED", "路线大约 42 分钟"),
            ),
            running = false,
        )
        assertEquals(listOf("asked-1", "fold-1", "item-5", "foot-1"), rows.map { it.key })
        val folded = rows[1] as PhoneRow.Folded
        assertEquals(listOf("thought-2", "tool-3"), folded.rows.map { it.key })
        assertEquals(1, folded.toolCalls)
        assertEquals(0, folded.messages)
        val foot = rows[3] as PhoneRow.Footnote
        assertEquals("路线大约 42 分钟", foot.reply)
        assertEquals(2, foot.steps)
    }

    @Test
    fun theTurnStillGoingShowsItsStepsInOrder() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "找咖啡", "找咖啡"),
                ConversationItem.MessageItem(2, TranscriptRole.THINKING, "先搜附近"),
                ConversationItem.MessageItem(3, TranscriptRole.ASSISTANT, "我先找一下附近的店"),
                ConversationItem.ToolCallItem(4, toolName = "poi_search", argumentsJson = null, resultJson = null, status = ToolCallStatus.RUNNING, startedAt = 0L),
            ),
            running = true,
        )
        assertEquals(listOf("asked-1", "thought-2", "item-3", "tool-4"), rows.map { it.key })
    }

    @Test
    fun threeToolsInARowAreOneLine() {
        val tools = (2L..4L).map { ConversationItem.ToolCallItem(it, toolName = "read_file", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L) }
        val rows = phoneRows(listOf(ConversationItem.TurnHeader(1, "You", "读", "读")) + tools, running = true)
        assertEquals(listOf("asked-1", "tools-2"), rows.map { it.key })
        assertEquals(3, (rows[1] as PhoneRow.ToolGroup).tools.size)
    }

    @Test
    fun aFailedTurnStaysOpenWithAFailedFootnote() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "开空调", "开空调"),
                ConversationItem.ToolCallItem(2, toolName = "car_climate", argumentsJson = null, resultJson = null, status = ToolCallStatus.FAILED, startedAt = 0L),
                ConversationItem.MessageItem(3, TranscriptRole.ERROR, "车机没有回应"),
                ConversationItem.RuntimePhaseItem(4, "Runtime result FAILED", "车机没有回应"),
            ),
            running = false,
        )
        assertEquals(listOf("asked-1", "tool-2", "item-3", "foot-1"), rows.map { it.key })
        assertTrue((rows.last() as PhoneRow.Footnote).failed)
    }

    @Test
    fun anApprovalIsShownInItsTurn() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "删文件", "删文件"),
                ConversationItem.ConfirmationItem(2, pendingId = "p", mode = ConfirmationMode.ASK_EVERY_TIME, createdAt = 0L),
            ),
            running = true,
        )
        assertEquals(listOf("asked-1", "item-2"), rows.map { it.key })
    }

    @Test
    fun theRunClockCountsMinutesAndHours() {
        assertEquals("0:07", clock(7_000L))
        assertEquals("12:45", clock(765_000L))
        assertEquals("1:02:05", clock(3_725_000L))
    }
}
