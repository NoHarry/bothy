package cc.noharry.bothy.server.voice

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.USER_STEERING_LABEL
import cc.noharry.agent.appserver.protocol.USER_TURN_LABEL
import cc.noharry.agent.appserver.session.UiState
import cc.noharry.bothy.server.voice.realtime.latestLocalReply
import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringVoiceContextTest {
    @Test fun additionalUserWordsStayWithTheOriginalRequestAndItsReply() {
        val state = UiState(items = listOf(
            ConversationItem.TurnHeader(1, USER_TURN_LABEL, "Find a route", "Find a route"),
            ConversationItem.MessageItem(2, TranscriptRole.ASSISTANT, "Looking up routes"),
            ConversationItem.TurnHeader(3, USER_STEERING_LABEL, "Avoid highways", "Avoid highways"),
        ))
        assertEquals(listOf(true to "Find a route\nAvoid highways", false to "Looking up routes"), state.lastLocalExchange())
        assertEquals("Looking up routes", state.latestLocalReply())
        val next = state.copy(items = state.items + listOf(
            ConversationItem.TurnHeader(4, USER_TURN_LABEL, "Next task", "Next task"),
            ConversationItem.MessageItem(5, TranscriptRole.ASSISTANT, "Starting the next task"),
        ))
        assertEquals(listOf(true to "Next task", false to "Starting the next task"), next.lastLocalExchange())
        assertEquals("Starting the next task", next.latestLocalReply())
    }
}
