package cc.noharry.bothy.app.session

import cc.noharry.bothy.app.a2a.A2aModuleState
import cc.noharry.bothy.app.console.ArtifactPreview
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.mcp.McpProviderState
import cc.noharry.bothy.app.model.ProviderEditState
import cc.noharry.bothy.app.runtime.DEBUG_MAX_ACTIVE_SESSION_RUNS
import cc.noharry.bothy.app.runtime.RuntimeDemoState
import cc.noharry.bothy.app.runtime.WorkspaceSelectionState
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.trajectory.TrajectorySnapshot
import cc.noharry.bothy.app.usage.TokenUsageState
internal const val DEBUG_BROADCAST_ACTION = "cc.noharry.bothy.DEBUG_SEND_TEXT"
internal const val DEBUG_TEXT_EXTRA = "text"
internal const val DEBUG_ALREADY_SUBMITTED_EXTRA = "already_submitted"
internal const val DEBUG_BACKEND_EXTRA = "backend"
internal const val DEBUG_STREAM_EXTRA = "stream"
internal const val DEBUG_ROUTE_EXTRA = "route"
internal const val DEBUG_A2A_CLIENT_EXTRA = "a2a_client"
internal const val DEBUG_BROADCAST_PERMISSION = "android.permission.DUMP"

internal const val BACKEND_LIVE = "live"
internal const val BACKEND_SIMULATED = "simulated"
internal const val LEGACY_BACKEND_LIVE_ALIAS = "catalog"
internal const val LEGACY_BACKEND_SIMULATED_ALIAS = "fixture"

internal enum class ModelProvider(val wireName: String, val label: String) {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic");

    companion object {
        fun from(value: String?): ModelProvider =
            entries.firstOrNull { it.wireName == value?.lowercase() } ?: OPENAI
    }
}

/**
 * Semantic role for a transcript message. Compose rendering maps roles to theme
 * colors (see DebugConversationTimeline). USER input is rendered as a
 * [ConversationItem.TurnHeader] rather than a message bubble.
 */
internal enum class TranscriptRole {
    ASSISTANT,
    THINKING,
    RUNTIME,
    STATUS,
    ERROR,

    /** An app-level event worth a visible line, e.g. 连接应用 connected or disconnected; unlike STATUS it is shown. */
    NOTICE,
}

/** Lifecycle status of a tool call card. */
internal enum class ToolCallStatus {
    RUNNING,
    DONE,
    FAILED,
    UNKNOWN,
}

internal enum class ConfirmationMode(
    val label: String,
    val shortLabel: String,
    val warning: Boolean = false,
) {
    ASK_EVERY_TIME("Ask every time", "Ask"),
    ALLOW_CONFIRMED_DOMAINS("Allow confirmed domains", "Domains"),
    ALLOW_ALL_SESSION("Allow all this session", "Allow all", warning = true),
    DENY_ALL("Deny all", "Deny all"),
}

internal enum class ConfirmationStatus {
    PENDING,
    APPROVED,
    DENIED,
    AUTO_APPROVED,
    AUTO_DENIED,
    CONSUMED,
    EXPIRED,
    FAILED,
}

/**
 * Structured conversation item rendered as one row in the transcript RecyclerView.
 *
 * Replaces the previous flat log stream with a Codex/Claude Code style
 * turn-grouped, collapsible layout:
     * - [TurnHeader] opens a user or assistant turn.
     * - [MessageItem] renders thinking (collapsible), assistant (markdown), status, or error.
     * - [ToolCallItem] pairs start/finish into a single status card with elapsed time.
     * - [ConfirmationItem] renders a pending approval inline with the streaming transcript.
     * - [RuntimePhaseItem] renders real runtime phases (intake / result / evidence).
     */
internal sealed interface ConversationItem {
    val id: Long

    /** Opens a new turn; [preview] is the truncated input or assistant label. */
    data class TurnHeader(
        override val id: Long,
        val label: String,
        val preview: String,
        val fullText: String,
        var collapsed: Boolean = false,
    ) : ConversationItem

