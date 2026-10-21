package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolCallResult
import cc.noharry.bothy.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.kernel.api.ToolInitializeContext
import cc.noharry.bothy.kernel.api.ToolInitializeResult
import cc.noharry.bothy.kernel.api.ToolDescriptor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * An MCP server's tools in a run, through any [McpRpcClient], such as a standard MCP server over HTTP
 * (设置 → 连接 → MCP). It speaks JSON-RPC `tools/list` and `tools/call`.
 */
internal class McpToolProvider(
    private val client: McpRpcClient,
    private val catalogCache: McpToolCatalogCache? = null,
    override val serverId: String,
    override val displayName: String,
) : McpClientProvider {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult {
        val result = client.connect()
        return if (result.ok) {
            ToolInitializeResult.connected("$displayName connected.")
        } else {
            ToolInitializeResult.failed(result.errorMessage ?: "$displayName connection failed.")
        }
    }

    override suspend fun listTools(): List<ToolDescriptor> =
        (catalogCache?.get { listMcpToolsCatalog(client) } ?: listMcpToolsCatalog(client)).map { it.withSpokenHint() }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        val arguments = parseJsonObject(argumentsJson) ?: return ToolCallResult(
            success = false,
            resultJson = errorJson(INVALID_ARGUMENTS_CODE, "Tool arguments must be a JSON object.", toolName),
        )
        val response = client.sendRequest(
            method = TOOLS_CALL_METHOD,
            params = buildToolsCallParams(toolName, arguments),
        )
        if (!response.ok) {
            return ToolCallResult(
                success = false,
                resultJson = errorJson(MCP_TRANSPORT_CODE, response.errorMessage.orEmpty(), toolName),
                retryable = true,
            )
        }
        val data = response.dataJson ?: return ToolCallResult(
            success = false,
            resultJson = errorJson(MCP_EMPTY_DATA_CODE, "MCP response did not contain JSON-RPC data.", toolName),
            retryable = true,
        )
        return ToolCallResult(
            success = !data.hasMcpToolError(),
            resultJson = data.toString(),
        )
    }

    companion object {
        /** A standard MCP server over HTTP, with the app's shared connection and catalog for it. */
        fun forHttp(context: Context, server: DebugMcpServer): McpToolProvider =
            McpToolProvider(McpHttpClients.get(context, server), McpToolCatalogs.cache(server.id), server.id, server.label)
    }
}

/** Every page of `tools/list`; empty when any page failed. */
internal suspend fun listMcpToolsCatalog(client: McpRpcClient): List<ToolDescriptor> =
    listMcpTools(client).let { if (it.problem == null) it.tools else emptyList() }

/** A server's whole tool list, or why it could not be read (shown by the tool preview in settings). */
internal class McpToolsListing(val tools: List<ToolDescriptor>, val problem: String? = null)

internal suspend fun listMcpTools(client: McpRpcClient): McpToolsListing {
    val collected = ArrayList<ToolDescriptor>()
    var cursor: String? = null
    val seenCursors = HashSet<String>()
    repeat(MAX_TOOLS_LIST_PAGES) {
        val page = fetchToolsListPage(client, cursor)
        page.problem?.let { return McpToolsListing(emptyList(), it) }
        collected += page.tools
        val nextCursor = page.nextCursor ?: return McpToolsListing(collected)
        if (!seenCursors.add(nextCursor)) return McpToolsListing(emptyList(), "tools/list 的分页游标重复了")
        cursor = nextCursor
    }
    return McpToolsListing(emptyList(), "tools/list 超过 $MAX_TOOLS_LIST_PAGES 页")
}

/** An order or payment tool, from any server, says it waits for the user's pick ([TRANSACTION_HINT]). */
internal fun ToolDescriptor.withSpokenHint(): ToolDescriptor =
    if (isTransaction(name)) copy(description = "$description $TRANSACTION_HINT") else this

