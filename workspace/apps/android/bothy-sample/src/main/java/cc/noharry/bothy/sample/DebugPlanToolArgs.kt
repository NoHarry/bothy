package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.ToolDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** `when` as the model wrote it. */
internal fun planWhen(item: JsonObject): DebugPlanWhen = DebugPlanWhen(
    inMinutes = (item["in_minutes"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
    date = item.argText("date"),
    time = item.argText("time"),
    repeat = item.argText("repeat"),
    days = (item["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() },
)

/** `watch` as the model wrote it. */
internal fun planWatchAsk(item: JsonObject): DebugPlanWatchAsk = DebugPlanWatchAsk(
    server = item.argText("server"),
    uri = item.argText("uri"),
    field = item.argText("field"),
    op = item.argText("op"),
    value = item["value"] as? JsonPrimitive,
    text = item.argText("text"),
    where = (item["where"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it } }.toMap(),
    mode = item.argText("mode"),
    holdSeconds = (item["hold_seconds"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
    repeat = (item["repeat"] as? JsonPrimitive)?.booleanOrNull,
    pollSeconds = (item["poll_seconds"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
    tool = item.argText("tool"),
    arguments = item["arguments"] as? JsonObject,
)

/**
 * `actions` as plan actions, each checked against its server's catalog as loaded now ([tools]) and the policy: the
 * server is the item's `server`, else the one in the runtime's 「mcp__<server>__<tool>」 name, else the device adapter.
 * Null when `actions` is not there (an update that keeps them).
 */
internal suspend fun planActionsOf(
    args: JsonObject,
    policy: DebugAutomationPolicy,
    tools: suspend (String) -> List<ToolDescriptor>,
    serverOf: (String) -> String = { it },
): List<DebugPlanAction>? {
    val items = args["actions"] as? JsonArray ?: return null
    require(items.size <= MAX_PLAN_ACTIONS) { "一个计划最多 $MAX_PLAN_ACTIONS 个动作" }
    val catalogs = mutableMapOf<String, List<ToolDescriptor>>()
    return items.map { element ->
        val item = element as? JsonObject ?: throw IllegalArgumentException("actions 里每一项都要是对象")
        val raw = item.argText("tool")
        val server = serverOf(
            item.argText("server") ?: raw?.takeIf { it.startsWith(MCP_PREFIX) }?.removePrefix(MCP_PREFIX)?.substringBefore("__")
                ?: throw IllegalArgumentException("actions 里每一项都要写 server，或用 mcp__<server>__<tool> 形式的 tool"),
        )
        val catalog = catalogs.getOrPut(server) { tools(server) }
        resolvePlanAction(server, raw, item["arguments"] as? JsonObject, item.argText("label"), catalog, policy)
    }
}

/** `signals`: what the loaded servers offer to watch, narrowed by [query] (words in the name, description or URI). */
internal fun planSignalsJson(resources: List<DebugMcpResource>, query: String?): JsonObject {
    val words = query?.split(' ', '，', ',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val found = resources.filter { resource -> words.isEmpty() || words.any { it in resource.name || it in resource.description || it in resource.uri } }
    return buildJsonObject {
        put("ok", true)
        put("count", found.size)
        put(
            "signals",
            buildJsonArray {
                found.take(MAX_LISTED_SIGNALS).forEach { resource ->
                    add(
                        buildJsonObject {
                            put("server", resource.server)
                            put("uri", resource.uri)
                            put("name", resource.name)
                            if (resource.description.isNotBlank()) put("description", resource.description)
                        },
                    )
                }
            },
        )
        put("message", "用 inspect 读一次看字段；字段值的含义先看服务的说明或相关 skill，再建 watch。")
    }
}

/** `inspect`: the reading now (one per position when the resource answers several), so the model sees the fields a watch can compare. */
internal fun planInspectJson(server: String, uri: String, readings: List<JsonObject>?, tool: String? = null): JsonObject = buildJsonObject {
    put("ok", readings != null)
    put("server", server)
    if (tool != null) put("tool", tool) else put("uri", uri)
    if (readings == null) {
        put("message", if (tool != null) "现在调不通：服务没开、没有这个工具，或者参数不对。" else "现在读不到：服务没开、没有这个资源，或者它只在推送时才有值。")
    } else if (readings.size == 1) {
        put("reading", readings.single())
        put("message", "field 用 reading 里的字段名（嵌套用点，列表用序号：itineraries.0.price）。")
    } else {
        put("readings", JsonArray(readings))
        put(
            "message",
            "每条是一项的读数，watch 用 where 选你要的那条（如 {\"id\":1}），field 用读数里的字段名。-1000 这类值是读不到，不代表状态。",
        )
    }
}

internal fun JsonObject.argText(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

private const val MCP_PREFIX = "mcp__"
private const val MAX_LISTED_SIGNALS = 40
