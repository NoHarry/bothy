package cc.noharry.bothy.server.tools
import android.content.Context
import cc.noharry.agent.appserver.a2a.describe
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_CALENDAR
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_MEDIA
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.McpToolCatalogs
import cc.noharry.agent.appserver.mcp.ToolsJsonMcpProvider
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.model.describe
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.describe
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.tools.LastPrompt
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
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.McpToolRef
import cc.noharry.agent.kernel.core.tools.ToolDomainIndexer
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.server.evidence.CheckResult
import cc.noharry.bothy.server.evidence.CheckState

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
    val shownGroups = listing(groups.take(MAX_LISTED_GROUPS).map { finding(BothyFindingCode.TOOLS_GROUP_LINE, it.id, it.toolCount) })
    val listed = if (groups.size > MAX_LISTED_GROUPS) Finding(BothyFindingCode.TOOLS_GROUPS_MORE, more = listOf(shownGroups)) else shownGroups
    val found = listOfNotNull(
        if (sources.isEmpty()) {
            finding(BothyFindingCode.TOOLS_NO_SOURCES)
        } else {
            listing(sources.map { (label, source) -> Finding(BothyFindingCode.TOOLS_SOURCE_LINE, listOf(label), listOf(source.describe(refs))) })
        },
        groups.takeIf { it.isNotEmpty() }
            ?.let { Finding(BothyFindingCode.TOOLS_GROUPS, listOf(it.size.toString()), listOf(listed), count = it.size) },
        LastPrompt.stats?.finding() ?: finding(BothyFindingCode.TOOLS_NO_PROMPT_YET),
    )
    val state = when {
        sources.any { it.second.failed } -> CheckState.WARN
        LastPrompt.stats?.let { it.overBudget || it.shortened } == true -> CheckState.WARN
        sources.isEmpty() -> CheckState.OFF
        else -> CheckState.OK
    }
    return CheckResult(state, parts(*found.toTypedArray()))
}

/** One MCP source as the check sees it: the tools to count, or why there are none. */
private class CatalogSource(val provider: ToolProvider? = null, val note: Finding? = null, val failed: Boolean = false) {
    fun describe(refs: List<McpToolRef>): Finding =
        note ?: refs.count { it.provider === provider }.let { Finding(BothyFindingCode.TOOLS_COUNT, listOf(it.toString()), count = it) }
}

private suspend fun catalogSource(context: Context, provider: McpProviderState): CatalogSource {
    provider.server?.let { server ->
        val tools = McpToolCatalogs.tools(context, server)
        return if (tools.isEmpty()) {
            CatalogSource(note = finding(BothyFindingCode.TOOLS_SOURCE_UNREACHABLE), failed = true)
        } else {
            CatalogSource(CatalogSnapshot(server.id, tools))
        }
    }
    return when (provider.id) {
        DEBUG_MCP_PROVIDER_TOOLS_JSON -> CatalogSource(ToolsJsonMcpProvider.fromAssets(context))
        DEBUG_MCP_PROVIDER_DEVICE_CALENDAR -> CatalogSource(DeviceCalendarMcpProvider(context))
        DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION -> CatalogSource(DeviceNotificationMcpProvider(context))
        DEBUG_MCP_PROVIDER_DEVICE_MEDIA -> CatalogSource(DeviceMediaMcpProvider(context))
        else -> CatalogSource(note = finding(BothyFindingCode.TOOLS_SOURCE_UNKNOWN), failed = true)
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
