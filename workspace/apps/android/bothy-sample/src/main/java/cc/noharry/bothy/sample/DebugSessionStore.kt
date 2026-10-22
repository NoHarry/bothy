package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.io.File

internal class DebugSessionStore(private val root: File) {
    init {
        root.mkdirs()
        refreshQueryFiles()
    }

    fun ensureSession(sessionId: String = newSessionId(), title: String = "New session"): DebugSessionSummary {
        readSummary(root, sessionId)?.let { return it }
        return ensureDebugSession(root, sessionId, title).also { refreshQueryFiles() }
    }

    fun listSessions(): List<DebugSessionSummary> =
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { dir -> File(dir, META_FILE).takeIf { it.isFile }?.readText()?.let(DebugSessionJson::decodeSummary) }
            .sortedByDescending { it.updatedAtEpochMillis }

    fun readContext(sessionId: String, limit: Int = RECENT_TURN_LIMIT): DebugSessionContext {
        val summary = ensureSession(sessionId)
        val turns = readTurns(sessionId).takeLast(limit)
        return DebugSessionContext(summary, turns)
    }

    fun readTurns(sessionId: String): List<DebugSessionTurn> {
        val file = turnsFile(root, sessionId)
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull(DebugSessionJson::decodeTurn)
    }

    fun appendTurn(turn: DebugSessionTurn): DebugSessionSummary =
        appendDebugSessionTurn(root, turn, readTurns(turn.sessionId)).also { refreshQueryFiles() }

    fun deleteSession(sessionId: String): Boolean {
        val deleted = sessionDir(root, sessionId).takeIf { it.exists() }?.deleteRecursively() == true
        refreshQueryFiles()
        return deleted
    }

    private fun refreshQueryFiles() {
        publishDebugSessionQueryFiles(root, listSessions(), ::readTurns)
    }
}

private fun ensureDebugSession(root: File, sessionId: String, title: String): DebugSessionSummary {
    readSummary(root, sessionId)?.let { return it }
    val summary = DebugSessionSummary(sessionId, title, now(), turnCount = 0, compactedSummary = "")
    writeSummary(root, summary)
    return summary
}

private fun appendDebugSessionTurn(
    root: File,
    turn: DebugSessionTurn,
    turnsBeforeAppend: List<DebugSessionTurn>,
): DebugSessionSummary {
    val dir = sessionDir(root, turn.sessionId).apply { mkdirs() }
    File(dir, TURNS_FILE).appendText(DebugSessionJson.encodeTurn(turn) + "\n", Charsets.UTF_8)
    val turns = turnsBeforeAppend + turn
    val previous = readSummary(root, turn.sessionId)
    val title = previous?.title?.takeIf { it != "New session" } ?: turn.userText.titleFromUserText()
    val summary = DebugSessionSummary(turn.sessionId, title, turn.completedAtEpochMillis, turns.size, compactTurns(turns))
    writeSummary(root, summary)
    return summary
}

private fun writeSummary(root: File, summary: DebugSessionSummary) {
    val dir = sessionDir(root, summary.sessionId).apply { mkdirs() }
    File(dir, META_FILE).writeText(DebugSessionJson.encodeSummary(summary), Charsets.UTF_8)
}

private fun readSummary(root: File, sessionId: String): DebugSessionSummary? {
    val file = File(sessionDir(root, sessionId), META_FILE)
    return if (file.isFile) DebugSessionJson.decodeSummary(file.readText(Charsets.UTF_8)) else null
}

private fun turnsFile(root: File, sessionId: String): File = File(sessionDir(root, sessionId), TURNS_FILE)

private fun sessionDir(root: File, sessionId: String): File = File(root, encodeSessionId(sessionId))

internal fun DebugSessionStore.transcriptItems(sessionId: String): List<DebugConversationItem> =
    readTurns(sessionId).flatMapIndexed { index, turn -> turn.toConversationItems(index + 1) }

