package cc.noharry.bothy.server.voice.realtime

import cc.noharry.agent.appserver.protocol.*
import cc.noharry.agent.appserver.protocol.client.ServerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RealtimeRunReplyTest {
    @Test fun normalCompletionPrefersTheSameRunsTerminalFeedbackToEarlierCommentary() = runBlocking {
        RealtimeWaits.begin()
        try {
            val consumer = RealtimeRunReply()
            consumer.observe(state("current", running = true))
            RealtimeWaits.hold("current", "The request failed. Please check the screen.")
            consumer.observe(state("current", running = false))
            assertEquals("The request failed. Please check the screen.", consumer.reply.await())
        } finally { RealtimeWaits.end() }
    }

    @Test fun anotherRunsHeldResultAndSessionSwitchCannotCompleteThisWait() = runBlocking {
        RealtimeWaits.begin()
        try {
            RealtimeWaits.hold("old", "old secret result")
            val consumer = RealtimeRunReply()
            consumer.observe(state("current", running = true))
            consumer.observe(state("other", running = false))
            assertFalse(consumer.reply.isCompleted)
            consumer.observe(state("current", running = false))
            assertEquals("Earlier commentary", consumer.reply.await())
        } finally { RealtimeWaits.end() }
        assertNull(RealtimeWaits.replyFor("old"))
    }

    private fun state(runId: String, running: Boolean) = ServerState(
        isRunning = running, currentRequest = CurrentRequest(runId, "request"),
        items = listOf(ConversationItem.TurnHeader(1, "request", "request", "request"),
            ConversationItem.MessageItem(2, TranscriptRole.ASSISTANT, "Earlier commentary")),
    )
}
