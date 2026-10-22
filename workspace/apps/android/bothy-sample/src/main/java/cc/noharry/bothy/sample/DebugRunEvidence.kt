package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.io.File

internal class DebugRunEvidenceRecorder(
    private val evidenceDir: File,
    request: AgentRequest,
    private val providerLabel: String,
    private val streamLabel: String,
    private val backendLabel: String,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    /** What the user typed; a host frame around a short reply must not make every reply look actionable. */
    userText: String = request.input,
) {
    private val tools = mutableListOf<MutableDebugRunToolEvidence>()
    private val toolsByCallId = mutableMapOf<String, Int>()
    private val publicPending = mutableMapOf<String, ArrayDeque<Int>>()
    private val rawPending = mutableMapOf<String, ArrayDeque<Int>>()
    private val requestId = request.requestId
    private val sessionId = request.sessionId
    private val scene = request.scene.orEmpty()
    private val inputLength = request.input.length
    private val inputPreview = request.input.debugInputPreview()
    private val requiresBusinessAction = userText.requiresBusinessAction()
    private var runId: String = request.resumeRunId.orEmpty()
    private var finalStatus: String? = null
    private var finalCompletionOutcome: String? = null
    private var finalErrorCode: String? = null
    private var failureEventCode: String? = null
    private var finalResponse: String = ""
    private var eventDispatch: DebugRuntimeEventDispatchEvidence = DebugRuntimeEventDispatchEvidence()
    private var memory: DebugRuntimeMemoryEvidence = DebugRuntimeMemoryEvidence()
    private val emptyCompletionObservations = mutableListOf<DebugEmptyCompletionObservation>()

    fun recordEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Started -> runId = event.runId
            is AgentEvent.NativeToolCallStarted -> recordPublicStarted(
                event.runId,
                event.toolName,
                event.toolCallId,
                nowEpochMillis(),
            )
            is AgentEvent.NativeToolCallFinished -> recordPublicFinished(
                event.runId,
                event.toolName,
                event.toolCallId,
                nowEpochMillis(),
            )
            is AgentEvent.ResultAvailable -> recordResult(
                event.result.runId,
                event.result.status.name,
                event.result.completion.outcome.name,
                event.result.errorCode,
                event.result.response,
            )
            is AgentEvent.Failed -> recordFailed(event.runId, event.code)
            // Hosted web_search has no raw pre/post; still count as a business tool for replay.
            is AgentEvent.SearchActivity -> recordSearchActivity(event)
            is AgentEvent.EmptyCompletionObserved -> recordEmptyCompletion(event)
            else -> Unit
        }
    }

    fun recordRawToolEvent(title: String, body: String) {
        when {
            title.startsWith("Raw tool arguments") -> recordRawStarted(
                parseRawToolName(body),
                parseRawToolCallId(body),
                body.timestampMillisOrNow(nowEpochMillis),
            )
            title.startsWith("Raw tool result") -> {
                recordRawFinished(
                    parseRawToolName(body),
                    parseRawToolCallId(body),
                    rawResultStatus(body),
                    body.timestampMillisOrNow(nowEpochMillis),
                )
            }
        }
    }

    fun recordCancelled() {
        if (finalStatus == null) finalStatus = RuntimeRunStatus.CANCELLED.name
    }

    fun recordRuntimeFailure() {
        if (finalStatus == null) finalStatus = RuntimeRunStatus.FAILED.name
    }

    fun recordTerminalResult(result: RuntimeResult) {
        recordResult(
            eventRunId = result.runId,
            status = result.status.name,
            completionOutcome = result.completion.outcome.name,
            errorCode = result.errorCode,
            response = result.response,
        )
    }

    fun recordPendingPublicToolFinishes() {
        tools.filter { it.publicStarted && !it.publicFinished }.forEach { tool ->
            recordPublicFinished(runId.ifBlank { requestId }, tool.toolName, tool.toolCallId, nowEpochMillis())
        }
    }

    fun recordPendingRawToolFinishes(status: String) {
        tools.filter { it.rawPre && !it.rawPost }.forEach { tool ->
            recordRawFinished(tool.toolName, tool.toolCallId, status, nowEpochMillis())
        }
    }

    fun snapshot(): DebugRunEvidence {
        val immutableTools = tools.map { it.toEvidence() }
        return DebugRunEvidence(
            requestId = requestId,
            runId = runId,
            sessionId = sessionId,
            scene = scene,
            inputLength = inputLength,
            inputPreview = inputPreview,
            provider = providerLabel,
            stream = streamLabel,
            backend = backendLabel,
            finalStatus = finalStatus,
            finalCompletionOutcome = finalCompletionOutcome,
            finalErrorCode = finalErrorCode,
            failureEventCode = failureEventCode,
            finalResponse = finalResponse,
            tools = immutableTools,
            requiresBusinessAction = requiresBusinessAction,
            counts = DebugRunEvidenceCounts.from(immutableTools),
            eventDispatch = eventDispatch,
            memory = memory,
            loopProbes = loopProbeEvidence(),
        )
    }

    private fun loopProbeEvidence(): DebugLoopProbeEvidence {
        val last = emptyCompletionObservations.lastOrNull()
        return DebugLoopProbeEvidence(
            emptyCompletionObservedCount = emptyCompletionObservations.size,
            emptyCompletionContinueCount = emptyCompletionObservations.count { it.continued },
            lastEmptyCompletionKind = last?.kind,
            lastEmptyCompletionContinued = last?.continued,
            lastEmptyCompletionModelRound = last?.modelRound,
            lastEmptyCompletionTextChars = last?.textChars,
            lastEmptyCompletionThinkingChars = last?.thinkingChars,
            lastHadStateAdvancingWork = last?.hadStateAdvancingWork,
            lastSuccessfulBackendToolCount = last?.successfulBackendToolCount,
            observations = emptyCompletionObservations.toList(),
        )
    }

    private fun recordEmptyCompletion(event: AgentEvent.EmptyCompletionObserved) {
        runId = event.runId.ifBlank { runId }
        emptyCompletionObservations += DebugEmptyCompletionObservation(
            modelRound = event.modelRound,
            kind = event.kind,
            continued = event.continued,
            continueCount = event.continueCount,
            textChars = event.textChars,
            thinkingChars = event.thinkingChars,
            hadStateAdvancingWork = event.hadStateAdvancingWork,
            successfulBackendToolCount = event.successfulBackendToolCount,
        )
    }

    fun write(): File {
        evidenceDir.mkdirs()
        val file = File(evidenceDir, debugRunEvidenceFileName(requestId))
        file.writeText(DebugRunEvidenceJson.write(snapshot()), Charsets.UTF_8)
        return file
    }

    fun recordEventDispatch(evidence: DebugRuntimeEventDispatchEvidence) {
        eventDispatch = evidence
    }

    fun recordMemoryEvidence(evidence: DebugRuntimeMemoryEvidence) {
        memory = evidence
    }

    private fun recordPublicStarted(eventRunId: String, toolName: String, toolCallId: String, timestampMs: Long) {
        runId = eventRunId
        val tool = toolByCallId(toolCallId)
            ?: findLatest(toolName) { it.rawPre && !it.publicStarted }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        tool.publicStarted = true
        tool.publicStartedAtEpochMillis = timestampMs
        publicPending.getOrPut(toolName) { ArrayDeque() }.addLast(tool.sequence)
    }

    private fun recordPublicFinished(eventRunId: String, toolName: String, toolCallId: String, timestampMs: Long) {
        runId = eventRunId
        val sequence = toolByCallId(toolCallId)?.sequence ?: publicPending[toolName]?.removeLastOrNull()
        val tool = toolBySequenceOrNull(sequence)
            ?: findLatest(toolName) { !it.publicFinished }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        removePending(publicPending, toolName, tool.sequence)
        tool.publicFinished = true
        tool.publicFinishedAtEpochMillis = timestampMs
    }

    private fun recordRawStarted(toolName: String, toolCallId: String, timestampMs: Long) {
        val tool = toolByCallId(toolCallId)
            ?: findLatest(toolName) { it.publicStarted && !it.rawPre }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        tool.rawPre = true
        tool.rawPreAtEpochMillis = timestampMs
        rawPending.getOrPut(toolName) { ArrayDeque() }.addLast(tool.sequence)
    }

    private fun recordRawFinished(toolName: String, toolCallId: String, status: String, timestampMs: Long) {
        val sequence = toolByCallId(toolCallId)?.sequence
            ?: rawPending[toolName]?.removeLastOrNull()
            ?: latestUnfinishedRawSequence().takeIf { toolName == "unknown" && toolCallId.isBlank() }
        val tool = toolBySequenceOrNull(sequence)
            ?: findLatest(toolName) { !it.rawPost }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        removePending(rawPending, toolName, tool.sequence)
        tool.rawPost = true
        tool.rawPostStatus = status
        tool.rawPostAtEpochMillis = timestampMs
    }

    private fun recordResult(
        eventRunId: String,
        status: String,
        completionOutcome: String?,
        errorCode: String?,
        response: String,
    ) {
        runId = eventRunId
        finalStatus = status
        finalCompletionOutcome = completionOutcome
        finalErrorCode = errorCode.orEmpty().ifBlank { null }
        finalResponse = response
    }

    private fun recordFailed(eventRunId: String, code: String) {
        runId = eventRunId
        failureEventCode = code
        if (finalStatus == null) finalStatus = RuntimeRunStatus.FAILED.name
    }

    /**
     * Provider-hosted web_search (Anthropic server tool / OpenAI hosted search / DSML-shaped
     * web_search remapped to SearchActivity). There is no raw hook pair; mark public start+finish
     * so replay treats it as a meaningful business tool (music chat, trivia, etc.).
     */
    private fun recordSearchActivity(event: AgentEvent.SearchActivity) {
        runId = event.runId.ifBlank { runId }
        val now = nowEpochMillis()
        val tool = findLatest(WEB_SEARCH_TOOL_NAME) { it.publicStarted && !it.publicFinished }
            ?: findLatest(WEB_SEARCH_TOOL_NAME) {
                // Merge resultCount-only follow-ups into the last completed search card.
                event.queryPreview.isBlank() && it.publicFinished
            }
            ?: newTool(
                toolName = WEB_SEARCH_TOOL_NAME,
                toolCallId = "search-activity-${tools.size + 1}",
            )
        if (!tool.publicStarted) {
            tool.publicStarted = true
            tool.publicStartedAtEpochMillis = now
        }
        // Hosted search is server-side; close public lifecycle immediately so replay does not
        // treat it as pending (no NativeToolCallFinished for SearchActivity-only path).
        tool.publicFinished = true
        tool.publicFinishedAtEpochMillis = now
    }

    private fun newTool(toolName: String, toolCallId: String = ""): MutableDebugRunToolEvidence {
        val tool = MutableDebugRunToolEvidence(tools.size + 1, toolCallId, toolName)
        tools += tool
        rememberToolCallId(tool, toolCallId)
        return tool
    }

    private fun findLatest(
        toolName: String,
        predicate: (MutableDebugRunToolEvidence) -> Boolean,
    ): MutableDebugRunToolEvidence? = tools.asReversed().firstOrNull { it.toolName == toolName && predicate(it) }

    private fun toolBySequenceOrNull(sequence: Int?): MutableDebugRunToolEvidence? =
        sequence?.let { seq -> tools.firstOrNull { it.sequence == seq } }

    private fun toolByCallId(toolCallId: String): MutableDebugRunToolEvidence? =
        toolCallId.takeIf { it.isNotBlank() }?.let(toolsByCallId::get)?.let(::toolBySequence)

    private fun rememberToolCallId(tool: MutableDebugRunToolEvidence, toolCallId: String) {
        if (toolCallId.isBlank()) return
        tool.toolCallId = tool.toolCallId.ifBlank { toolCallId }
        toolsByCallId[toolCallId] = tool.sequence
    }

    private fun toolBySequence(sequence: Int): MutableDebugRunToolEvidence? =
        tools.firstOrNull { it.sequence == sequence }

    private fun removePending(pending: MutableMap<String, ArrayDeque<Int>>, toolName: String, sequence: Int) {
        val stack = pending[toolName] ?: return
        val remaining = stack.filterNot { it == sequence }
        stack.clear()
        stack.addAll(remaining)
        if (stack.isEmpty()) pending.remove(toolName)
    }

    private fun latestUnfinishedRawSequence(): Int? =
        tools.asReversed().firstOrNull { it.rawPre && !it.rawPost }?.sequence
}

/** Shared by the writer and the read-only Trajectory enrichment so both resolve one path. */
internal fun debugRunEvidenceFileName(requestId: String): String =
    requestId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_").ifBlank { "debug-run" } + ".json"

private const val WEB_SEARCH_TOOL_NAME = "web_search"