internal fun DebugSessionStore.modelContext(sessionId: String): String {
    val context = readContext(sessionId)
    val semanticTurns = context.recentTurns.filter { it.isSemanticConversationHistory() }
    // Only the latest turn, even a failed or cancelled one, can hold an offer a bare reply may accept.
    val latestTurnId = context.recentTurns.lastOrNull()?.turnId
    val recentLines = semanticTurns.asReversed().map { turn -> turn.semanticContextLine(offerIsCurrent = turn.turnId == latestTurnId) }
    val lastAction = context.recentTurns.filter { it.isFirstPartyReusableHistory() }
        .asReversed().flatMap { it.tools.asReversed() }
        .firstOrNull { it.isSuccessfulMeaningfulAction() }
    val compactedSummary = compactTurns(readTurns(sessionId)).ifBlank { "none" }
    return boundedModelContext(
        sessionId = context.summary.sessionId,
        lastSuccessfulAction = lastAction?.toolName ?: "none",
        recentLines = recentLines,
        compactedSummary = compactedSummary,
    )
}

private fun boundedModelContext(
    sessionId: String,
    lastSuccessfulAction: String,
    recentLines: List<String>,
    compactedSummary: String,
): String {
    val output = mutableListOf<String>()
    var usedBytes = 0
    fun append(line: String): Boolean {
        val addedBytes = line.toByteArray(Charsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (usedBytes + addedBytes > MAX_DEBUG_SESSION_CONTEXT_BYTES) return false
        output += line
        usedBytes += addedBytes
        return true
    }
    listOf(
        "Current debug session:",
        "sessionId=$sessionId",
        "historyPolicy=conversation semantics only; action success requires COMPLETED plus SUCCEEDED",
        OFFER_POLICY,
        "lastSuccessfulAction=$lastSuccessfulAction",
        "recentTurnsNewestFirst:",
    ).forEach { check(append(it)) }
    for (line in recentLines.ifEmpty { listOf("- none") }) {
        if (!append(line)) break
    }
    if (append("olderConversationSummary:")) {
        for (line in compactedSummary.lineSequence()) {
            if (!append(line)) break
        }
    }
    return output.joinToString("\n")
}

private fun compactTurns(turns: List<DebugSessionTurn>): String {
    val older = turns.dropLast(RECENT_TURN_LIMIT).filter { it.isSemanticConversationHistory() }
    if (older.isEmpty()) return ""
    return older.takeLast(COMPACTED_TURN_LIMIT).joinToString("\n") { turn ->
        val lastTool = turn.tools.lastOrNull()?.toolName ?: "none"
        "- ${turn.userText.oneLine()} -> $lastTool -> ${turn.finalResponse.oneLine()}"
    }.take(MAX_COMPACTED_CHARS)
}

private fun DebugSessionTurn.semanticContextLine(offerIsCurrent: Boolean): String {
    val actionEvidence = if (isFirstPartyReusableHistory()) {
        tools.filter { it.isSuccessfulMeaningfulAction() }
            .joinToString(", ") { "${it.toolName}:${it.status}" }
            .ifBlank { "none" }
            .contextPreview(MAX_CONTEXT_ACTION_EVIDENCE_CHARS)
    } else {
        "unverified"
    }
    return "- user=${userText.contextPreview(MAX_CONTEXT_USER_CHARS)} | " +
        "assistant=${finalResponse.contextPreview(MAX_CONTEXT_ASSISTANT_CHARS)} | " +
        "runStatus=$finalStatus | completion=$completionOutcome | replay=$replayStatus | " +
        "offers=${if (offerIsCurrent) "current" else "superseded"} | successfulActionEvidence=$actionEvidence"
}

private fun DebugSessionToolCall.isSuccessfulMeaningfulAction(): Boolean =
    status == RAW_STATUS_OK && isMeaningfulAction()

private fun String.contextPreview(maxChars: Int): String {
    val normalized = oneLine()
    if (normalized.length <= maxChars) return normalized
    val suffixChars = maxChars / 2
    val prefixChars = maxChars - suffixChars - CONTEXT_ELLIPSIS.length
    return normalized.take(prefixChars) + CONTEXT_ELLIPSIS + normalized.takeLast(suffixChars)
}

internal fun DebugSessionTurn.isSemanticConversationHistory(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus) }.getOrNull() ?: return false
    val outcome = runCatching { RuntimeCompletionOutcome.valueOf(completionOutcome) }
        .getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    // A turn that stopped to ask is conversation too: its question is what the next short reply answers.
    return finalResponse.isNotBlank() && (
        status == RuntimeRunStatus.WAITING_USER_INPUT ||
            status == RuntimeRunStatus.COMPLETED && outcome != RuntimeCompletionOutcome.UNKNOWN
        )
}

