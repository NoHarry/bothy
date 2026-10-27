package cc.noharry.bothy.app.tools
import android.content.Context
import cc.noharry.agent.device.DeviceCalendarMcpProvider
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.agent.device.DeviceNotificationMcpProvider
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.McpToolRef
import cc.noharry.agent.kernel.core.STATIC_PROMPT_SOFT_BUDGET_BYTES
import cc.noharry.agent.kernel.core.ToolDomainIndexer
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.evidence.CheckResult
import cc.noharry.bothy.app.evidence.CheckState
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_DEVICE_CALENDAR
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_DEVICE_MEDIA
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.bothy.app.mcp.McpProviderState
import cc.noharry.bothy.app.mcp.McpToolCatalogs
import cc.noharry.bothy.app.mcp.ToolsJsonMcpProvider
import cc.noharry.bothy.app.mcp.loadMcpProviders
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.plans.describe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 检查 → 工具目录 (owner 2026-10-13): each switched-on MCP source with its tool count, the groups the prompt lists them
 * in (one line per `command_<domain>_` prefix or MCP server), and the size of the last prompt this app sent. A car's
 * prompt grows with those groups: one device's grew past the old hard limit, and its upload could not say from what.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal suspend fun checkToolCatalog(context: Context): CheckResult {
    val sources = loadMcpProviders(context).filter { it.enabled }.map { it.label to catalogSource(context, it) }
    val refs = ToolProviderRegistry(sources.mapNotNull { it.second.provider }).allTools(MaterializerContext())
    val groups = ToolDomainIndexer.index(refs).domains
    val listed = groups.take(MAX_LISTED_GROUPS).joinToString("、") { "${it.id} ${it.toolCount}" }
    val detail = listOfNotNull(
        sources.joinToString("、") { (label, source) -> "$label ${source.describe(refs)}" }.ifEmpty { "没有打开的工具来源" },
        groups.takeIf { it.isNotEmpty() }?.let { "分 ${it.size} 组：$listed${if (it.size > MAX_LISTED_GROUPS) " 等" else ""}" },
        LastPrompt.stats?.describe() ?: "还没有对话过，固定提示大小未知",
    ).joinToString(" · ")
    val state = when {
        sources.any { it.second.failed } -> CheckState.WARN
        LastPrompt.stats?.let { it.overBudget || it.shortened } == true -> CheckState.WARN
        sources.isEmpty() -> CheckState.OFF
        else -> CheckState.OK
    }
    return CheckResult(state, detail)
}

/** One MCP source as the check sees it: the tools to count, or why there are none. */
private class CatalogSource(val provider: ToolProvider? = null, val note: String? = null, val failed: Boolean = false) {
    fun describe(refs: List<McpToolRef>): String =
        note ?: "${refs.count { it.provider === provider }} 个"
}

private suspend fun catalogSource(context: Context, provider: McpProviderState): CatalogSource {
    provider.server?.let { server ->
        val tools = McpToolCatalogs.tools(context, server)
        return if (tools.isEmpty()) CatalogSource(note = "连不上或没有工具", failed = true) else CatalogSource(CatalogSnapshot(server.id, tools))
    }
    return when (provider.id) {
        DEBUG_MCP_PROVIDER_TOOLS_JSON -> CatalogSource(ToolsJsonMcpProvider.fromAssets(context))
        DEBUG_MCP_PROVIDER_DEVICE_CALENDAR -> CatalogSource(DeviceCalendarMcpProvider(context))
        DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION -> CatalogSource(DeviceNotificationMcpProvider(context))
        DEBUG_MCP_PROVIDER_DEVICE_MEDIA -> CatalogSource(DeviceMediaMcpProvider(context))
        else -> CatalogSource(note = "未知来源", failed = true)
    }
}

/** A server's catalog already fetched (the app's cache), listed under its server id so it groups as it does in a run. */
private class CatalogSnapshot(override val serverId: String, private val tools: List<ToolDescriptor>) : McpClientProvider {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = tools

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        ToolCallResult(success = false, resultJson = "{}")
}

private const val MAX_LISTED_GROUPS = 8

/** The system prompt of the last chat request this app sent, measured where the request is logged. */
internal object LastPrompt {
    @Volatile
    var stats: PromptStats? = null
        private set

    fun record(requestBody: String) {
        if (PROTOCOL_HEADER !in requestBody) return
        systemPromptOf(requestBody)?.let(PromptStats::of)?.let { stats = it }
    }
}

/** The prompt before the host instructions: its size, and the tool section's size and group lines. */
internal data class PromptStats(val staticBytes: Int, val toolBytes: Int, val toolGroups: Int, val shortened: Boolean) {
    val overBudget: Boolean get() = staticBytes > STATIC_PROMPT_SOFT_BUDGET_BYTES

    fun describe(): String =
        "上次对话固定提示 $staticBytes/$STATIC_PROMPT_SOFT_BUDGET_BYTES 字节（工具 $toolBytes 字节、$toolGroups 组" +
            "${if (shortened) "，已自动缩短" else ""}）"

    companion object {
        fun of(system: String): PromptStats? {
            val staticPart = system.substringBefore(HOST_MARKER)
            if (!staticPart.startsWith(PROTOCOL_HEADER)) return null
            val tools = staticPart.split("\n\n").firstOrNull { it.startsWith(TOOL_SECTION_HEADER) }.orEmpty()
            val groups = tools.lines().filter { it.startsWith("- ") && !it.startsWith("- and ") }
            return PromptStats(
                staticBytes = staticPart.toByteArray(Charsets.UTF_8).size,
                toolBytes = tools.toByteArray(Charsets.UTF_8).size,
                toolGroups = groups.size,
                // The full index gives each group its summary after "): "; the shortened one stops at the tool count.
                shortened = groups.isNotEmpty() && groups.none { "): " in it },
            )
        }
    }
}

/** A chat request's system prompt: Anthropic's `system` (text or blocks), OpenAI's `instructions` or system message. */
internal fun systemPromptOf(requestBody: String): String? {
    val root = try {
        Json.parseToJsonElement(requestBody) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val message = (root["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
    return (root["system"] ?: root["instructions"] ?: message?.get("content"))?.text()
}

private fun JsonElement.text(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
    else -> null
}

private const val PROTOCOL_HEADER = "Model-driven harness protocol"
private const val TOOL_SECTION_HEADER = "Tool domains"
private const val HOST_MARKER = "\n\nHost instructions:\n"
