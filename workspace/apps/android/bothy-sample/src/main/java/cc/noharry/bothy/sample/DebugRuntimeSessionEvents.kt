package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.PendingConfirmation

/**
 * Maps a runtime [AgentEvent] into debug console transcript mutations.
 *
 * Tool pairing is handled by [DebugRawToolTranscriptHook] (pre/post ToolUse) which sees
 * real arguments/result in the debug build; [AgentEvent.NativeToolCallStarted]/[Finished]
 * are only used here for status-line updates since the hook path owns the visible cards.
 *
 * Quiet timeline policy (Codex / Claude Code style):
 * - Thinking / assistant / tools / confirmations / errors stay visible.
 * - Routine lifecycle noise (started, plan steps, result body, search/network chatter,
 *   workspace diffs, protocol bookkeeping) only updates the status strip — not the stream.
 * - Blocking / failure / budget / retry / steering still append a compact runtime row.
 *
 * Returns the new status string, or null to leave the status unchanged.
 */
internal fun handleDebugAgentEvent(
    event: AgentEvent,
    appendThinking: (String) -> Unit,
    appendAssistant: (String) -> Unit,
    appendRuntime: (String, String) -> Unit,
    appendError: (String, String) -> Unit,
    appendConfirmation: (PendingConfirmation) -> Unit,
): String? =
    when (event) {
        is AgentEvent.Started -> "Started ${event.runId}"
        is AgentEvent.ThinkingDelta -> null.also { appendThinking(event.text) }
        is AgentEvent.TextDelta -> null.also { appendAssistant(event.text) }
        is AgentEvent.TokenUsageDelta -> null
        is AgentEvent.NativeToolCallStarted -> "Tool running: ${event.toolName}"
        is AgentEvent.NativeToolCallFinished -> "Tool finished: ${event.toolName}"
        // Final body is rendered as Assistant (appendFinalResponse in run scope).
        is AgentEvent.ResultAvailable -> "Result ${event.result.status}"
        is AgentEvent.Finished -> "Finished ${event.runId}"
        is AgentEvent.Failed -> "Failed ${event.code}".also {
            appendError("Failed", "${event.code}: ${event.message}")
            // In logcat too, so a log capture carries why a run failed.
            android.util.Log.w("DebugRuntime", "run ${event.runId} failed: ${event.code}: ${event.message}")
        }
        is AgentEvent.ConfirmationRequired -> null.also {
            appendConfirmation(event.pending)
        }
        is AgentEvent.RuntimeBlocked -> null.also {
            appendRuntime("Blocked · ${event.blocker.code}", event.blocker.message)
        }
        is AgentEvent.RuntimeProtocolRequestCreated ->
            "Protocol ${event.protocolRequest.kind}"
        is AgentEvent.RetryAttempted -> "Retry ${event.attempt}".also {
            appendRuntime(
                "Retry · attempt ${event.attempt}",
                "delayMillis=${event.delayMillis}",
            )
        }
        is AgentEvent.BudgetWarning -> "Budget warning".also {
            appendRuntime(
                "Budget warning",
                "roundsRemaining=${event.roundsRemaining} toolCallsRemaining=${event.toolCallsRemaining}",
            )
        }
        is AgentEvent.CompactionApplied -> "Compacted".also {
            appendRuntime(
                "Context compacted",
                "before=${event.beforeMessageCount} after=${event.afterMessageCount} dropped=${event.droppedCount}",
            )
        }
        is AgentEvent.EmptyCompletionObserved -> "Empty completion".also {
            appendRuntime(
                "Empty completion · ${event.kind}",
                "continued=${event.continued} continueCount=${event.continueCount} " +
                    "round=${event.modelRound} textChars=${event.textChars} thinkingChars=${event.thinkingChars} " +
                    "stateAdvancing=${event.hadStateAdvancingWork} backendTools=${event.successfulBackendToolCount}",
            )
        }
        is AgentEvent.SteeringAccepted -> "Steering accepted".also {
            appendRuntime("Steering", event.textPreview)
        }
        is AgentEvent.UserInteraction -> null
        is AgentEvent.ExecOutputDelta -> null.also {
            appendRuntime("Exec output", event.text)
        }
        is AgentEvent.NetworkToolActivity -> "Network ${event.decision}"
        is AgentEvent.SearchActivity -> "Search activity"
        is AgentEvent.WorkspaceChanged -> null
        is AgentEvent.WorkspaceFileChanged -> null
    }

private fun AgentEvent.WorkspaceFileChanged.workspaceChangeSummary(): String {
    val timeout = if (diffTimedOut) " diffTimedOut=true" else ""
    return "path=$path bytes=$bytes added=$addedLines removed=$removedLines$timeout"
}

private fun AgentEvent.NetworkToolActivity.networkActivitySummary(): String {
    val status = statusCode?.let { " status=$it" }.orEmpty()
    val truncatedText = if (truncated) " truncated=true" else ""
    return "tool=$toolName decision=$decision domain=$domain$status$truncatedText"
}

private fun AgentEvent.SearchActivity.searchActivitySummary(): String {
    val query = queryPreview.takeIf { it.isNotBlank() }?.let { " query=$it" }.orEmpty()
    val count = resultCount?.let { " resultCount=$it" }.orEmpty()
    return "provider=$provider$query$count"
}
