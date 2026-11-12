@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.bothy.server.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

internal fun testTurnStarted(runId: String) = ThreadEvent.TurnStarted("session-1", runId)
/** A turn that ended with [result], as the server hands it to the voice once it has read the run's outcome. */
internal fun testTurnCompleted(result: RuntimeResult) = AppServerTesting.completedTurn(ThreadEvent.TurnCompleted(TurnResult(
    result.runId, when (result.status) {
        RuntimeRunStatus.COMPLETED -> TurnStatus.COMPLETED
        RuntimeRunStatus.WAITING_USER_INPUT -> TurnStatus.WAITING_FOR_ANSWER
        RuntimeRunStatus.CANCELLED -> TurnStatus.INTERRUPTED
        else -> TurnStatus.FAILED
    }, result.response, emptyList(), error = result.errorCode?.let { TurnError(it, result.response) },
)), result)
internal fun testToolStarted(runId: String, toolName: String, arguments: String, toolCallId: String = toolName): ThreadEvent.ItemStarted {
    // The note the model wrote on this call ([testSpokenNote]); a tool that closes the reply takes none.
    val note = pendingNote.takeUnless { cc.noharry.bothy.server.voice.tts.closesReply(toolName) }
    val args = if (note == null) arguments else """{"call":${++notedCalls}}"""
    if (note != null) {
        pendingNote = null
        AppServerTesting.noteToolCall(toolName, args, note)
    }
    return ThreadEvent.ItemStarted(ThreadItem.ToolCall(toolCallId, runId, toolName, ToolSource.BACKEND, arguments = args))
}
internal fun testToolCompleted(runId: String, toolName: String, result: String, toolCallId: String = toolName, success: Boolean = true) =
    ThreadEvent.ItemCompleted(ThreadItem.ToolCall(toolCallId, runId, toolName, ToolSource.BACKEND, output = result, status = if (success) ItemStatus.COMPLETED else ItemStatus.FAILED))
internal fun testTurnFailed(runId: String, code: String, message: String, recoverable: Boolean = false) =
    ThreadEvent.TurnFailed(runId, TurnError(code, message, recoverable))
private var pendingNote: String? = null
private var notedCalls = 0

/**
 * The model writes [text] in the `say` field of its next tool call ([testToolStarted]). What it returns is the round's
 * visible text, which is not spoken.
 */
internal fun testSpokenNote(runId: String, text: String): ThreadEvent {
    pendingNote = text
    return ThreadEvent.ItemCompleted(ThreadItem.AgentMessage("$runId-message", runId, "", MessagePhase.COMMENTARY, ItemStatus.COMPLETED))
}
internal fun testReasoningDelta(runId: String, text: String) = ThreadEvent.ItemDelta("$runId-thinking", text)
internal fun testSearch(runId: String, provider: String, queryPreview: String = "", resultCount: Int? = null) =
    ThreadEvent.ItemCompleted(ThreadItem.WebSearch("search", runId, provider, queryPreview, resultCount))

internal class TestApproval(override val state: ApprovalState = ApprovalState.PENDING) : Approval() {
    override val id = "confirm-1"
    override val turnId = "run-1"
    override val tool = "web_fetch"
    override val arguments = "{}"
    override val domain = "example.com"
    override val reason = "Approval required"
    override val expiresAt = Long.MAX_VALUE
    override val status = if (state == ApprovalState.PENDING) ItemStatus.IN_PROGRESS else ItemStatus.COMPLETED
    override suspend fun approve(scope: ApprovalScope) = RespondResult.ACCEPTED
    override suspend fun deny(reason: String?) = RespondResult.ACCEPTED
}

internal fun testRetry(runId: String, attempt: Int, delayMillis: Long) = ThreadEvent.ItemCompleted(
    ThreadItem.Notice("$runId-retry-$attempt", runId, NoticeKind.RETRY, "Retry $attempt after $delayMillis ms"),
)
