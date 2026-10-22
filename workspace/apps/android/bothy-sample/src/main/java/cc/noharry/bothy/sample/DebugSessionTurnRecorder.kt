package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

internal class DebugSessionTurnRecorder(
    private val request: AgentRequest,
    private val userText: String,
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    private val tools = mutableListOf<MutableDebugSessionToolCall>()
    private val toolsByCallId = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<String, ArrayDeque<Int>>()
    private var runId: String = request.resumeRunId.orEmpty()
    private var finalStatus: String = RuntimeRunStatus.RUNNING.name
    private var finalResponse: String = ""
    private var completionOutcome: String = RuntimeCompletionOutcome.UNKNOWN.name
    private var replayStatus: String = DEBUG_REPLAY_STATUS_NOT_RUN
    private var replaySummary: String = ""
    private var usage = DebugTokenUsageState()

    fun recordEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Started -> runId = event.runId
            is AgentEvent.TokenUsageDelta -> usage = usage.plus(event.toDebugUsageDelta())
            is AgentEvent.NativeToolCallStarted -> recordToolStarted(event.toolName, event.arguments, event.toolCallId)
            is AgentEvent.NativeToolCallFinished -> recordToolFinished(
                toolName = event.toolName,
                resultPreview = event.result,
                status = RAW_STATUS_OK,
                toolCallId = event.toolCallId,
                removePendingOnMatch = false,
            )
            is AgentEvent.ResultAvailable -> {
                runId = event.result.runId
                finalStatus = event.result.status.name
                finalResponse = event.result.response
                completionOutcome = event.result.completion.outcome.name
            }
            is AgentEvent.Failed -> {
                runId = event.runId
                finalStatus = RuntimeRunStatus.FAILED.name
                finalResponse = event.message
            }
            // A run that stops to ask ends this exchange without a result event. Keep the question as the reply so
            // the next turn can bind a short answer to it; a later cancellation of the waiting run does not erase it.
            is AgentEvent.RuntimeBlocked -> event.followUpQuestion()?.let { (blockedRunId, question) ->
                runId = blockedRunId
                finalStatus = RuntimeRunStatus.WAITING_USER_INPUT.name
                finalResponse = question
            }
            else -> Unit
        }
    }

    fun recordRawToolEvent(title: String, body: String) {
        when {
            title.startsWith("Raw tool arguments") -> recordToolStarted(
                toolName = parseRawToolName(body),
                argumentsPreview = body.rawLineValue("arguments"),
                toolCallId = parseRawToolCallId(body),
            )
            title.startsWith("Raw tool result") -> recordToolFinished(
                toolName = parseRawToolName(body),
                resultPreview = body.rawLineValue("result"),
                status = rawStatus(body),
                toolCallId = parseRawToolCallId(body),
                removePendingOnMatch = true,
            )
        }
    }

    fun recordCancelled() {
        if (finalStatus == RuntimeRunStatus.RUNNING.name) {
            finalStatus = RuntimeRunStatus.CANCELLED.name
        }
    }

    fun recordRuntimeFailure(message: String?) {
        if (finalStatus == RuntimeRunStatus.RUNNING.name) {
            finalStatus = RuntimeRunStatus.FAILED.name
            finalResponse = message.orEmpty()
        }
    }

    fun recordReplay(replay: DebugRunReplayResult) {
        replayStatus = if (replay.passed) DEBUG_REPLAY_STATUS_PASSED else DEBUG_REPLAY_STATUS_FAILED
        replaySummary = replay.summary
    }

    fun snapshot(): DebugSessionTurn =
        DebugSessionTurn(
            turnId = request.requestId,
            requestId = request.requestId,
            runId = runId.ifBlank { request.requestId },
            sessionId = request.sessionId,
            scene = request.scene.orEmpty(),
            userText = userText,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = System.currentTimeMillis(),
            finalStatus = finalStatus,
            finalResponse = finalResponse,
            tools = tools.map { it.toImmutable() },
            completionOutcome = completionOutcome,
            replayStatus = replayStatus,
            replaySummary = replaySummary,
            usage = usage,
        )

    private fun recordToolStarted(toolName: String, argumentsPreview: String, toolCallId: String = "") {
        val call = toolByCallId(toolCallId) ?: tools.asReversed().firstOrNull {
            it.toolName == toolName && it.argumentsPreview.isBlank()
        } ?: newTool(toolName, toolCallId)
        rememberToolCallId(call, toolCallId)
        call.argumentsPreview = redactDebugToolPayload(argumentsPreview).take(MAX_TOOL_PREVIEW_CHARS)
        pending.getOrPut(toolName) { ArrayDeque() }.addLast(call.sequence)
    }

    private fun recordToolFinished(
        toolName: String,
        resultPreview: String,
        status: String,
        toolCallId: String = "",
        removePendingOnMatch: Boolean = true,
    ) {
        val sequence = toolByCallId(toolCallId)?.sequence ?: pending[toolName]?.removeLastOrNull()
        val call = sequence?.let { seq -> tools.firstOrNull { it.sequence == seq } }
            ?: tools.asReversed().firstOrNull { it.toolName == toolName && it.resultPreview.isBlank() }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(call, toolCallId)
        if (removePendingOnMatch) removePending(toolName, call.sequence)
        call.resultPreview = redactDebugToolPayload(resultPreview).take(MAX_TOOL_PREVIEW_CHARS)
        call.status = status
    }

    private fun newTool(toolName: String, toolCallId: String = ""): MutableDebugSessionToolCall =
        MutableDebugSessionToolCall(tools.size + 1, toolName, toolCallId).also {
            tools += it
            rememberToolCallId(it, toolCallId)
        }

    private fun toolByCallId(toolCallId: String): MutableDebugSessionToolCall? =
        toolCallId.takeIf { it.isNotBlank() }?.let(toolsByCallId::get)?.let { sequence ->
            tools.firstOrNull { it.sequence == sequence }
        }

    private fun rememberToolCallId(call: MutableDebugSessionToolCall, toolCallId: String) {
        if (toolCallId.isBlank()) return
        call.toolCallId = call.toolCallId.ifBlank { toolCallId }
        toolsByCallId[toolCallId] = call.sequence
    }

    private fun removePending(toolName: String, sequence: Int) {
        val stack = pending[toolName] ?: return
        val remaining = stack.filterNot { it == sequence }
        stack.clear()
        stack.addAll(remaining)
        if (stack.isEmpty()) pending.remove(toolName)
    }
}

private data class MutableDebugSessionToolCall(
    val sequence: Int,
    val toolName: String,
    var toolCallId: String = "",
    var argumentsPreview: String = "",
    var resultPreview: String = "",
    var status: String = RAW_STATUS_UNKNOWN,
) {
    fun toImmutable(): DebugSessionToolCall =
        DebugSessionToolCall(sequence, toolName, argumentsPreview, resultPreview, status, toolCallId)
}

private fun String.rawLineValue(name: String): String =
    lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter("=").orEmpty().trim()

private fun rawStatus(body: String): String =
    when (body.rawLineValue("ok").lowercase()) {
        "true" -> RAW_STATUS_OK
        "false" -> RAW_STATUS_FAILED
        else -> RAW_STATUS_UNKNOWN
    }

// Kept large enough to hold full write_file payloads: persisted sessions are the source
// for HTML artifact persistence and for restored-timeline page previews.
private const val MAX_TOOL_PREVIEW_CHARS = 64 * 1024
