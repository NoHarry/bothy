package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class DebugTranscriptStore {
    private val items = mutableListOf<DebugConversationItem>()
    private val pendingToolCalls = mutableMapOf<String, ArrayDeque<Long>>()
    private val toolItemsByCallId = mutableMapOf<String, Long>()
    private val confirmationItemsByPendingId = mutableMapOf<String, Long>()
    private var nextItemId: Long = 1
    private var currentTurnId: Long = 0
    private var lastAssistantItemId: Long? = null
    private var lastThinkingItemId: Long? = null

    fun snapshot(): List<DebugConversationItem> = items.toList()

    fun replaceAll(restored: List<DebugConversationItem>) {
        items.clear()
        items += restored
        nextItemId = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        currentTurnId = items.filterIsInstance<DebugConversationItem.TurnHeader>().lastOrNull()?.id ?: 0L
        lastAssistantItemId = null
        lastThinkingItemId = null
        pendingToolCalls.clear()
        toolItemsByCallId.clear()
        confirmationItemsByPendingId.clear()
    }

    fun toggle(updated: DebugConversationItem): Boolean {
        val index = items.indexOfFirst { it.id == updated.id }
        if (index < 0) return false
        items[index] = updated
        return true
    }

    fun openTurn(text: String) {
        currentTurnId = nextItemId++
        lastAssistantItemId = null
        lastThinkingItemId = null
        pendingToolCalls.clear()
        toolItemsByCallId.clear()
        items += DebugConversationItem.TurnHeader(
            id = currentTurnId,
            label = debugTurnLabel(text),
            preview = text.take(TURN_PREVIEW_CHARS).replace("\n", " "),
            fullText = text,
            collapsed = false,
        )
        trimItems()
    }

    fun appendConfirmationRequired(
        pendingId: String,
        domain: String,
        mode: DebugConfirmationMode,
    ) {
        val existingId = confirmationItemsByPendingId[pendingId]
        if (existingId != null) {
            updateConfirmation(pendingId, DebugConfirmationStatus.PENDING)
            return
        }
        val id = nextItemId++
        confirmationItemsByPendingId[pendingId] = id
        items += DebugConversationItem.ConfirmationItem(
            id = id,
            pendingId = pendingId,
            domain = domain,
            mode = mode,
            createdAt = nowEpochMillis(),
        )
        trimItems()
    }

    fun updateConfirmation(pendingId: String, status: DebugConfirmationStatus): Boolean {
        val id = confirmationItemsByPendingId[pendingId] ?: return false
        val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ConfirmationItem }
        if (index < 0) return false
        val item = items[index] as DebugConversationItem.ConfirmationItem
        items[index] = item.copy(
            status = status,
            resolvedAt = if (status == DebugConfirmationStatus.PENDING) null else nowEpochMillis(),
        )
        return true
    }

    fun appendThinkingDelta(text: String) {
        appendDelta(text, DebugTranscriptRole.THINKING, { lastThinkingItemId }, { lastThinkingItemId = it })
    }

    fun appendAssistantDelta(text: String) {
        appendDelta(text, DebugTranscriptRole.ASSISTANT, { lastAssistantItemId }, { lastAssistantItemId = it })
    }

    fun appendFinalResponse(text: String) {
        if (text.isBlank()) return
        val normalized = text.trimForDisplay()
        // Streaming already painted the same assistant body this turn — avoid a second copy. An earlier turn's
        // identical reply ("不客气" twice) is not this one.
        val lastAssistant = lastAssistantItemId?.let { id ->
            items.firstOrNull { it.id == id } as? DebugConversationItem.MessageItem
        }
        if (lastAssistant != null && lastAssistant.content == normalized) {
            items[items.indexOf(lastAssistant)] = lastAssistant.copy(final = true)
            return
        }
        items += DebugConversationItem.MessageItem(
            id = nextItemId++,
            role = DebugTranscriptRole.ASSISTANT,
            content = normalized,
            collapsed = false,
            final = true,
        )
        trimItems()
    }

    fun toolIssuesForCurrentTurn(): DebugToolIssueSummary = currentToolIssues()

    fun appendRawToolEvent(title: String, body: String) {
        when {
            title.startsWith("Raw tool arguments") -> {
                recordToolCallStarted(parseRawToolCallId(body), parseRawToolName(body), body)
            }
            title.startsWith("Raw tool result") -> {
                recordToolCallFinished(parseRawToolCallId(body), parseRawToolName(body), body)
            }
            else -> appendStatus("$title\n$body")
        }
    }

    fun recordPublicToolCallStarted(
        toolName: String,
        toolCallId: String = "",
        arguments: String = "",
    ) {
        if (hasPendingRawToolCall(toolCallId, toolName)) return
        // Hosted web_search should arrive via SearchActivity (with query). If a public
        // NativeToolCallStarted still appears (legacy path), keep a card but treat
        // redacted public JSON as empty so SearchActivity can fill the query.
        if (toolName.equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true)) {
            val query = extractDisplayWebSearchQuery(arguments)
            recordSearchActivity(
                provider = "public",
                queryPreview = query,
                resultCount = null,
            )
            // Keep toolCallId mapping if we already had a matching card this turn.
            items.asReversed()
                .filterIsInstance<DebugConversationItem.ToolCallItem>()
                .firstOrNull { it.toolName.equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true) && it.id > currentTurnId }
                ?.let { rememberToolCallId(toolCallId, it.id) }
            return
        }
        val id = nextItemId++
        rememberToolCallId(toolCallId, id)
        pendingToolCalls.getOrPut(toolName) { ArrayDeque() }.addLast(id)
        val args = arguments.trim().takeIf { it.isNotBlank() && !it.isRedactedPublicJson() }
        items += DebugConversationItem.ToolCallItem(
            id = id,
            toolCallId = toolCallId,
            toolName = toolName,
            argumentsJson = args ?: PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER,
            resultJson = null,
            status = DebugToolCallStatus.RUNNING,
            startedAt = nowEpochMillis(),
        )
        trimItems()
    }

    fun recordPublicToolCallFinished(
        toolName: String,
        toolCallId: String = "",
        result: String = "",
    ): Boolean {
        // web_search cards are SearchActivity-owned; ignore redacted public finishes.
        if (toolName.equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true)) {
            val id = itemIdForToolCall(toolCallId)
                ?: items.asReversed()
                    .filterIsInstance<DebugConversationItem.ToolCallItem>()
                    .firstOrNull {
                        it.toolName.equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true) && it.id > currentTurnId
                    }?.id
                ?: return false
            val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
            if (index < 0) return false
            val item = items[index] as DebugConversationItem.ToolCallItem
            val cleaned = result.trim().takeIf { it.isNotBlank() && !it.isRedactedPublicJson() }
            items[index] = item.copy(
                resultJson = cleaned ?: item.resultJson ?: """{"hosted":true}""",
                status = DebugToolCallStatus.DONE,
                finishedAt = item.finishedAt ?: nowEpochMillis(),
            )
            return true
        }
        // Keep the pending entry until the raw post hook arrives so legacy raw bodies without id can still pair.
        val id = itemIdForToolCall(toolCallId)
            ?: pendingToolCalls[toolName]?.lastOrNull()
            ?: return false
        val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
        if (index < 0) return false
        val item = items[index] as DebugConversationItem.ToolCallItem
        val resultBody = result.trim().takeIf { it.isNotBlank() && !it.isRedactedPublicJson() }
            ?: item.resultJson
            ?: PUBLIC_TOOL_RESULT_PLACEHOLDER
        items[index] = item.copy(
            resultJson = resultBody,
            status = DebugToolCallStatus.DONE,
            finishedAt = nowEpochMillis(),
        )
        return true
    }

    /**
     * Hosted web_search (Anthropic server tool / OpenAI hosted search) is not a native
     * ToolUse, so it never gets raw pre/post hooks. Surface query + result count as a
     * normal tool card so the user can expand and see what was searched.
     */
    fun recordSearchActivity(
        provider: String,
        queryPreview: String,
        resultCount: Int?,
    ) {
        val query = queryPreview.trim()
        val matching = items.asReversed()
            .filterIsInstance<DebugConversationItem.ToolCallItem>()
            .firstOrNull { it.toolName.equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true) && it.id > currentTurnId }
        if (matching != null) {
            val index = items.indexOfFirst { it.id == matching.id }
            if (index >= 0) {
                val item = items[index] as DebugConversationItem.ToolCallItem
                val nextArgs = when {
                    query.isNotBlank() && item.argumentsJson.isReplaceableWebSearchArgs() ->
                        """{"query":${query.jsonStringLiteral()}}"""
                    else -> item.argumentsJson
                }
                val existingResult = item.resultJson
                val nextResult = when {
                    resultCount != null ->
                        """{"provider":${provider.jsonStringLiteral()},"resultCount":$resultCount}"""
                    existingResult == null ||
                        existingResult == PUBLIC_TOOL_RESULT_PLACEHOLDER ||
                        existingResult.isRedactedPublicJson() ->
                        """{"provider":${provider.jsonStringLiteral()}}"""
                    else -> existingResult
                }
                items[index] = item.copy(
                    argumentsJson = nextArgs,
                    resultJson = nextResult,
                    status = if (resultCount != null || item.status == DebugToolCallStatus.DONE) {
                        DebugToolCallStatus.DONE
                    } else {
                        item.status
                    },
                    finishedAt = item.finishedAt ?: if (resultCount != null) nowEpochMillis() else null,
                )
                return
            }
        }
        val id = nextItemId++
        items += DebugConversationItem.ToolCallItem(
            id = id,
            toolCallId = "search-${id}",
            toolName = WEB_SEARCH_TOOL_NAME,
            // Empty query keeps args replaceable so a later SearchActivity can fill it in.
            argumentsJson = if (query.isNotBlank()) {
                """{"query":${query.jsonStringLiteral()}}"""
            } else {
                null
            },
            resultJson = resultCount?.let {
                """{"provider":${provider.jsonStringLiteral()},"resultCount":$it}"""
            },
            status = if (resultCount != null) DebugToolCallStatus.DONE else DebugToolCallStatus.RUNNING,
            startedAt = nowEpochMillis(),
            finishedAt = if (resultCount != null) nowEpochMillis() else null,
            collapsed = true,
        )
        trimItems()
    }

    fun markPendingToolCallsUnknown() {
        pendingToolCalls.values.flatten().forEach { id ->
            val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
            if (index >= 0) {
                val item = items[index] as DebugConversationItem.ToolCallItem
                if (item.status == DebugToolCallStatus.RUNNING) {
                    items[index] = item.copy(
                        status = DebugToolCallStatus.UNKNOWN,
                        finishedAt = nowEpochMillis(),
                    )
                }
            }
        }
        pendingToolCalls.clear()
    }

    fun appendMessage(role: DebugTranscriptRole, content: String, collapsed: Boolean = false) {
        items += DebugConversationItem.MessageItem(
            id = nextItemId++,
            role = role,
            content = content.trimForDisplay(),
            collapsed = collapsed,
        )
        trimItems()
    }

    fun appendStatus(message: String) {
        // Status is ambient noise in the stream; keep collapsed so Debug stays scannable.
        appendMessage(DebugTranscriptRole.STATUS, message, collapsed = true)
    }

    fun appendError(title: String, message: String) {
        appendMessage(DebugTranscriptRole.ERROR, "$title: $message")
    }

    fun appendRuntime(title: String, body: String, markdown: Boolean = false) {
        items += DebugConversationItem.RuntimePhaseItem(
            id = nextItemId++,
            title = title,
            body = body.trimForDisplay(),
            collapsed = true,
            markdown = markdown,
        )
        trimItems()
    }

    fun appendRuntimeResult(resultStatus: String, response: String) {
        appendRuntime(
            title = "Runtime result $resultStatus",
            body = decorateRuntimeResultBody(response, currentToolIssues()),
            markdown = true,
        )
    }

    fun appendDiagnostics(entry: DebugDiagnosticEntry) {
        appendMessage(
            role = DebugTranscriptRole.RUNTIME,
            content = "${entry.title}\n${entry.body}",
            collapsed = true,
        )
    }

    private fun appendDelta(
        text: String,
        role: DebugTranscriptRole,
        getId: () -> Long?,
        setId: (Long?) -> Unit,
    ) {
        if (text.isBlank()) return
        val id = getId()
        val index = items.indexOfLast { it.id == id && it is DebugConversationItem.MessageItem }
        if (index >= 0) {
            val item = items[index] as DebugConversationItem.MessageItem
            items[index] = item.copy(content = (item.content + text).trimForDisplay())
            return
        }
        val item = DebugConversationItem.MessageItem(
            id = nextItemId++,
            role = role,
            content = text.trimForDisplay(),
            collapsed = role == DebugTranscriptRole.THINKING,
        )
        items += item
        setId(item.id)
        trimItems()
    }

    private fun recordToolCallStarted(toolCallId: String, toolName: String, argumentsBody: String) {
        if (updatePublicToolPlaceholder(toolCallId, toolName, argumentsBody)) return
        val id = nextItemId++
        rememberToolCallId(toolCallId, id)
        pendingToolCalls.getOrPut(toolName) { ArrayDeque() }.addLast(id)
        items += DebugConversationItem.ToolCallItem(
            id = id,
            toolCallId = toolCallId,
            toolName = toolName,
            argumentsJson = argumentsBody.trimForDisplay(),
            resultJson = null,
            status = DebugToolCallStatus.RUNNING,
            startedAt = nowEpochMillis(),
        )
        trimItems()
    }

    private fun hasPendingRawToolCall(toolCallId: String, toolName: String): Boolean {
        val id = itemIdForToolCall(toolCallId)
            ?: pendingToolCalls[toolName]?.lastOrNull()
            ?: return false
        val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
        if (index < 0) return false
        val item = items[index] as DebugConversationItem.ToolCallItem
        if (item.resultJson != null || item.argumentsJson == PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER) return false
        rememberToolCallId(toolCallId, id)
        items[index] = item.copy(toolCallId = item.toolCallId.ifBlank { toolCallId })
        return true
    }

    private fun updatePublicToolPlaceholder(toolCallId: String, toolName: String, argumentsBody: String): Boolean {
        val id = itemIdForToolCall(toolCallId)
            ?: pendingToolCalls[toolName]?.lastOrNull()
            ?: return false
        val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
        if (index < 0) return false
        val item = items[index] as DebugConversationItem.ToolCallItem
        if (item.argumentsJson != PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER) return false
        rememberToolCallId(toolCallId, id)
        items[index] = item.copy(
            toolCallId = item.toolCallId.ifBlank { toolCallId },
            argumentsJson = argumentsBody.trimForDisplay(),
        )
        return true
    }

    private fun recordToolCallFinished(toolCallId: String, toolName: String, resultBody: String): Long? {
        val id = itemIdForToolCall(toolCallId) ?: pendingToolCalls[toolName]?.removeLastOrNull() ?: return null
        removePendingToolItem(toolName, id)
        val index = items.indexOfFirst { it.id == id && it is DebugConversationItem.ToolCallItem }
        if (index < 0) return null
        val item = items[index] as DebugConversationItem.ToolCallItem
        items[index] = item.copy(
            resultJson = resultBody.trimForDisplay(),
            status = parseDebugToolResultStatus(resultBody),
            finishedAt = nowEpochMillis(),
        )
        return id
    }

    private fun itemIdForToolCall(toolCallId: String): Long? =
        toolCallId.takeIf { it.isNotBlank() }?.let(toolItemsByCallId::get)

    private fun rememberToolCallId(toolCallId: String, itemId: Long) {
        if (toolCallId.isNotBlank()) toolItemsByCallId[toolCallId] = itemId
    }

    private fun removePendingToolItem(toolName: String, itemId: Long) {
        val stack = pendingToolCalls[toolName] ?: return
        val remaining = stack.filterNot { it == itemId }
        stack.clear()
        stack.addAll(remaining)
        if (stack.isEmpty()) pendingToolCalls.remove(toolName)
    }

    private fun currentToolIssues(): DebugToolIssueSummary {
        var failed = 0
        var unknown = 0
        items.asSequence()
            .filterIsInstance<DebugConversationItem.ToolCallItem>()
            .filter { it.id > currentTurnId }
            .filterNot { it.toolName.isInternalDebugToolName() }
            .forEach { item ->
                when (item.status) {
                    DebugToolCallStatus.FAILED -> failed++
                    DebugToolCallStatus.UNKNOWN,
                    DebugToolCallStatus.RUNNING -> unknown++
                    DebugToolCallStatus.DONE -> Unit
                }
            }
        return DebugToolIssueSummary(failed = failed, unknown = unknown)
    }

    private fun trimItems() {
        while (items.size > MAX_TRANSCRIPT_ITEMS) {
            items.removeAt(0)
        }
    }

    private fun nowEpochMillis(): Long = System.currentTimeMillis()
}

