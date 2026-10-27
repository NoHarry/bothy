@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.session

import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.evidence.isInternalToolName
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.DEBUG_SESSION_LIFECYCLE_IDLE
import cc.noharry.bothy.app.usage.TokenUsageState
internal data class SessionSummary(
    val sessionId: String,
    val title: String,
    val updatedAtEpochMillis: Long,
    val turnCount: Int,
    val compactedSummary: String,
    val lifecycleStatus: String = DEBUG_SESSION_LIFECYCLE_IDLE,
    val completionOutcome: String = cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN.name,
    val logHealth: String = cc.noharry.agent.kernel.api.SessionEventLogHealthStatus.UNKNOWN.name,
)

internal data class SessionTurn(
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
    val tools: List<SessionToolCall>,
    val completionOutcome: String = cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN.name,
    val replayStatus: String = DEBUG_REPLAY_STATUS_NOT_RUN,
    val replaySummary: String = "",
    /** The run's model usage, so the Board can show a whole session's, also after a restart. */
    val usage: TokenUsageState = TokenUsageState(),
)

internal data class SessionToolCall(
    val sequence: Int,
    val toolName: String,
    val argumentsPreview: String,
    val resultPreview: String,
    val status: String,
    val toolCallId: String = "",
)

internal data class SessionContext(
    val summary: SessionSummary,
    val recentTurns: List<SessionTurn>,
) {
    val lastAction: SessionToolCall? =
        recentTurns.asReversed().flatMap { it.tools.asReversed() }.firstOrNull { it.isMeaningfulAction() }
}

internal data class SessionBoardState(
    val activeSession: SessionSummary?,
    val recentTurns: List<SessionTurn>,
    val lastAction: SessionToolCall?,
)

internal fun SessionTurn.meaningfulAction(): SessionToolCall? =
    tools.asReversed().firstOrNull { it.isMeaningfulAction() }

internal fun SessionToolCall.isMeaningfulAction(): Boolean =
    toolName.isNotBlank() && !toolName.isInternalToolName()

internal const val DEBUG_REPLAY_STATUS_NOT_RUN = "NOT_RUN"
internal const val DEBUG_REPLAY_STATUS_PASSED = "PASSED"
internal const val DEBUG_REPLAY_STATUS_FAILED = "FAILED"