internal fun DebugSessionTurn.isFirstPartyReusableHistory(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus) }.getOrNull() ?: return false
    val outcome = runCatching { RuntimeCompletionOutcome.valueOf(completionOutcome) }
        .getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    return RuntimeCompletionSummary.firstPartyPass(status, RuntimeCompletionSummary(outcome = outcome))
}

internal fun debugSessionStore(context: Context): DebugSessionStore =
    DebugSessionStore(File(debugWorkspaceRoot(context), DEBUG_SESSIONS_DIR))

internal fun newSessionId(): String = "debug-session-${System.currentTimeMillis()}"

private fun DebugSessionTurn.toConversationItems(turnIndex: Int): List<DebugConversationItem> {
    val baseId = turnIndex * PERSISTED_ITEM_ID_BLOCK
    return listOf(
            DebugConversationItem.TurnHeader(
                id = baseId.toLong(),
                label = debugTurnLabel(userText),
                preview = userText.take(TURN_PREVIEW_CHARS).replace("\n", " "),
                fullText = userText,
            ),
    ) + tools.map { tool ->
        DebugConversationItem.ToolCallItem(
            id = (baseId + tool.sequence).toLong(),
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            argumentsJson = tool.argumentsPreview,
            resultJson = tool.resultPreview,
            status = tool.status.toToolStatus(),
            startedAt = startedAtEpochMillis,
            finishedAt = completedAtEpochMillis,
        )
    } + finalResponseConversationItems(baseId, finalStatus, finalResponse)
}

private fun finalResponseConversationItems(
    baseId: Int,
    finalStatus: String,
    finalResponse: String,
): List<DebugConversationItem> =
    listOfNotNull(
        finalResponse.takeIf { it.isNotBlank() }?.let { response ->
            DebugConversationItem.MessageItem(
                id = (baseId + PERSISTED_FINAL_RESPONSE_OFFSET).toLong(),
                role = DebugTranscriptRole.ASSISTANT,
                content = response.trimForDisplay(),
                collapsed = false,
                final = true,
            )
        },
        DebugConversationItem.RuntimePhaseItem(
            id = (baseId + PERSISTED_RESULT_OFFSET).toLong(),
            title = "Runtime result $finalStatus",
            body = finalResponse,
            collapsed = true,
            markdown = true,
        ),
    )

private fun String.toToolStatus(): DebugToolCallStatus =
    when (this) {
        RAW_STATUS_OK -> DebugToolCallStatus.DONE
        RAW_STATUS_FAILED -> DebugToolCallStatus.FAILED
        RAW_STATUS_UNKNOWN -> DebugToolCallStatus.UNKNOWN
        else -> DebugToolCallStatus.UNKNOWN
    }

private fun String.titleFromUserText(): String =
    oneLine().take(SESSION_TITLE_CHARS).ifBlank { "Debug session" }

private fun String.oneLine(): String =
    replace("\n", " ").replace(Regex("""\s+"""), " ").trim()

private fun now(): Long = System.currentTimeMillis()

private const val DEBUG_SESSIONS_DIR = "debug-sessions"
private const val META_FILE = "meta.json"
private const val TURNS_FILE = "turns.jsonl"
private const val OFFER_POLICY =
    "offerPolicy=a bare reply may accept only an offer in the turn marked offers=current; offers in superseded turns " +
        "and olderConversationSummary were replaced by later turns and need a new explicit request"
private const val RECENT_TURN_LIMIT = 6
private const val COMPACTED_TURN_LIMIT = 20
private const val MAX_COMPACTED_CHARS = 1800
internal const val MAX_DEBUG_SESSION_CONTEXT_BYTES = 8 * 1024
private const val MAX_CONTEXT_USER_CHARS = 160
private const val MAX_CONTEXT_ASSISTANT_CHARS = 360
private const val MAX_CONTEXT_ACTION_EVIDENCE_CHARS = 480
private const val CONTEXT_ELLIPSIS = " … "
private const val SESSION_TITLE_CHARS = 32
private const val TURN_PREVIEW_CHARS = 60
private const val PERSISTED_ITEM_ID_BLOCK = 1000
private const val PERSISTED_FINAL_RESPONSE_OFFSET = 899
private const val PERSISTED_RESULT_OFFSET = 900
