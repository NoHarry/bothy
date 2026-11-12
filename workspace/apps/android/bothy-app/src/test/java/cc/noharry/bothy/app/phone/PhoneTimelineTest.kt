package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
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
        assertEquals(listOf(2L, 3L), folded.steps.map { it.id })
        assertEquals(1, folded.thoughts)
        assertEquals(1, folded.tools)
        assertTrue(folded.interim.isEmpty())
        val foot = rows[3] as PhoneRow.Footnote
        assertEquals("路线大约 42 分钟", foot.reply)
        assertEquals(1, foot.steps)
    }

    @Test
    fun aFootnoteOpensItsOwnTurnsCalls() {
        val items = listOf(
            ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗"),
            ConversationItem.ToolCallItem(2, toolName = "navigation_route", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
            ConversationItem.TurnHeader(3, "You", "开空调", "开空调"),
            ConversationItem.ToolCallItem(4, toolName = "car_climate", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
            ConversationItem.ToolCallItem(5, toolName = "finish", argumentsJson = null, resultJson = "{}", status = ToolCallStatus.DONE, startedAt = 0L),
        )
        assertEquals(listOf(2L), turnCalls(items, turn = 1).map { it.id })
        assertEquals(listOf(4L), turnCalls(items, turn = 3).map { it.id })
        assertEquals(listOf(4L), turnCalls(items, turn = null).map { it.id })
        assertEquals(listOf(4L), turnCalls(items, turn = 99).map { it.id })
    }

    @Test
    fun theTurnStillGoingShowsItsStepsAsOneCardThenWhatItSays() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "找咖啡", "找咖啡"),
                ConversationItem.MessageItem(2, TranscriptRole.THINKING, "先搜附近"),
                ConversationItem.MessageItem(3, TranscriptRole.ASSISTANT, "我先找一下附近的店"),
                ConversationItem.ToolCallItem(4, toolName = "poi_search", argumentsJson = null, resultJson = null, status = ToolCallStatus.RUNNING, startedAt = 0L),
            ),
            running = true,
        )
        assertEquals(listOf("asked-1", "steps-1", "item-3"), rows.map { it.key })
        val steps = rows[1] as PhoneRow.Steps
        assertEquals(listOf(2L, 4L), steps.steps.map { it.id })
        assertTrue(steps.live)
    }

    @Test
    fun aTurnJustStartedShowsThatItIsWorking() {
        val rows = phoneRows(listOf(ConversationItem.TurnHeader(1, "You", "读", "读")), running = true)
        assertEquals(listOf("asked-1", "steps-1"), rows.map { it.key })
        assertTrue((rows[1] as PhoneRow.Steps).steps.isEmpty())
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
        assertEquals(listOf("asked-1", "steps-1", "item-3", "foot-1"), rows.map { it.key })
        assertTrue((rows[1] as PhoneRow.Steps).failed)
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
        assertEquals(listOf("asked-1", "steps-1", "approval-2"), rows.map { it.key })
        assertEquals("p", (rows[1] as PhoneRow.Steps).waiting?.pendingId)
    }

    @Test
    fun aPlanTheTurnMadeComesUnderItsAnswer() {
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "明早提醒我", "明早提醒我"),
                ConversationItem.ToolCallItem(
                    2, toolName = "mcp__plans__plan_manage", argumentsJson = """{"action":"create"}""",
                    resultJson = """{"ok":true,"id":"p1"}""", status = ToolCallStatus.DONE, startedAt = 0L,
                ),
                ConversationItem.MessageItem(3, TranscriptRole.ASSISTANT, "好，明早 8 点提醒你", final = true),
                ConversationItem.RuntimePhaseItem(4, "Runtime result COMPLETED", ""),
            ),
            running = false,
        )
        assertEquals(listOf("asked-1", "fold-1", "item-3", "foot-1", "plan-1-p1"), rows.map { it.key })
    }

    @Test
    fun aPageTheTurnDrewShowsBeforeItsAnswer() {
        val drawn = """{"ok":true,"page":"drawn-pages/c2Vzcw/page-mh3k-abcd.html","title":"三款对比"}"""
        val rows = phoneRows(
            listOf(
                ConversationItem.TurnHeader(1, "You", "比较三款耳机", "比较三款耳机"),
                ConversationItem.ToolCallItem(2, toolName = "mcp__pages__show_html", argumentsJson = null, resultJson = drawn, status = ToolCallStatus.DONE, startedAt = 0L),
                ConversationItem.MessageItem(3, TranscriptRole.ASSISTANT, "点上面的标签切换", final = true),
                ConversationItem.RuntimePhaseItem(4, "Runtime result COMPLETED", "点上面的标签切换"),
            ),
            running = false,
        )
        assertEquals(listOf("asked-1", "fold-1", "drawn-page-2", "item-3", "foot-1"), rows.map { it.key })
    }
}
