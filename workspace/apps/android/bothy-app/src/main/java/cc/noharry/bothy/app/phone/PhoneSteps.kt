package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConversationItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tool call acted on, in a few words, for the line after its verb ("Looking things up · 账单-10月.csv"): the
 * query, path, name or command it was given, a path cut to its last part. Empty when it was given nothing worth naming;
 * the tool's own name shows only when the line is opened.
 */
internal fun ConversationItem.ToolCallItem.stepObject(): String {
    val arguments = argumentsJson.orEmpty()
    val raw = arguments.substringAfter(ARGUMENTS_PREFIX, arguments).trim()
    if (!raw.startsWith("{")) return ""
    val args = runCatching { StepJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
    val strings = args.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it.trim() } }
        .filter { it.second.isNotEmpty() }
    val named = OBJECT_KEYS.firstNotNullOfOrNull { key -> strings.firstOrNull { it.first == key } }
    val (key, value) = named ?: strings.firstOrNull { it.second.length <= OBJECT_MAX_CHARS } ?: return ""
    val text = value.lineSequence().joinToString(" ").trim()
    val shown = if (key in PATH_KEYS) text.trimEnd('/').substringAfterLast('/').ifBlank { text } else text
    return if (shown.length > OBJECT_MAX_CHARS) shown.take(OBJECT_MAX_CHARS - 1).trimEnd() + "…" else shown
}

private val StepJson = Json { ignoreUnknownKeys = true }
private const val ARGUMENTS_PREFIX = "arguments="
private const val OBJECT_MAX_CHARS = 40
private val PATH_KEYS = setOf("path", "file", "file_path", "dir")
private val OBJECT_KEYS = listOf(
    "query", "q", "pattern", "keyword", "destination", "path", "file", "file_path", "dir", "url", "name", "title", "command", "text",
)
