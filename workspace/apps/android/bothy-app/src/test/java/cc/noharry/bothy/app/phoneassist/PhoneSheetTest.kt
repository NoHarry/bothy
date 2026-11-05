package cc.noharry.bothy.app.phoneassist

import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.client.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneSheetTest {
    private val items = listOf(
        ConversationItem.TurnHeader(1, "You", "打开大众点评", "打开大众点评"),
        ConversationItem.ToolCallItem(2, toolName = "web_fetch", argumentsJson = null, resultJson = null, status = ToolCallStatus.RUNNING, startedAt = 0L),
        ConversationItem.ConfirmationItem(
            3,
            pendingId = "p1",
            domain = "www.dianping.com",
            mode = ConfirmationMode.ASK_EVERY_TIME,
            status = ConfirmationStatus.PENDING,
            createdAt = 0L,
        ),
    )

    @Test
    fun theSheetAsksForTheApprovalTheRunWaitsForWithTheCallThatAsked() {
        val waiting = ServerState(connected = true, isRunning = true, items = items).waitingApproval()
        assertEquals("p1", waiting?.item?.pendingId)
        assertEquals(2L, waiting?.tool?.id)
    }

    @Test
    fun anApprovalLeftPendingByARunThatEndedIsNotAsked() {
        assertNull(ServerState(connected = true, isRunning = false, items = items).waitingApproval())
    }
}
