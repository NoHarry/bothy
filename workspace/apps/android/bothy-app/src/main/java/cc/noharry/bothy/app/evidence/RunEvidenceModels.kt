package cc.noharry.bothy.app.evidence

import cc.noharry.bothy.app.session.LessonEntry
internal data class RunEvidence(
    val requestId: String,
    val runId: String,
    val sessionId: String,
    val scene: String,
    val inputLength: Int,
    val inputPreview: String,
    val provider: String,
    val stream: String,
    val backend: String,
    val finalStatus: String?,
    val finalCompletionOutcome: String? = null,
    val finalErrorCode: String?,
    val failureEventCode: String?,
    val finalResponse: String,
    val tools: List<RunToolEvidence>,
    val requiresBusinessAction: Boolean,
    val counts: RunEvidenceCounts,
    val eventDispatch: RuntimeEventDispatchEvidence = RuntimeEventDispatchEvidence(),
    val memory: RuntimeMemoryEvidence = RuntimeMemoryEvidence(),
    /**
     * Runtime loop probes derived only from [AgentEvent]s (not UI / presentation mode).
     * Used to root-cause empty completion continue vs hard-fail on later repros.
     */
    val loopProbes: LoopProbeEvidence = LoopProbeEvidence(),
)

internal data class RuntimeEventDispatchEvidence(
    val eventCount: Int = 0,
    val maxQueueDepth: Int = 0,
    val enqueueDurationMs: Long = 0,
    val handleDurationMs: Long = 0,
    val maxHandleDurationMs: Long = 0,
)

internal data class RuntimeMemoryEvidence(
    val activeCount: Int = 0,
    val pendingCount: Int = 0,
    val active: List<LessonEntry> = emptyList(),
    val pending: List<LessonEntry> = emptyList(),
)

/**
 * Sample-side aggregation of runtime empty-completion probes.
 * Source of truth is [cc.noharry.agent.kernel.api.AgentEvent.EmptyCompletionObserved] from the tool loop.
 */
internal data class LoopProbeEvidence(
    val emptyCompletionObservedCount: Int = 0,
    val emptyCompletionContinueCount: Int = 0,
    val lastEmptyCompletionKind: String? = null,
    val lastEmptyCompletionContinued: Boolean? = null,
    val lastEmptyCompletionModelRound: Int? = null,
    val lastEmptyCompletionTextChars: Int? = null,
    val lastEmptyCompletionThinkingChars: Int? = null,
    val lastHadStateAdvancingWork: Boolean? = null,
    val lastSuccessfulBackendToolCount: Int? = null,
    val observations: List<EmptyCompletionObservation> = emptyList(),
)

internal data class EmptyCompletionObservation(
    val modelRound: Int,
    val kind: String,
    val continued: Boolean,
    val continueCount: Int,
    val textChars: Int,
    val thinkingChars: Int,
    val hadStateAdvancingWork: Boolean,
    val successfulBackendToolCount: Int,
)

internal data class RunToolEvidence(
    val sequence: Int,
    val toolCallId: String,
    val toolName: String,
    val category: String,
    val publicStarted: Boolean,
    val publicFinished: Boolean,
    val rawPre: Boolean,
    val rawPost: Boolean,
    val rawPostStatus: String,
    val publicStartedAtEpochMillis: Long,
    val publicFinishedAtEpochMillis: Long,
    val rawPreAtEpochMillis: Long,
    val rawPostAtEpochMillis: Long,
    val publicDurationMs: Long,
    val rawDurationMs: Long,
    val recorderGapMs: Long,
)

internal data class RunEvidenceCounts(
    val publicStarted: Int,
    val publicFinished: Int,
    val rawPre: Int,
    val rawPost: Int,
    val pendingPublicTools: Int,
    val rawHookGaps: Int,
    val failedRawTools: Int,
    val orphanPublicFinishes: Int,
    val meaningfulBusinessTools: Int,
    val readDurationMs: Long,
    val searchDurationMs: Long,
    val backendDurationMs: Long,
    val recorderGapMs: Long,
) {
    companion object {
        fun from(tools: List<RunToolEvidence>): RunEvidenceCounts =
            RunEvidenceCounts(
                publicStarted = tools.count { it.publicStarted },
                publicFinished = tools.count { it.publicFinished },
                rawPre = tools.count { it.rawPre },
                rawPost = tools.count { it.rawPost },
                pendingPublicTools = tools.count { it.publicStarted && !it.publicFinished },
                rawHookGaps = tools.count { it.rawPre && !it.rawPost },
                failedRawTools = tools.count { it.rawPostStatus == RAW_STATUS_FAILED },
                orphanPublicFinishes = tools.count { it.publicFinished && !it.publicStarted },
                meaningfulBusinessTools = tools.count { it.isMeaningfulBusinessTool() },
                readDurationMs = tools.sumDuration(DEBUG_TOOL_CATEGORY_READ),
                searchDurationMs = tools.sumDuration(DEBUG_TOOL_CATEGORY_SEARCH),
                backendDurationMs = tools.sumDuration(DEBUG_TOOL_CATEGORY_BACKEND),
                recorderGapMs = tools.sumOf { it.recorderGapMs.coerceAtLeast(0L) },
            )
    }
}

