package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent
import cc.noharry.bothy.kernel.api.PendingConfirmation
import cc.noharry.bothy.kernel.api.reliability.RuntimeBlocker
import cc.noharry.bothy.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.bothy.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.bothy.kernel.api.reliability.RuntimeResult
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.kernel.api.reliability.SuggestedNextAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRuntimeDemoStateTest {
    @Test
    fun reducerFoldsRuntimeEventsIntoVisibleState() {
        val state = demoEvents().fold(DebugRuntimeDemoState()) { current, event ->
            current.reduce(event)
        }

        assertEquals("run-1", state.runId)
        assertEquals(RuntimeRunStatus.COMPLETED, state.finalStatus)
        assertEquals("done", state.assistantPreview)
        assertEquals("USER_INPUT_REQUIRED", state.errorCode)
        assertEquals(listOf("confirm-1"), state.pendingConfirmationIds)
        assertEquals("extra context", state.steeringPreview)
        assertEquals("exec output", state.execOutputPreview)
        assertEquals("host-workspace", state.workspaceId)
        assertEquals(1, state.retryCount)
        assertEquals(listOf("web_fetch"), state.finishedTools)
        assertEquals("/app/index.html", state.changedFiles.single().path)
        assertTrue(state.changedFiles.single().diffTimedOut)
        assertEquals(120, state.tokenUsage.inputTokens)
        assertEquals(12, state.tokenUsage.outputTokens)
    }

    @Test
    fun visibleSignalsCoverDemoAcceptanceEvents() {
        val state = demoEvents().fold(DebugRuntimeDemoState()) { current, event ->
            current.reduce(event)
        }

        assertEquals(
            listOf(
                "status",
                "thinking",
                "assistant",
                "confirmation",
                "steering",
                "exec",
                "workspace",
                "retry",
                "tools",
                "diff",
                "usage",
                "result",
                "error",
            ),
            state.visibleSignals(),
        )
    }

    @Test
    fun pendingConfirmationActionsStopAfterTerminalResult() {
        val waiting = DebugRuntimeDemoState().reduce(AgentEvent.ConfirmationRequired(pendingConfirmation()))
        val terminal = waiting.reduce(AgentEvent.ResultAvailable(RuntimeResult("run-1", RuntimeRunStatus.COMPLETED, "done")))

        assertEquals(listOf("confirm-1"), waiting.pendingConfirmationActionIds())
        assertEquals(emptyList<String>(), terminal.pendingConfirmationActionIds())
    }

    @Test
    fun resolvedConfirmationIsRemovedFromBoardActions() {
        val waiting = DebugRuntimeDemoState().reduce(AgentEvent.ConfirmationRequired(pendingConfirmation()))

        val resolved = waiting.resolvePendingConfirmation("confirm-1")

        assertEquals(emptyList<String>(), resolved.pendingConfirmationActionIds())
    }

    private fun demoEvents(): List<AgentEvent> =
        listOf(
            AgentEvent.Started("run-1"),
            AgentEvent.ThinkingDelta("run-1", "thinking"),
            AgentEvent.TextDelta("run-1", "partial"),
            AgentEvent.TokenUsageDelta("run-1", inputTokens = 120, outputTokens = 12),
            AgentEvent.ConfirmationRequired(pendingConfirmation()),
            AgentEvent.RuntimeBlocked(blocker()),
            AgentEvent.RuntimeProtocolRequestCreated(protocolRequest()),
            AgentEvent.RetryAttempted("run-1", attempt = 1, delayMillis = 10),
            AgentEvent.SteeringAccepted("run-1", "steer-1", "extra context"),
            AgentEvent.ExecOutputDelta("run-1", "exec output"),
            AgentEvent.WorkspaceChanged("run-1", "host-workspace"),
            AgentEvent.NativeToolCallStarted("run-1", "web_fetch", "{}", "tool-1"),
            AgentEvent.NativeToolCallFinished("run-1", "web_fetch", "{}", "tool-1"),
            AgentEvent.WorkspaceFileChanged(
                runId = "run-1",
                path = "/app/index.html",
                toolName = "write_file",
                bytes = 32,
                addedLines = 3,
                removedLines = 1,
                diffTimedOut = true,
            ),
            AgentEvent.ResultAvailable(RuntimeResult("run-1", RuntimeRunStatus.COMPLETED, "done")),
            AgentEvent.Finished("run-1"),
            AgentEvent.Failed("run-1", "USER_INPUT_REQUIRED", "need input", recoverable = true),
        )

    private fun pendingConfirmation(): PendingConfirmation =
        PendingConfirmation(
            pendingId = "confirm-1",
            sessionId = "session-1",
            runId = "run-1",
            expiresAtEpochMillis = 1_000,
            idempotencyKey = "confirm-key",
        )

    private fun blocker(): RuntimeBlocker =
        RuntimeBlocker(
            runId = "run-1",
            code = "USER_INPUT_REQUIRED",
            message = "Need input",
            suggestedNextAction = SuggestedNextAction.ASK_USER,
        )

    private fun protocolRequest(): RuntimeProtocolRequest =
        RuntimeProtocolRequest(
            protocolRequestId = "protocol-1",
            runId = "run-1",
            sessionId = "session-1",
            status = RuntimeProtocolRequestStatus.WAITING,
            kind = "question",
            payloadJson = "{}",
        )
}
