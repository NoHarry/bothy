@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.session.TranscriptStore
import cc.noharry.agent.kernel.api.AgentEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A run in a session that is not selected writes its own session's live transcript, never the selected one's. */
class RuntimeSessionRunnerBackgroundTranscriptTest {
    @Test
    fun aRunBesideTheSelectedSessionWritesItsOwnTranscript() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            harness.coordinator.adoptActiveSessionId("session-b")
            // A client follows session-a, so it stays loaded once its run ends.
            harness.coordinator.transcripts.keepLoaded = { it == "session-a" }
            val background = RuntimeMultiSessionFixture("session-a", "run-a")
            harness.startPrepared(background)
            val live = harness.coordinator.transcriptOf("session-a")!!
            assertTrue(live.snapshot().first() is ConversationItem.TurnHeader)

            background.release.complete(Unit)
            assertTrue(awaitUntil { harness.awaitFinishedSnapshot("session-a") })
            assertTrue(harness.coordinator.transcriptOf("session-a") === live)
            assertTrue(harness.transcript.snapshot().isEmpty())

            harness.coordinator.transcripts.keepLoaded = { false }
            harness.coordinator.transcripts.release("session-a")
            assertNull(harness.coordinator.transcriptOf("session-a"))
        }
    }

    @Test
    fun aRunsEventsGoToItsSessionsTranscriptAndOnlyTheRunOnScreenSetsTheStatus() {
        val background = TranscriptStore().apply { openTurn("开空调") }
        val statuses = mutableListOf<String>()
        var changes = 0
        val handle = { event: AgentEvent, ownsUi: Boolean, transcript: TranscriptStore? ->
            handleScopedAgentEvent(event, ownsUi, transcript, setStatus = { statuses += it }, notifyChanged = { changes++ })
        }
        handle(AgentEvent.ResultAvailable(completedRuntimeResult("run-a")), false, background)
        assertTrue(background.snapshot().any { it is ConversationItem.MessageItem && it.role == TranscriptRole.ASSISTANT && it.content == "done" })
        handle(AgentEvent.Started("run-a"), false, background)
        assertTrue(statuses.isEmpty())
        handle(AgentEvent.Started("run-b"), true, TranscriptStore())
        assertTrue(statuses.isNotEmpty())
        val before = changes
        handle(AgentEvent.Started("run-c"), true, null)
        assertTrue(changes == before)
    }

    @Test
    fun anUnfollowedSessionIsLetGoWhenItsRunEnds() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            harness.coordinator.adoptActiveSessionId("session-b")
            val background = RuntimeMultiSessionFixture("session-a", "run-a")
            harness.startPrepared(background)
            assertTrue(harness.coordinator.transcriptOf("session-a") != null)
            background.release.complete(Unit)
            assertTrue(awaitUntil { harness.awaitFinishedSnapshot("session-a") && harness.coordinator.transcriptOf("session-a") == null })
        }
    }
}
