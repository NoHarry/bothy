@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus

internal data class DebugRuntimeDemoState(
    val runId: String = "",
    val status: String = "Idle",
    val thinkingPreview: String = "",
    val assistantPreview: String = "",
    val pendingConfirmationIds: List<String> = emptyList(),
    val steeringPreview: String = "",
    val execOutputPreview: String = "",
    val workspaceId: String = "",
    val retryCount: Int = 0,
    val runningTools: List<String> = emptyList(),
    val finishedTools: List<String> = emptyList(),
    val changedFiles: List<DebugRuntimeFileChange> = emptyList(),
    val tokenUsage: DebugRuntimeTokenUsage = DebugRuntimeTokenUsage(),
    val finalStatus: RuntimeRunStatus? = null,
    val errorCode: String = "",
)

internal data class DebugRuntimeFileChange(
    val path: String,
    val addedLines: Int,
    val removedLines: Int,
    val diffTimedOut: Boolean,
)

internal data class DebugRuntimeTokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

internal fun DebugRuntimeDemoState.reduce(event: AgentEvent): DebugRuntimeDemoState =
    when (event) {
        is AgentEvent.Started -> copy(runId = event.runId, status = "Running", finalStatus = null, errorCode = "")
        is AgentEvent.ThinkingDelta -> copy(runId = event.runId, thinkingPreview = appendPreview(thinkingPreview, event.text))
        is AgentEvent.TextDelta -> copy(runId = event.runId, assistantPreview = appendPreview(assistantPreview, event.text))
        is AgentEvent.TokenUsageDelta -> copy(runId = event.runId, tokenUsage = event.toDemoTokenUsage())
        is AgentEvent.ConfirmationRequired -> copy(
            runId = event.pending.runId,
            status = "Waiting confirmation",
            pendingConfirmationIds = (pendingConfirmationIds + event.pending.pendingId).distinct(),
        )
        is AgentEvent.RuntimeBlocked -> copy(runId = event.blocker.runId, status = "Blocked", errorCode = event.blocker.code)
        is AgentEvent.RuntimeProtocolRequestCreated -> copy(
            runId = event.protocolRequest.runId,
            status = "Waiting protocol request",
        )
        is AgentEvent.RetryAttempted -> copy(runId = event.runId, status = "Retrying", retryCount = retryCount + 1)
        is AgentEvent.BudgetWarning -> copy(runId = event.runId, status = "Budget warning")
        is AgentEvent.CompactionApplied -> copy(runId = event.runId, status = "Compacted")
        is AgentEvent.EmptyCompletionObserved -> copy(
            runId = event.runId,
            status = if (event.continued) "Empty completion continue" else "Empty completion",
        )
        is AgentEvent.SteeringAccepted -> copy(runId = event.runId, steeringPreview = event.textPreview)
        is AgentEvent.UserInteraction -> copy(runId = event.runId)
        is AgentEvent.ExecOutputDelta -> copy(runId = event.runId, execOutputPreview = appendPreview(execOutputPreview, event.text))
        is AgentEvent.WorkspaceChanged -> copy(runId = event.runId, status = "Workspace selected", workspaceId = event.workspaceId)
        is AgentEvent.NetworkToolActivity -> copy(runId = event.runId, status = "Network ${event.decision}")
        is AgentEvent.SearchActivity -> copy(runId = event.runId, status = "Search activity")
        is AgentEvent.NativeToolCallStarted -> toolStarted(event)
        is AgentEvent.NativeToolCallFinished -> toolFinished(event)
        is AgentEvent.WorkspaceFileChanged -> fileChanged(event)
        is AgentEvent.ResultAvailable -> copy(
            runId = event.result.runId,
            finalStatus = event.result.status,
            status = event.result.status.name,
            assistantPreview = event.result.response.displayReplyText().ifBlank { assistantPreview },
            errorCode = event.result.errorCode.orEmpty(),
        )
        is AgentEvent.Finished -> copy(runId = event.runId, status = "Finished")
        is AgentEvent.Failed -> copy(runId = event.runId, status = "Failed", errorCode = event.code)
    }

internal fun DebugRuntimeDemoState.visibleSignals(): List<String> =
    buildList {
        if (status.isNotBlank()) add("status")
        if (thinkingPreview.isNotBlank()) add("thinking")
        if (assistantPreview.isNotBlank()) add("assistant")
        if (pendingConfirmationIds.isNotEmpty()) add("confirmation")
        if (steeringPreview.isNotBlank()) add("steering")
        if (execOutputPreview.isNotBlank()) add("exec")
        if (workspaceId.isNotBlank()) add("workspace")
        if (retryCount > 0) add("retry")
        if (runningTools.isNotEmpty() || finishedTools.isNotEmpty()) add("tools")
        if (changedFiles.isNotEmpty()) add("diff")
        if (tokenUsage.hasUsage()) add("usage")
        if (finalStatus != null) add("result")
        if (errorCode.isNotBlank()) add("error")
    }

internal fun DebugRuntimeDemoState.pendingConfirmationActionIds(): List<String> =
    if (finalStatus == null) pendingConfirmationIds else emptyList()

internal fun DebugRuntimeDemoState.resolvePendingConfirmation(pendingId: String): DebugRuntimeDemoState =
    copy(pendingConfirmationIds = pendingConfirmationIds.filterNot { it == pendingId })

private fun DebugRuntimeDemoState.toolStarted(event: AgentEvent.NativeToolCallStarted): DebugRuntimeDemoState =
    copy(runId = event.runId, runningTools = (runningTools + event.toolName).takeLast(MAX_VISIBLE_ITEMS), status = "Tool running")

private fun DebugRuntimeDemoState.toolFinished(event: AgentEvent.NativeToolCallFinished): DebugRuntimeDemoState =
    copy(
        runId = event.runId,
        runningTools = runningTools.removeFirst(event.toolName),
        finishedTools = (finishedTools + event.toolName).takeLast(MAX_VISIBLE_ITEMS),
        status = "Tool finished",
    )

private fun DebugRuntimeDemoState.fileChanged(event: AgentEvent.WorkspaceFileChanged): DebugRuntimeDemoState =
    copy(
        runId = event.runId,
        changedFiles = (
            changedFiles + DebugRuntimeFileChange(
                path = event.path,
                addedLines = event.addedLines,
                removedLines = event.removedLines,
                diffTimedOut = event.diffTimedOut,
            )
            ).takeLast(MAX_VISIBLE_ITEMS),
        status = "Workspace changed",
    )

private fun AgentEvent.TokenUsageDelta.toDemoTokenUsage(): DebugRuntimeTokenUsage =
    DebugRuntimeTokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
    )

private fun DebugRuntimeTokenUsage.hasUsage(): Boolean =
    inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens > 0

private fun appendPreview(current: String, delta: String): String =
    (current + delta).takeLast(MAX_PREVIEW_CHARS)

private fun List<String>.removeFirst(value: String): List<String> {
    val index = indexOf(value)
    if (index < 0) return this
    return take(index) + drop(index + 1)
}

private const val MAX_VISIBLE_ITEMS = 8
private const val MAX_PREVIEW_CHARS = 240
