package cc.noharry.agent.appserver.plans
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.protocol.PlanWatch
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.protocol.view.valueText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The value at [path] in [data], through nested objects and lists (「poi.name」, 「itineraries.0.price」); null when it
 * is not there or not a value.
 */
@InternalAppServerApi
fun watchReading(data: JsonObject, path: String): JsonPrimitive? {
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

/** Whether [data] is a reading this watch looks at: every [PlanWatch.where] field has its value. */
@InternalAppServerApi
fun PlanWatch.concerns(data: JsonObject): Boolean =
    where.all { (path, expected) -> watchReading(data, path)?.let { sameValue(it, expected) } == true }

/** The field as a number; null when missing, not a number, or a server's 「invalid」 (such as -1000). */
@InternalAppServerApi
fun PlanWatch.number(data: JsonObject): Double? = watchReading(data, field)?.doubleOrNull?.takeIf { it > INVALID_READING }

/** The field in words, for 「changes」 and for the history: 「true」「2」「回龙观」. */
@InternalAppServerApi
fun PlanWatch.reading(data: JsonObject): String? = watchReading(data, field)?.let(::valueText)

/** Whether the field is what [PlanWatch.op] asks for (equals or not); numbers are the tracker's. */
@InternalAppServerApi
fun PlanWatch.matches(data: JsonObject): Boolean {
    val current = watchReading(data, field) ?: return false
    val expected = value ?: return false
    return when (op) {
        WatchOp.EQUALS -> sameValue(current, expected)
        WatchOp.NOT_EQUALS -> !sameValue(current, expected)
        WatchOp.CONTAINS -> valueText(current).contains(valueText(expected), ignoreCase = true)
        else -> false
    }
}

/** 「2」 and 2.0 and "2" are the same value; so are true and "true". */
internal fun sameValue(a: JsonPrimitive, b: JsonPrimitive): Boolean = valueText(a) == valueText(b)

@InternalAppServerApi
fun valueText(value: JsonPrimitive): String {
    value.booleanOrNull?.let { return it.toString() }
    val number = if (value.isString) null else value.doubleOrNull
    if (number != null) return if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    return value.contentOrNull.orEmpty().trim()
}

/** Read a resource again this often. */

/** Call a watched tool again this often: it is usually someone else's service, often a public one. */
internal const val DEFAULT_TOOL_WATCH_POLL_MS = 1_800_000L

/** And never more often than this. */
internal const val MIN_TOOL_WATCH_POLL_MS = 300_000L
private const val INVALID_READING = -999.0
