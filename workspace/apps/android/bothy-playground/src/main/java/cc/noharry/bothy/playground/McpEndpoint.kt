package cc.noharry.bothy.playground

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A resource a server offers: the state as JSON, for a client to read or a plan to watch. */
class McpResource(val uri: String, val name: String, val description: String, val read: (World) -> JsonObject)

/**
 * One MCP server over JSON-RPC: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`, `resources/read`.
 * Tool calls change [store]; a refused call answers as a tool error (`isError`), an unknown tool or method as a
 * JSON-RPC error. Notifications get no answer.
 */
class McpEndpoint(
    val id: String,
    private val title: String,
    private val instructions: String,
    private val tools: List<McpTool>,
    private val resources: List<McpResource>,
    private val store: PlaygroundStore,
) {
    /** The answer to [message], or null for a notification. */
    fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]?.takeUnless { it is JsonNull } ?: return null
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_REQUEST, "缺少 method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools.map { it.describe() })) })
                "tools/call" -> call(id, params)
                "resources/list" -> result(id, buildJsonObject { put("resources", JsonArray(resources.map { it.describe() })) })
                "resources/templates/list" -> result(id, buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) })
                "resources/read" -> read(id, params)
                "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                else -> error(id, METHOD_NOT_FOUND, "不支持 $method")
            }
        } catch (bad: IllegalArgumentException) {
            error(id, INVALID_PARAMS, bad.message ?: "参数不对")
        }
    }

    private fun initialize(params: JsonObject): JsonObject = buildJsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        put("protocolVersion", asked?.takeIf { it in SUPPORTED_VERSIONS } ?: SUPPORTED_VERSIONS.first())
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
            putJsonObject("resources") {
                put("subscribe", false)
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", "bothy-playground-$id")
            put("title", title)
            put("version", SERVER_VERSION)
        }
        put("instructions", instructions)
    }

    private fun call(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_PARAMS, "缺少 name")
        val tool = tools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "没有工具 $name")
        val args = ToolArgs(params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        val outcome = if (tool.readOnly || tool.direct) {
            try {
                tool.run(store.read(), args).also { if (tool.direct && !it.isError) store.record(this.id, tool.name, it.text, it.highlights) }
            } catch (refused: ToolRefusal) {
                ToolOutcome(store.read(), refused.message.orEmpty(), isError = true)
            } catch (failed: Exception) {
                // A map or network failure answers as a tool error; it must never take the server down.
                ToolOutcome(store.read(), "出错了：${failed.message ?: failed.javaClass.simpleName}", isError = true)
            }
        } else {
            store.change(this.id, tool.name) { world ->
                try {
                    tool.run(world, args)
                } catch (refused: ToolRefusal) {
                    ToolOutcome(world, refused.message.orEmpty(), isError = true)
                }
            }
        }
        val structured = if (outcome.isError) null else resources.firstOrNull()?.read?.invoke(outcome.world)
        return result(id, toolResult(outcome, structured))
    }

    private fun read(id: JsonElement, params: JsonObject): JsonObject {
        val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_PARAMS, "缺少 uri")
        val resource = resources.firstOrNull { it.uri == uri } ?: return error(id, RESOURCE_NOT_FOUND, "没有资源 $uri")
        return result(id, buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("uri", uri)
                    put("mimeType", "application/json")
                    put("text", resource.read(store.read()).toString())
                })
            })
        })
    }

    private fun McpTool.describe(): JsonObject = buildJsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("inputSchema", inputSchema)
        putJsonObject("annotations") {
            put("title", title)
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
            put("idempotentHint", true)
            put("openWorldHint", false)
        }
    }

    private fun McpResource.describe(): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("description", description)
        put("mimeType", "application/json")
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    companion object {
        const val SERVER_VERSION = "0.1.0"
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val RESOURCE_NOT_FOUND = -32002

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }
}

/** The two servers the playground runs, by path: `/cabin/mcp` (with the car\'s navigation, [nav]) and `/home/mcp`. */
fun playgroundEndpoints(
    store: PlaygroundStore,
    nav: NavController = NavController(store, UnavailableNavEngine("这个 Playground 没有导航")),
): Map<String, McpEndpoint> = listOf(
    McpEndpoint(
        id = "cabin",
        title = "Bothy Playground 虚拟座舱",
        instructions = "这是一辆虚构的车，只在 Bothy Playground 里显示，不连接任何真车。调之前不知道现状就先 get_cabin_status。" +
            "用户要么在车上要么在家（状态里的 user_here）；「我这边」「我这里」「有点热」这类话按他在的地方去调。" +
            "座椅、车窗用 driver/passenger/rear_left/rear_right；用户说「我」「我这边」时 seat 填 speaker（正在说话的人坐的位置），" +
            "「乘客」填 passengers，「车上的人」填 occupied。导航是真实的高德导航（可能是模拟行驶）：地点可以直接给名字，" +
            "多个地点一次 start_navigation 算好；开着导航改途经点用 add_waypoint / remove_waypoint / replace_waypoint / reorder_waypoints，" +
            "路上找吃饭、加油、充电用 search_along_route；想在某个读数到了时自动做事，可以监听资源 playground://cabin/state。",
        tools = CABIN_TOOLS + navTools(nav),
        resources = listOf(McpResource("playground://cabin/state", "虚拟座舱状态", "车内状态 JSON，字段：$CABIN_FIELDS") { it.cabin.toJson(userHere = it.userAt == UserPlace.CABIN) }),
        store = store,
    ),
    McpEndpoint(
        id = "home",
        title = "Bothy Playground 虚拟小家",
        instructions = "这是一个虚构的小家（客厅、卧室、厨房、书房），只在 Bothy Playground 里显示，不连接任何真实家电。" +
            "调之前不知道现状就先 get_home_status；用户要么在家要么在车上（状态里的 user_here），「我这边」按他在的地方理解；" +
            "开门锁前先向用户确认；想在某个读数到了时自动做事，可以监听资源 playground://home/state。",
        tools = HOME_TOOLS,
        resources = listOf(McpResource("playground://home/state", "虚拟小家状态", "家里状态 JSON，字段：$HOME_FIELDS") { it.home.toJson(userHere = it.userAt == UserPlace.HOME) }),
        store = store,
    ),
).associateBy { "/${it.id}/mcp" }