private suspend fun fetchToolsListPage(
    client: McpRpcClient,
    cursor: String?,
): ToolsListPage {
    val response = client.sendRequest(TOOLS_LIST_METHOD, toolsListParams(cursor))
    if (!response.ok) return ToolsListPage(problem = response.errorMessage ?: "tools/list 失败（status=${response.statusCode}）")
    val data = response.dataJson ?: return ToolsListPage(problem = "tools/list 的回应不是 JSON")
    if (data.hasJsonRpcError()) {
        val message = ((data["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return ToolsListPage(problem = "tools/list 出错：${message ?: data["error"]}")
    }
    return ToolsListPage(
        tools = data.toolsArray().mapNotNull(::parseTool),
        nextCursor = data.nextCursor(),
    )
}

private fun toolsListParams(cursor: String?): JsonObject =
    if (cursor == null) {
        EMPTY_PARAMS
    } else {
        buildJsonObject { put(CURSOR_PARAM, JsonPrimitive(cursor)) }
    }

private fun JsonObject.nextCursor(): String? {
    val result = this["result"] as? JsonObject ?: return null
    val value = result[NEXT_CURSOR_FIELD] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.hasJsonRpcError(): Boolean = this["error"] != null && this["error"] !is JsonNull

private data class ToolsListPage(
    val tools: List<ToolDescriptor> = emptyList(),
    val nextCursor: String? = null,
    val problem: String? = null,
)

private fun parseTool(element: kotlinx.serialization.json.JsonElement): ToolDescriptor? {
    val item = element as? JsonObject ?: return null
    val name = item.stringField("name") ?: return null
    return ToolDescriptor(
        name = name,
        description = item.stringField("description").orEmpty(),
        inputSchemaJson = (item["inputSchema"] as? JsonObject)?.toString() ?: EMPTY_SCHEMA_JSON,
        title = item.stringField("title") ?: item.stringField("description") ?: name,
        // The server's own word that the tool changes nothing (MCP `readOnlyHint`); a plan may then call it on a watch.
        effectKind = if (item.readOnlyHint()) READ_ONLY_EFFECT else UNKNOWN_EFFECT,
        outputSchemaJson = (item["outputSchema"] as? JsonObject)?.toString(),
    )
}

private fun buildToolsCallParams(toolName: String, arguments: JsonObject): JsonObject =
    buildJsonObject {
        put("name", JsonPrimitive(toolName))
        put("arguments", arguments)
    }

private fun JsonObject.toolsArray(): List<kotlinx.serialization.json.JsonElement> =
    ((this["result"] as? JsonObject)?.get("tools") as? JsonArray).orEmpty()

private fun JsonObject.hasMcpToolError(): Boolean {
    if (hasJsonRpcError()) return true
    val topLevelError = (this["isError"] as? JsonPrimitive)?.booleanOrNull == true
    val nestedError = ((this["result"] as? JsonObject)?.get("isError") as? JsonPrimitive)
        ?.booleanOrNull == true
    return topLevelError || nestedError
}

private fun JsonObject.readOnlyHint(): Boolean =
    ((this["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun parseJsonObject(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun errorJson(code: String, message: String, toolName: String): String =
    """{"ok":false,"code":"${code.escapeJson()}","tool":"${toolName.escapeJson()}","message":"${message.escapeJson()}"}"""

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private const val TOOLS_LIST_METHOD = "tools/list"
/** On every order or payment tool ([isTransaction]): a model said 「先帮你下单了」 before [DebugActionGate] refused the call. */
private const val TRANSACTION_HINT =
    "这会真的下单或付款：先用预览把门店、商品、价格告诉用户，用 offer_options 给「下单」（绑定这次调用）和「先不下」，等用户选；" +
        "调用成功之前不要说已经下单。"
private const val TOOLS_CALL_METHOD = "tools/call"
private const val CURSOR_PARAM = "cursor"
private const val NEXT_CURSOR_FIELD = "nextCursor"
private const val MAX_TOOLS_LIST_PAGES = 128
private const val INVALID_ARGUMENTS_CODE = "MCP_INVALID_ARGUMENTS"
private const val MCP_TRANSPORT_CODE = "MCP_TRANSPORT_ERROR"
private const val MCP_EMPTY_DATA_CODE = "MCP_EMPTY_DATA"
private const val READ_ONLY_EFFECT = "READ_ONLY"
private const val UNKNOWN_EFFECT = "UNKNOWN"
private const val EMPTY_SCHEMA_JSON = """{"type":"object","additionalProperties":true}"""
private val EMPTY_PARAMS = JsonObject(emptyMap())
