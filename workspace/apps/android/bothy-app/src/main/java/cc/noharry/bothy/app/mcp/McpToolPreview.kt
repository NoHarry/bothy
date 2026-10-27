package cc.noharry.bothy.app.mcp
import android.content.Context
import cc.noharry.agent.device.DeviceCalendarMcpProvider
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.agent.device.DeviceNotificationMcpProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import kotlinx.coroutines.withTimeoutOrNull
import cc.noharry.agent.mcp.McpRpcClient
import cc.noharry.agent.mcp.listMcpTools

/** One MCP tool entry shown in the provider preview list. */
internal data class McpToolPreview(
    val name: String,
    val title: String,
    val description: String,
)

internal sealed interface McpToolsResult {
    data class Loaded(val tools: List<McpToolPreview>) : McpToolsResult
    data class Unavailable(val reason: String) : McpToolsResult
}

/**
 * Loads the tool catalog a provider exposes, for the "what's loaded" preview in MCP settings. A configured server
 * (a standard MCP server over HTTP) goes through a live tools/list on the app's connection to
 * it; the others answer locally.
 */
internal suspend fun loadMcpProviderTools(context: Context, provider: McpProviderState): McpToolsResult {
    provider.server?.let { return loadServerTools(context.applicationContext, it) }
    return when (provider.id) {
        DEBUG_MCP_PROVIDER_TOOLS_JSON -> {
            val catalog = loadMcpCatalog(context.applicationContext)
            if (catalog == null) {
                McpToolsResult.Unavailable("mcp/tools.json not found in assets")
            } else {
                McpToolsResult.Loaded(
                    catalog.tools.map { tool ->
                        McpToolPreview(
                            name = tool.name,
                            title = tool.displayName,
                            description = tool.description,
                        )
                    },
                )
            }
        }
        DEBUG_MCP_PROVIDER_DEVICE_CALENDAR ->
            DeviceCalendarMcpProvider(context.applicationContext).listTools().toPreviewResult()
        DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION ->
            DeviceNotificationMcpProvider(context.applicationContext).listTools().toPreviewResult()
        DEBUG_MCP_PROVIDER_DEVICE_MEDIA ->
            DeviceMediaMcpProvider(context.applicationContext).listTools().toPreviewResult()
        else -> McpToolsResult.Unavailable("Unknown provider: ${provider.id}")
    }
}

private suspend fun loadServerTools(context: Context, server: McpServer): McpToolsResult {
    return withTimeoutOrNull(SERVER_PREVIEW_TIMEOUT_MS) {
        val client: McpRpcClient = McpHttpClients.get(context, server)
        val connect = client.connect()
        if (!connect.ok) {
            McpHttpClients.close(server.id)
            return@withTimeoutOrNull McpToolsResult.Unavailable(connect.errorMessage ?: "${server.label} 连不上")
        }
        val listing = listMcpTools(client, ChineseMcpMessages)
        listing.problem?.let { McpToolsResult.Unavailable(it) } ?: listing.tools.toPreviewResult()
    } ?: McpToolsResult.Unavailable("${server.label} tools/list 超时")
}

private fun List<ToolDescriptor>.toPreviewResult(): McpToolsResult =
    McpToolsResult.Loaded(
        map { descriptor ->
            McpToolPreview(
                name = descriptor.name,
                title = descriptor.title ?: descriptor.name,
                description = descriptor.description,
            )
        },
    )

private const val SERVER_PREVIEW_TIMEOUT_MS = 60_000L