internal data class MutableRunToolEvidence(
    val sequence: Int,
    var toolCallId: String,
    val toolName: String,
    val category: String = toolName.toolCategory(),
    var publicStarted: Boolean = false,
    var publicFinished: Boolean = false,
    var rawPre: Boolean = false,
    var rawPost: Boolean = false,
    var rawPostStatus: String = RAW_STATUS_MISSING,
    var publicStartedAtEpochMillis: Long = 0,
    var publicFinishedAtEpochMillis: Long = 0,
    var rawPreAtEpochMillis: Long = 0,
    var rawPostAtEpochMillis: Long = 0,
) {
    fun toEvidence(): RunToolEvidence =
        RunToolEvidence(
            sequence = sequence,
            toolCallId = toolCallId,
            toolName = toolName,
            category = category,
            publicStarted = publicStarted,
            publicFinished = publicFinished,
            rawPre = rawPre,
            rawPost = rawPost,
            rawPostStatus = rawPostStatus,
            publicStartedAtEpochMillis = publicStartedAtEpochMillis,
            publicFinishedAtEpochMillis = publicFinishedAtEpochMillis,
            rawPreAtEpochMillis = rawPreAtEpochMillis,
            rawPostAtEpochMillis = rawPostAtEpochMillis,
            publicDurationMs = durationOrZero(publicStartedAtEpochMillis, publicFinishedAtEpochMillis),
            rawDurationMs = durationOrZero(rawPreAtEpochMillis, rawPostAtEpochMillis),
            recorderGapMs = durationOrZero(publicFinishedAtEpochMillis, rawPostAtEpochMillis),
        )
}

internal fun rawResultStatus(body: String): String =
    when (body.lineValue("ok")?.lowercase()) {
        "true" -> RAW_STATUS_OK
        "false" -> RAW_STATUS_FAILED
        else -> RAW_STATUS_UNKNOWN
    }

internal fun String.lineValue(name: String): String? =
    lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim()

internal fun String.timestampMillisOrNow(nowEpochMillis: () -> Long = { System.currentTimeMillis() }): Long =
    lineValue("timestampMs")?.toLongOrNull() ?: nowEpochMillis()

internal fun String.inputPreviewText(): String =
    replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(DEBUG_INPUT_PREVIEW_CHARS)

private fun durationOrZero(start: Long, end: Long): Long =
    if (start > 0 && end >= start) end - start else 0

private fun List<RunToolEvidence>.sumDuration(category: String): Long =
    filter { it.category == category }.sumOf { it.publicDurationMs }

private fun String.toolCategory(): String =
    when {
        this == "read_file" || this == "list_dir" -> DEBUG_TOOL_CATEGORY_READ
        this == "grep" || equals("web_search", ignoreCase = true) -> DEBUG_TOOL_CATEGORY_SEARCH
        startsWith("mcp__") || startsWith("command_") -> DEBUG_TOOL_CATEGORY_BACKEND
        this == "write_file" || this == "edit_file" -> DEBUG_TOOL_CATEGORY_WRITE
        this == "update_todo" -> DEBUG_TOOL_CATEGORY_TODO
        this == "finish" -> DEBUG_TOOL_CATEGORY_TERMINAL
        else -> DEBUG_TOOL_CATEGORY_INTERNAL
    }

/** Bumped when [LoopProbeEvidence] was added for empty-completion root-cause probes. */
internal const val DEBUG_RUN_EVIDENCE_SCHEMA_VERSION = 2
internal const val DEBUG_INPUT_PREVIEW_CHARS = 120
internal const val RAW_STATUS_MISSING = "MISSING"
internal const val RAW_STATUS_OK = "OK"
internal const val RAW_STATUS_FAILED = "FAILED"
internal const val RAW_STATUS_CANCELLED = "CANCELLED"
internal const val RAW_STATUS_UNKNOWN = "UNKNOWN"
internal const val DEBUG_TOOL_CATEGORY_READ = "read"
internal const val DEBUG_TOOL_CATEGORY_SEARCH = "search"
internal const val DEBUG_TOOL_CATEGORY_BACKEND = "backend"
internal const val DEBUG_TOOL_CATEGORY_WRITE = "write"
internal const val DEBUG_TOOL_CATEGORY_TODO = "todo"
internal const val DEBUG_TOOL_CATEGORY_TERMINAL = "terminal"
internal const val DEBUG_TOOL_CATEGORY_INTERNAL = "internal"
