package cc.noharry.bothy.sample

internal const val DEBUG_BROADCAST_ACTION = "cc.noharry.bothy.sample.DEBUG_SEND_TEXT"
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

internal enum class DebugProvider(val wireName: String, val label: String) {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic");

    companion object {
        fun from(value: String?): DebugProvider =
            entries.firstOrNull { it.wireName == value?.lowercase() } ?: OPENAI
    }
}

/**
 * Semantic role for a transcript message. Compose rendering maps roles to theme
 * colors (see DebugConversationTimeline). USER input is rendered as a
 * [DebugConversationItem.TurnHeader] rather than a message bubble.
 */
internal enum class DebugTranscriptRole {
    ASSISTANT,
    THINKING,
    RUNTIME,
    STATUS,
    ERROR,

    /** An app-level event worth a visible line, e.g. 连接应用 connected or disconnected; unlike STATUS it is shown. */
    NOTICE,
}

/** Lifecycle status of a tool call card. */
internal enum class DebugToolCallStatus {
    RUNNING,
    DONE,
    FAILED,
    UNKNOWN,
}

internal enum class DebugConfirmationMode(
    val label: String,
    val shortLabel: String,
    val warning: Boolean = false,
) {
    ASK_EVERY_TIME("Ask every time", "Ask"),
    ALLOW_CONFIRMED_DOMAINS("Allow confirmed domains", "Domains"),
    ALLOW_ALL_SESSION("Allow all this session", "Allow all", warning = true),
    DENY_ALL("Deny all", "Deny all"),
}

internal enum class DebugConfirmationStatus {
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
internal sealed interface DebugConversationItem {
    val id: Long

    /** Opens a new turn; [preview] is the truncated input or assistant label. */
    data class TurnHeader(
        override val id: Long,
        val label: String,
        val preview: String,
        val fullText: String,
        var collapsed: Boolean = false,
    ) : DebugConversationItem

    /**
     * A message bubble. Thinking collapses to a one-line summary by default;
     * assistant renders markdown and never collapses; status/error are compact.
     */
    data class MessageItem(
        override val id: Long,
        val role: DebugTranscriptRole,
        val content: String,
        var collapsed: Boolean = role == DebugTranscriptRole.THINKING,
        /** The run's answer ([DebugTranscriptStore.appendFinalResponse]), not text streamed while it worked. */
        val final: Boolean = false,
    ) : DebugConversationItem

    /** A single tool call that pairs start + finish with a status row. */
    data class ToolCallItem(
        override val id: Long,
        val toolCallId: String = "",
        val toolName: String,
        val argumentsJson: String?,
        var resultJson: String?,
        var status: DebugToolCallStatus,
        val startedAt: Long,
        var finishedAt: Long? = null,
        var collapsed: Boolean = true,
    ) : DebugConversationItem

    data class ConfirmationItem(
        override val id: Long,
        val pendingId: String,
        val domain: String = "",
        val mode: DebugConfirmationMode,
        var status: DebugConfirmationStatus = DebugConfirmationStatus.PENDING,
        val createdAt: Long,
        var resolvedAt: Long? = null,
    ) : DebugConversationItem

