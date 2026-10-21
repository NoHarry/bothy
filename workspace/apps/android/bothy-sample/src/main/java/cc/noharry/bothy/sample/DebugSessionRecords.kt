@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

internal data class DebugSessionSummary(
    val sessionId: String,
    val title: String,
    val updatedAtEpochMillis: Long,
    val turnCount: Int,
    val compactedSummary: String,
    val lifecycleStatus: String = DEBUG_SESSION_LIFECYCLE_IDLE,
    val completionOutcome: String = cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN.name,
    val logHealth: String = cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus.UNKNOWN.name,
)

internal data class DebugSessionTurn(
    val turnId: String,
    val requestId: String,
    val runId: String,
    val sessionId: String,
    val scene: String,
    val userText: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val finalStatus: String,
    val finalResponse: String,
    val tools: List<DebugSessionToolCall>,
    val completionOutcome: String = cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN.name,
    val replayStatus: String = DEBUG_REPLAY_STATUS_NOT_RUN,
    val replaySummary: String = "",
    /** The run's model usage, so the Board can show a whole session's, also after a restart. */
    val usage: DebugTokenUsageState = DebugTokenUsageState(),
)

internal data class DebugSessionToolCall(
    val sequence: Int,
    val toolName: String,
    val argumentsPreview: String,
    val resultPreview: String,
    val status: String,
    val toolCallId: String = "",
)

internal data class DebugSessionContext(
    val summary: DebugSessionSummary,
    val recentTurns: List<DebugSessionTurn>,
) {
    val lastAction: DebugSessionToolCall? =
        recentTurns.asReversed().flatMap { it.tools.asReversed() }.firstOrNull { it.isMeaningfulAction() }
}

internal data class DebugSessionBoardState(
    val activeSession: DebugSessionSummary?,
    val recentTurns: List<DebugSessionTurn>,
    val lastAction: DebugSessionToolCall?,
)

internal fun DebugSessionTurn.meaningfulAction(): DebugSessionToolCall? =
    tools.asReversed().firstOrNull { it.isMeaningfulAction() }

internal fun DebugSessionToolCall.isMeaningfulAction(): Boolean =
    toolName.isNotBlank() && !toolName.isInternalDebugToolName()

internal const val DEBUG_REPLAY_STATUS_NOT_RUN = "NOT_RUN"
internal const val DEBUG_REPLAY_STATUS_PASSED = "PASSED"
internal const val DEBUG_REPLAY_STATUS_FAILED = "FAILED"
