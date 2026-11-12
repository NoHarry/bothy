package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.USER_STEERING_LABEL
import cc.noharry.agent.appserver.protocol.USER_TURN_LABEL
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.toRenderItems
import cc.noharry.bothy.app.overlay.toCompact
import cc.noharry.bothy.app.overlay.toOverlayEntry
import cc.noharry.bothy.protocol.view.currentTurnItems
import org.junit.Assert.*
import org.junit.Test

class SteeringTurnLayoutTest {
    private val original = ConversationItem.TurnHeader(1, USER_TURN_LABEL, "Find a route", "Find a route")
    private val steer = ConversationItem.TurnHeader(3, USER_STEERING_LABEL, "Avoid highways", "Avoid highways")
    private val reply = ConversationItem.MessageItem(5, TranscriptRole.ASSISTANT, "Route ready", final = true)
    private val items = listOf(original, tool(2), steer, tool(4), reply)

    @Test fun overlayShowsUserLabelInsteadOfTheWireMarker() {
        assertEquals(USER_TURN_LABEL, steer.toCompact().title)
        assertEquals(USER_TURN_LABEL, steer.toOverlayEntry().title)
    }

    @Test fun phoneShowsSteeringAndOneLogicalProcessForBothLiveAndFinishedTurns() {
        for (running in listOf(true, false)) {
            val rows = phoneRows(items, running)
            assertEquals(listOf(original, steer), rows.filterIsInstance<PhoneRow.Asked>().map { it.header })
            if (running) {
                val process = rows.filterIsInstance<PhoneRow.Steps>().single()
                assertEquals(1L, process.turn)
                assertEquals(listOf(2L, 4L), process.steps.map { it.id })
            } else {
                val process = rows.filterIsInstance<PhoneRow.Folded>().single()
                assertEquals(1L, process.turn)
                assertEquals(2, process.tools)
                val footnote = rows.filterIsInstance<PhoneRow.Footnote>().single()
                assertEquals(1L, footnote.turn)
                assertEquals(2, footnote.steps)
            }
            assertEquals(reply, rows.filterIsInstance<PhoneRow.Shown>().single().item)
        }
    }

    @Test fun trajectoryAndOverlayKeepToolsBeforeAndAfterSteering() {
        assertEquals(listOf(2L, 4L), turnCalls(items, null).map { it.id })
        assertEquals(items.drop(1), items.currentTurnItems())
        val next = ConversationItem.TurnHeader(6, USER_TURN_LABEL, "Next task", "Next task")
        val conversation = items + next + tool(7)
        assertEquals(listOf(2L, 4L), turnCalls(conversation, 1).map { it.id })
        assertEquals(listOf(7L), turnCalls(conversation, null).map { it.id })
        assertEquals(listOf(tool(7)), conversation.currentTurnItems())
    }

    @Test fun consoleOnlySeparatesTheNextActualTurn() {
        val next = ConversationItem.TurnHeader(6, USER_TURN_LABEL, "Next task", "Next task")
        val rendered = (items + next).toRenderItems()
        assertEquals(listOf(6L), rendered.filterIsInstance<ConversationRenderItem.TurnDivider>().map { it.beforeTurnId })
        assertTrue(rendered.filterIsInstance<ConversationRenderItem.Single>().any { it.item == steer })
    }

    private fun tool(id: Long) = ConversationItem.ToolCallItem(
        id, "call-$id", "route_lookup", "{}", "{}", ToolCallStatus.DONE, startedAt = id, finishedAt = id + 1,
    )
}
