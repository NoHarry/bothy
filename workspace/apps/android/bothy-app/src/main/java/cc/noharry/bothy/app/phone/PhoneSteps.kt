package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tool call acted on, in a few words, for the line after its verb ("Looking things up · 账单-10月.csv"): the
 * query, path, name or command it was given, a path cut to its last part. Empty when it was given nothing worth naming;
 * the tool's own name shows only when the line is opened.
 */
internal fun ConversationItem.ToolCallItem.stepObject(): String {
    val args = jsonObjectOf(argumentsJson) ?: return ""
    val strings = args.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it.trim() } }
        .filter { it.second.isNotEmpty() }
    val named = OBJECT_KEYS.firstNotNullOfOrNull { key -> strings.firstOrNull { it.first == key } }
    val (key, value) = named ?: strings.firstOrNull { it.second.length <= OBJECT_MAX_CHARS } ?: return ""
    val text = value.lineSequence().joinToString(" ").trim()
    val shown = if (key in PATH_KEYS) text.trimEnd('/').substringAfterLast('/').ifBlank { text } else text
    return if (shown.length > OBJECT_MAX_CHARS) shown.take(OBJECT_MAX_CHARS - 1).trimEnd() + "…" else shown
}

/**
 * The plan a call of the plan tool made or changed, by the id its result gives; null for any other call, or one that
 * did not go through.
 */
internal fun ConversationItem.ToolCallItem.madePlan(): String? {
    if (!toolName.endsWith(PLAN_TOOL) || status != ToolCallStatus.DONE) return null
    val action = (jsonObjectOf(argumentsJson)?.get("action") as? JsonPrimitive)?.content
    if (action != "create" && action != "update") return null
    return (jsonObjectOf(resultJson)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** A call's arguments or result as a JSON object; the runtime may write the arguments as `arguments={…}`. */
internal fun jsonObjectOf(text: String?): JsonObject? {
    val raw = text.orEmpty().let { it.substringAfter(ARGUMENTS_PREFIX, it) }.trim()
    if (!raw.startsWith("{")) return null
    return runCatching { StepJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private val StepJson = Json { ignoreUnknownKeys = true }
/** The plan tool's name, as the server qualifies its own tools ("mcp__plans__plan_manage"). */
private const val PLAN_TOOL = "plan_manage"
private const val ARGUMENTS_PREFIX = "arguments="
private const val OBJECT_MAX_CHARS = 40
private val PATH_KEYS = setOf("path", "file", "file_path", "dir")
private val OBJECT_KEYS = listOf(
    "query", "q", "pattern", "keyword", "destination", "path", "file", "file_path", "dir", "url", "name", "title", "command", "text",
)