private fun String.jsonStringLiteral(): String =
    buildString {
        append('"')
        for (ch in this@jsonStringLiteral) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

private const val MAX_TRANSCRIPT_ITEMS = 300
private const val TURN_PREVIEW_CHARS = 60
private const val WEB_SEARCH_TOOL_NAME = "web_search"
private const val PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER = "Public NativeToolCallStarted observed; raw arguments pending."
private const val PUBLIC_TOOL_RESULT_PLACEHOLDER = "Public NativeToolCallFinished observed; raw result pending."
private const val REDACTED_PUBLIC_JSON = """{"redacted":true}"""
private const val REDACTED_SENSITIVE_PUBLIC_JSON = """{"redacted":true,"sensitive":true}"""

private fun String?.isReplaceableWebSearchArgs(): Boolean {
    if (this == null || isBlank()) return true
    if (this == PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER || this == "{}") return true
    if (isRedactedPublicJson()) return true
    // Provider-only placeholder from an earlier SearchActivity without query.
    return !contains("\"query\"")
}

private fun String.isRedactedPublicJson(): Boolean {
    val trimmed = trim()
    return trimmed == REDACTED_PUBLIC_JSON || trimmed == REDACTED_SENSITIVE_PUBLIC_JSON
}

/** Best-effort query from public/raw tool argument payloads for web_search cards. */
private fun extractDisplayWebSearchQuery(arguments: String): String {
    val raw = arguments.trim()
    if (raw.isBlank() || raw.isRedactedPublicJson() || raw == PUBLIC_TOOL_ARGUMENTS_PLACEHOLDER) return ""
    val json = raw.substringAfter("arguments=", raw).trim()
    if (!json.startsWith("{")) return ""
    return runCatching {
        val obj = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching ""
        obj["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifBlank { obj["q"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
    }.getOrDefault("")
}
