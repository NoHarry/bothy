@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.SessionEventLogDurability
import cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus

@JvmInline
internal value class TrajectoryRecordId(val value: String)

/** Where a record's identity came from. A record can carry more than one contributing source. */
internal enum class TrajectorySource {
    EVENT_LOG,
    SESSION_TURN,
    RUN_EVIDENCE,
    SYNTHETIC_BOUNDARY,
}

/** `SYSTEM` rows appear in the ledger and as full-height markers; visible lanes stay Input/Model/Tools. */
internal enum class TrajectoryLane {
    INPUT,
    MODEL,
    TOOLS,
    SYSTEM,
}

internal enum class TrajectoryKind {
    RUN_START,
    INPUT,
    THINKING,
    MODEL_MESSAGE,
    MODEL_CALL,
    MODEL_TOOL,
    TOOL_START,
    TOOL_FINISH,
    USAGE,
    CONFIRMATION,
    RETRY,
    COMPACTION,
    NETWORK,
    SEARCH,
    EXEC_OUTPUT,
    WORKSPACE,
    STATUS,
    RESULT,
    RUN_END,
    GAP,
    LOAD_ERROR,
}

/** How trustworthy a record's timestamp is; live arrival time is never Runtime occurrence time. */
internal enum class TrajectoryTimeQuality {
    PERSISTED_RECORDED,
    LIVE_ARRIVAL,
    SUMMARY_RANGE,
    UNKNOWN,
}

/**
 * Availability of rich detail (payload/result/schema), tracked separately from EventLog health:
 * a COMPLETE metadata log still has no raw payload, and missing rich detail is not corruption.
 */
internal enum class TrajectoryRichAvailability {
    AVAILABLE,
    PARTIAL,
    REDACTED,
    HASH_ONLY,
    UNAVAILABLE,
}

internal data class TrajectoryCorrelation(
    val toolCallId: String? = null,
    val modelCallId: String? = null,
    val lifecyclePhase: String? = null,
    val localOrdinal: Long? = null,
)

/** Display-only projection; never serialized back into the EventLog. */
internal data class TrajectoryRichDetails(
    val inputPreview: String? = null,
    val payloadPreview: String? = null,
    val resultPreview: String? = null,
    val schemaPreview: String? = null,
    val requestText: String? = null,
    val thinkingText: String? = null,
    val outputText: String? = null,
    val usageText: String? = null,
    val rawEvent: cc.noharry.bothy.kernel.api.SessionEvent? = null,
    val timing: Map<String, Long> = emptyMap(),
) {
    /** Full canonical data is rendered only when an inspector actually asks for it. */
    val rawText: String? by lazy(LazyThreadSafetyMode.PUBLICATION) { rawEvent?.renderRawText() }
}

internal data class TrajectoryRecord(
    val id: TrajectoryRecordId,
    val sessionId: String,
    val runId: String,
    val requestId: String? = null,
    val eventId: String? = null,
    val sequence: Long? = null,
    val occurredAtEpochMillis: Long? = null,
    val timeQuality: TrajectoryTimeQuality = TrajectoryTimeQuality.UNKNOWN,
    val kind: TrajectoryKind,
    val lane: TrajectoryLane,
    val status: String? = null,
    val title: String,
    val summary: String = "",
    val correlation: TrajectoryCorrelation = TrajectoryCorrelation(),
    val safeMetadata: Map<String, String> = emptyMap(),
    val contentHashes: Map<String, String> = emptyMap(),
    val sourceEventSequences: List<Long> = emptyList(),
    val sources: Set<TrajectorySource> = emptySet(),
    val rich: TrajectoryRichDetails? = null,
    val richAvailability: TrajectoryRichAvailability = TrajectoryRichAvailability.UNAVAILABLE,
    val isLive: Boolean = false,
)

internal data class TrajectoryHealth(
    val eventLogStatus: SessionEventLogHealthStatus = SessionEventLogHealthStatus.UNKNOWN,
    val durability: SessionEventLogDurability = SessionEventLogDurability.UNKNOWN,
    val attemptedCount: Int = 0,
    val appendedCount: Int = 0,
    val failedCount: Int = 0,
    val lastAcceptedSequence: Long? = null,
    val lastPersistedSequence: Long? = null,
    val hasSequenceGap: Boolean = false,
    val hasLiveOnlyRecords: Boolean = false,
    val richAvailability: TrajectoryRichAvailability = TrajectoryRichAvailability.UNAVAILABLE,
    val safeReasonCode: String? = null,
) {
    /** Bounded badge text; health must never be conveyed by colour alone. */
    val badgeLabel: String
        get() = when (eventLogStatus) {
            SessionEventLogHealthStatus.COMPLETE -> "Complete"
            SessionEventLogHealthStatus.DEGRADED -> "Degraded"
            SessionEventLogHealthStatus.UNAVAILABLE -> "Unavailable"
            SessionEventLogHealthStatus.VOLATILE -> "Volatile"
            SessionEventLogHealthStatus.UNKNOWN -> "Unknown"
        }
}

internal data class TrajectoryRunGroup(
    val sessionId: String,
    val runId: String,
    val requestId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val lifecycleStatus: String = TRAJECTORY_STATUS_UNKNOWN,
    val completionOutcome: String? = null,
    val isActive: Boolean = false,
    val recordsLoaded: Boolean = false,
    /** True when the durable read failed; the group is retryable, not merely unexpanded. */
    val loadFailed: Boolean = false,
    val records: List<TrajectoryRecord> = emptyList(),
    val health: TrajectoryHealth = TrajectoryHealth(),
) {
    val toolCallCount: Int
        get() = records.mapNotNull { it.correlation.toolCallId }.toSet().size
}

internal data class TrajectorySnapshot(
    val sessionId: String,
    val runGroups: List<TrajectoryRunGroup> = emptyList(),
    val visibleRunCount: Int = 0,
    val totalKnownRunCount: Int = 0,
    val hasOlderRuns: Boolean = false,
    val loadingRunIds: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    /**
     * live id -> durable id for rows reconciliation has replaced. Published so selection and
     * scroll anchors can follow a row whose identity changed under them.
     */
    val recordAliases: Map<TrajectoryRecordId, TrajectoryRecordId> = emptyMap(),
    val revision: Long = 0L,
) {
    val isEmpty: Boolean get() = runGroups.isEmpty()

    /** Follows an alias chain so a selection made before reconciliation still resolves. */
    fun resolveRecordAlias(id: TrajectoryRecordId): TrajectoryRecordId {
        var current = id
        var hops = 0
        while (hops < MAX_ALIAS_HOPS) {
            val next = recordAliases[current] ?: return current
            if (next == current) return current
            current = next
            hops += 1
        }
        return current
    }

    private companion object {
        /** Aliases are one hop in practice; the bound only guards against a malformed cycle. */
        const val MAX_ALIAS_HOPS = 4
    }
}

internal const val TRAJECTORY_STATUS_UNKNOWN = "UNKNOWN"

/** Bounded limits for the in-memory live projection; eviction never touches the EventLog. */
internal const val TRAJECTORY_INITIAL_VISIBLE_RUNS = 6
internal const val TRAJECTORY_OLDER_RUN_PAGE = 6
internal const val TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN = 1000
internal const val TRAJECTORY_SUMMARY_PREVIEW_CHARS = 2000
internal const val TRAJECTORY_RICH_PREVIEW_CHARS = 12000
internal const val TRAJECTORY_PUBLISH_BATCH_MS = 50L
