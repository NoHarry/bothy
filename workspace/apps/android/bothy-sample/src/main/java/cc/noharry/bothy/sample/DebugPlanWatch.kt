package cc.noharry.bothy.sample

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** How a watch compares its field: a number above or below a value, equal or not to a value, containing words, or any change. */
internal enum class DebugWatchOp(val id: String, val words: String) {
    ABOVE("above", "超过"),
    BELOW("below", "低于"),
    EQUALS("equals", "是"),
    NOT_EQUALS("not_equals", "不是"),
    CONTAINS("contains", "包含"),
    CHANGES("changes", "变化"),
    ;

    val numeric: Boolean get() = this == ABOVE || this == BELOW

    companion object {
        fun byId(id: String?): DebugWatchOp? = entries.firstOrNull { it.id == id }
    }
}

/** When a matching reading fires: on entering the match (a state something changes into), or on each one (an event). */
internal enum class DebugWatchMode(val id: String) {
    ENTER("enter"),
    EACH("each"),
    ;

    companion object {
        fun byId(id: String?): DebugWatchMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a plan waits for, on whatever MCP servers are loaded (not a list built into the app): [server]'s resource [uri],
 * or, for a server that offers tools only, its read-only [tool] called with [arguments] (an open flight search: 「深圳飞
 * 成都 11 月 7 日的最低价」). Its [field] (a dot path in the reading, 「itineraries.0.price」「current.condition」) is
 * compared by [op] with [value], only in readings whose [where] fields match (one item of several). The model finds
 * resources with plan_manage `signals`, tools in the server's catalog, and reads either once with `inspect`; [text] is
 * its words for the rule (「最低价低于 800 元时」). [margin] is how far back past a threshold a number must go before a
 * repeating plan may fire again.
 */
internal data class DebugPlanWatch(
    val server: String,
    val uri: String,
    val field: String,
    val op: DebugWatchOp,
    val value: JsonPrimitive?,
    val text: String,
    val where: Map<String, JsonPrimitive> = emptyMap(),
    val mode: DebugWatchMode = DebugWatchMode.ENTER,
    val margin: Double = 0.0,
    val pollMillis: Long = DEFAULT_WATCH_POLL_MS,
    /** A read-only tool read instead of a resource; [uri] is then empty. */
    val tool: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
) {
    /** What the watch reads, as one key: the resource, or the tool with its arguments. */
    val source: String get() = tool?.let { "tool:$it $arguments" } ?: uri

    /** The source in words for people and logs: the uri, or the tool's name. */
    val sourceLabel: String get() = tool ?: uri
}

/**
 * The value at [path] in [data], through nested objects and lists (「poi.name」, 「itineraries.0.price」); null when it
 * is not there or not a value.
 */
internal fun watchReading(data: JsonObject, path: String): JsonPrimitive? {
    var node: Any? = data
    path.split('.').forEach { key ->
        node = when (val current = node) {
            is JsonObject -> current[key]
            is JsonArray -> key.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }
    return node as? JsonPrimitive
}

/** Whether [data] is a reading this watch looks at: every [DebugPlanWatch.where] field has its value. */
internal fun DebugPlanWatch.concerns(data: JsonObject): Boolean =
    where.all { (path, expected) -> watchReading(data, path)?.let { sameValue(it, expected) } == true }

/** The field as a number; null when missing, not a number, or a server's 「invalid」 (such as -1000). */
internal fun DebugPlanWatch.number(data: JsonObject): Double? = watchReading(data, field)?.doubleOrNull?.takeIf { it > INVALID_READING }

/** The field in words, for 「changes」 and for the history: 「true」「2」「回龙观」. */
internal fun DebugPlanWatch.reading(data: JsonObject): String? = watchReading(data, field)?.let(::valueText)

/** Whether the field is what [DebugPlanWatch.op] asks for (equals or not); numbers are the tracker's. */
internal fun DebugPlanWatch.matches(data: JsonObject): Boolean {
    val current = watchReading(data, field) ?: return false
    val expected = value ?: return false
    return when (op) {
        DebugWatchOp.EQUALS -> sameValue(current, expected)
        DebugWatchOp.NOT_EQUALS -> !sameValue(current, expected)
        DebugWatchOp.CONTAINS -> valueText(current).contains(valueText(expected), ignoreCase = true)
        else -> false
    }
}

/** 「2」 and 2.0 and "2" are the same value; so are true and "true". */
internal fun sameValue(a: JsonPrimitive, b: JsonPrimitive): Boolean = valueText(a) == valueText(b)

internal fun valueText(value: JsonPrimitive): String {
    value.booleanOrNull?.let { return it.toString() }
    val number = if (value.isString) null else value.doubleOrNull
    if (number != null) return if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    return value.contentOrNull.orEmpty().trim()
}

/** Read a resource again this often. */
internal const val DEFAULT_WATCH_POLL_MS = 30_000L

/** Call a watched tool again this often: it is usually someone else's service, often a public one. */
internal const val DEFAULT_TOOL_WATCH_POLL_MS = 1_800_000L

/** And never more often than this. */
internal const val MIN_TOOL_WATCH_POLL_MS = 300_000L
private const val INVALID_READING = -999.0