    /**
     * A message bubble. Thinking collapses to a one-line summary by default;
     * assistant renders markdown and never collapses; status/error are compact.
     */
    data class MessageItem(
        override val id: Long,
        val role: TranscriptRole,
        val content: String,
        var collapsed: Boolean = role == TranscriptRole.THINKING,
        /** The run's answer ([TranscriptStore.appendFinalResponse]), not text streamed while it worked. */
        val final: Boolean = false,
    ) : ConversationItem

    /** A single tool call that pairs start + finish with a status row. */
    data class ToolCallItem(
        override val id: Long,
        val toolCallId: String = "",
        val toolName: String,
        val argumentsJson: String?,
        var resultJson: String?,
        var status: ToolCallStatus,
        val startedAt: Long,
        var finishedAt: Long? = null,
        var collapsed: Boolean = true,
    ) : ConversationItem

    data class ConfirmationItem(
        override val id: Long,
        val pendingId: String,
        val domain: String = "",
        val mode: ConfirmationMode,
        var status: ConfirmationStatus = ConfirmationStatus.PENDING,
        val createdAt: Long,
        var resolvedAt: Long? = null,
    ) : ConversationItem

    /** A real runtime phase entry (intake, result, evidence). */
    data class RuntimePhaseItem(
        override val id: Long,
        val title: String,
        val body: String,
        var collapsed: Boolean = true,
        val markdown: Boolean = false,
    ) : ConversationItem
}

/**
 * Flat single-line projection used by the overlay window, which only shows a compact
 * text strip and does not need the full structured layout.
 */
internal data class CompactEntry(
    val title: String,
    val body: String,
    val isError: Boolean,
)

internal fun ConversationItem.toCompact(): CompactEntry =
    when (this) {
        is ConversationItem.TurnHeader -> CompactEntry(label, preview, isError = false)
        is ConversationItem.MessageItem -> CompactEntry(
            title = role.compactTitle(),
            body = content,
            isError = role == TranscriptRole.ERROR,
        )
        is ConversationItem.ToolCallItem -> CompactEntry(
            title = "Tool ${status.compactMarker()} $toolName",
            body = resultJson ?: argumentsJson ?: "",
            isError = status == ToolCallStatus.FAILED,
        )
        is ConversationItem.ConfirmationItem -> CompactEntry(
            title = "Confirmation ${status.compactLabel()}",
            body = domain.ifBlank { pendingId },
            isError = status == ConfirmationStatus.DENIED ||
                status == ConfirmationStatus.AUTO_DENIED ||
                status == ConfirmationStatus.FAILED,
        )
        is ConversationItem.RuntimePhaseItem -> CompactEntry(title, body, isError = false)
    }

internal fun TranscriptRole.compactTitle(): String =
    when (this) {
        TranscriptRole.ASSISTANT -> "Assistant"
        TranscriptRole.THINKING -> "Thinking"
        TranscriptRole.RUNTIME -> "Runtime"
        TranscriptRole.STATUS -> "Status"
        TranscriptRole.ERROR -> "Error"
        TranscriptRole.NOTICE -> "Notice"
    }

internal fun ToolCallStatus.compactMarker(): String =
    when (this) {
        ToolCallStatus.RUNNING -> "⏳"
        ToolCallStatus.DONE -> "✓"
        ToolCallStatus.FAILED -> "✗"
        ToolCallStatus.UNKNOWN -> "?"
    }

internal fun ConfirmationStatus.compactLabel(): String =
    when (this) {
        ConfirmationStatus.PENDING -> "pending"
        ConfirmationStatus.APPROVED -> "approved"
        ConfirmationStatus.DENIED -> "denied"
        ConfirmationStatus.AUTO_APPROVED -> "auto-approved"
        ConfirmationStatus.AUTO_DENIED -> "auto-denied"
        ConfirmationStatus.CONSUMED -> "consumed"
        ConfirmationStatus.EXPIRED -> "expired"
        ConfirmationStatus.FAILED -> "failed"
    }