    /** A real runtime phase entry (intake, result, evidence). */
    data class RuntimePhaseItem(
        override val id: Long,
        val title: String,
        val body: String,
        var collapsed: Boolean = true,
        val markdown: Boolean = false,
    ) : DebugConversationItem
}

/**
 * Flat single-line projection used by the overlay window, which only shows a compact
 * text strip and does not need the full structured layout.
 */
internal data class DebugCompactEntry(
    val title: String,
    val body: String,
    val isError: Boolean,
)

internal fun DebugConversationItem.toCompact(): DebugCompactEntry =
    when (this) {
        is DebugConversationItem.TurnHeader -> DebugCompactEntry(label, preview, isError = false)
        is DebugConversationItem.MessageItem -> DebugCompactEntry(
            title = role.compactTitle(),
            body = content,
            isError = role == DebugTranscriptRole.ERROR,
        )
        is DebugConversationItem.ToolCallItem -> DebugCompactEntry(
            title = "Tool ${status.compactMarker()} $toolName",
            body = resultJson ?: argumentsJson ?: "",
            isError = status == DebugToolCallStatus.FAILED,
        )
        is DebugConversationItem.ConfirmationItem -> DebugCompactEntry(
            title = "Confirmation ${status.compactLabel()}",
            body = domain.ifBlank { pendingId },
            isError = status == DebugConfirmationStatus.DENIED ||
                status == DebugConfirmationStatus.AUTO_DENIED ||
                status == DebugConfirmationStatus.FAILED,
        )
        is DebugConversationItem.RuntimePhaseItem -> DebugCompactEntry(title, body, isError = false)
    }

internal fun DebugTranscriptRole.compactTitle(): String =
    when (this) {
        DebugTranscriptRole.ASSISTANT -> "Assistant"
        DebugTranscriptRole.THINKING -> "Thinking"
        DebugTranscriptRole.RUNTIME -> "Runtime"
        DebugTranscriptRole.STATUS -> "Status"
        DebugTranscriptRole.ERROR -> "Error"
        DebugTranscriptRole.NOTICE -> "Notice"
    }

internal fun DebugToolCallStatus.compactMarker(): String =
    when (this) {
        DebugToolCallStatus.RUNNING -> "⏳"
        DebugToolCallStatus.DONE -> "✓"
        DebugToolCallStatus.FAILED -> "✗"
        DebugToolCallStatus.UNKNOWN -> "?"
    }

internal fun DebugConfirmationStatus.compactLabel(): String =
    when (this) {
        DebugConfirmationStatus.PENDING -> "pending"
        DebugConfirmationStatus.APPROVED -> "approved"
        DebugConfirmationStatus.DENIED -> "denied"
        DebugConfirmationStatus.AUTO_APPROVED -> "auto-approved"
        DebugConfirmationStatus.AUTO_DENIED -> "auto-denied"
        DebugConfirmationStatus.CONSUMED -> "consumed"
        DebugConfirmationStatus.EXPIRED -> "expired"
        DebugConfirmationStatus.FAILED -> "failed"
    }

internal data class DebugUiState(
    val status: String,
    val providerLabel: String,
    val streamLabel: String,
    val backendLabel: String,
    val confirmationMode: DebugConfirmationMode,
    val isRunning: Boolean,
    val configLabel: String,
    val configEditor: DebugProviderEditState,
    val sessions: List<DebugSessionSummary>,
    val activeSessionId: String,
    val inputHint: String,
    val items: List<DebugConversationItem>,
    val contextPanel: DebugContextPanelState,
    val board: DebugSessionBoardState,
    val tokenUsage: DebugTokenUsageState,
    val runtimeDemo: DebugRuntimeDemoState,
    val mcpProviders: List<DebugMcpProviderState>,
    val a2aClients: DebugA2aModuleState = DebugA2aModuleState(),
    val workspaceSelection: DebugWorkspaceSelectionState = DebugWorkspaceSelectionState(),
    val currentRequest: DebugCurrentRequest?,
    val artifactPreview: DebugArtifactPreview?,
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

internal data class DebugCurrentRequest(
    val requestId: String,
    val text: String,
    val providerLabel: String,
    val streamLabel: String,
    val backendLabel: String,
    val status: String,
)

/** Collapsed state for the optional CDR context drawer; empty when closed. */
internal data class DebugContextPanelState(
    val visible: Boolean,
    val todo: List<DebugTodoEntry>,
    val lessons: List<DebugLessonEntry>,
    val skills: List<DebugSkillEntry>,
)

internal data class DebugTodoEntry(
    val content: String,
    val status: String,
)

internal data class DebugLessonEntry(
    val scene: String,
    val content: String,
    val confidence: Float,
    val hitCount: Int,
)

internal data class DebugSkillEntry(
    val name: String,
    val description: String,
)

internal fun String.trimForDisplay(): String =
    if (length <= MAX_TRANSCRIPT_CHARS) {
        this
    } else {
        take(MAX_TRANSCRIPT_CHARS) + "\n[truncated in debug UI]"
    }

internal fun String.normalizeDebugTextEscapes(): String =
    replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

internal fun parseDebugToolResultStatus(resultBody: String): DebugToolCallStatus {
    val ok = resultBody.lineSequence()
        .firstOrNull { it.startsWith("ok=") }
        ?.removePrefix("ok=")
        ?.trim()
        ?.lowercase()
    return when (ok) {
        "true" -> DebugToolCallStatus.DONE
        "false" -> DebugToolCallStatus.FAILED
        else -> DebugToolCallStatus.UNKNOWN
    }
}

internal fun parseRawToolCallId(body: String): String =
    body.lineSequence()
        .firstOrNull { it.startsWith("toolCallId=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()

internal data class DebugToolIssueSummary(
    val failed: Int,
    val unknown: Int,
) {
    val hasIssues: Boolean = failed > 0 || unknown > 0
}

internal fun decorateRuntimeResultBody(
    body: String,
    issues: DebugToolIssueSummary,
): String {
    val normalizedBody = body.normalizeDebugTextEscapes()
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