internal data class UiState(
    val status: String,
    val providerLabel: String,
    val streamLabel: String,
    val backendLabel: String,
    val confirmationMode: ConfirmationMode,
    val isRunning: Boolean,
    val configLabel: String,
    val configEditor: ProviderEditState,
    val sessions: List<SessionSummary>,
    val activeSessionId: String,
    val inputHint: String,
    val items: List<ConversationItem>,
    val contextPanel: ContextPanelState,
    val board: SessionBoardState,
    val tokenUsage: TokenUsageState,
    val runtimeDemo: RuntimeDemoState,
    val mcpProviders: List<McpProviderState>,
    val a2aClients: A2aModuleState = A2aModuleState(),
    val workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState(),
    val currentRequest: CurrentRequest?,
    val artifactPreview: ArtifactPreview?,
    /** Read-only run trajectory for the selected session; empty until the first refresh lands. */
    val trajectory: TrajectorySnapshot = TrajectorySnapshot(sessionId = ""),
    /** Wall-clock start of the active run; null when idle. */
    val runStartedAtEpochMillis: Long? = null,
    /** Codex-style summary after a round ends, e.g. "Worked for 12s". */
    val lastRunSummary: String? = null,
    /** Count of Sample sessions that currently own an active run. Selected-session UI only. */
    val activeSessionCount: Int = 0,
    val maxActiveSessions: Int = DEBUG_MAX_ACTIVE_SESSION_RUNS,
)

internal data class CurrentRequest(
    val requestId: String,
    val text: String,
    val providerLabel: String,
    val streamLabel: String,
    val backendLabel: String,
    val status: String,
)

/** Collapsed state for the optional CDR context drawer; empty when closed. */
internal data class ContextPanelState(
    val visible: Boolean,
    val todo: List<TodoEntry>,
    val lessons: List<LessonEntry>,
    val skills: List<SkillEntry>,
)

internal data class TodoEntry(
    val content: String,
    val status: String,
)

internal data class LessonEntry(
    val scene: String,
    val content: String,
    val confidence: Float,
    val hitCount: Int,
)

internal data class SkillEntry(
    val name: String,
    val description: String,
)

internal fun String.trimForDisplay(): String =
    if (length <= MAX_TRANSCRIPT_CHARS) {
        this
    } else {
        take(MAX_TRANSCRIPT_CHARS) + "\n[truncated in debug UI]"
    }

internal fun String.normalizeTextEscapes(): String =
    replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

internal fun parseToolResultStatus(resultBody: String): ToolCallStatus {
    val ok = resultBody.lineSequence()
        .firstOrNull { it.startsWith("ok=") }
        ?.removePrefix("ok=")
        ?.trim()
        ?.lowercase()
    return when (ok) {
        "true" -> ToolCallStatus.DONE
        "false" -> ToolCallStatus.FAILED
        else -> ToolCallStatus.UNKNOWN
    }
}

internal fun parseRawToolCallId(body: String): String =
    body.lineSequence()
        .firstOrNull { it.startsWith("toolCallId=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()

internal data class ToolIssueSummary(
    val failed: Int,
    val unknown: Int,
) {
    val hasIssues: Boolean = failed > 0 || unknown > 0
}

internal fun decorateRuntimeResultBody(
    body: String,
    issues: ToolIssueSummary,
): String {
    val normalizedBody = body.normalizeTextEscapes()
    if (!issues.hasIssues) return normalizedBody
    return buildString {
        append("⚠️ Tool execution did not fully pass.\n")
        if (issues.failed > 0) append("- Failed tool calls: ${issues.failed}\n")
        if (issues.unknown > 0) append("- Tool calls without captured raw result: ${issues.unknown}\n")
        append("\nModel finish response:\n\n")
        append(normalizedBody)
    }
}

private const val MAX_TRANSCRIPT_CHARS = 12000
