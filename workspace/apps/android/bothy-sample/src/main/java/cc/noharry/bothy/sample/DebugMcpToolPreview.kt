package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.device.DeviceCalendarMcpProvider
import cc.noharry.bothy.device.DeviceNotificationMcpProvider
import cc.noharry.bothy.kernel.api.ToolDescriptor
import kotlinx.coroutines.withTimeoutOrNull

/** One MCP tool entry shown in the provider preview list. */
internal data class DebugMcpToolPreview(
    val name: String,
    val title: String,
    val description: String,
)

internal sealed interface DebugMcpToolsResult {
    data class Loaded(val tools: List<DebugMcpToolPreview>) : DebugMcpToolsResult
    data class Unavailable(val reason: String) : DebugMcpToolsResult
}

/**
 * Loads the tool catalog a provider exposes, for the "what's loaded" preview in MCP settings. A configured server
 * (a standard MCP server over HTTP) goes through a live tools/list on the app's connection to
 * it; the others answer locally.
 */
internal suspend fun loadMcpProviderTools(context: Context, provider: DebugMcpProviderState): DebugMcpToolsResult {
    provider.server?.let { return loadServerTools(context.applicationContext, it) }
    return when (provider.id) {
        DEBUG_MCP_PROVIDER_TOOLS_JSON -> {
            val catalog = loadDebugMcpCatalog(context.applicationContext)
            if (catalog == null) {
                DebugMcpToolsResult.Unavailable("mcp/tools.json not found in assets")
            } else {
                DebugMcpToolsResult.Loaded(
                    catalog.tools.map { tool ->
                        DebugMcpToolPreview(
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
        else -> DebugMcpToolsResult.Unavailable("Unknown provider: ${provider.id}")
    }
}

private suspend fun loadServerTools(context: Context, server: DebugMcpServer): DebugMcpToolsResult {
    return withTimeoutOrNull(SERVER_PREVIEW_TIMEOUT_MS) {
        val client: McpRpcClient = McpHttpClients.get(context, server)
        val connect = client.connect()
        if (!connect.ok) {
            McpHttpClients.close(server.id)
            return@withTimeoutOrNull DebugMcpToolsResult.Unavailable(connect.errorMessage ?: "${server.label} 连不上")
        }
        val listing = listMcpTools(client)
        listing.problem?.let { DebugMcpToolsResult.Unavailable(it) } ?: listing.tools.toPreviewResult()
    } ?: DebugMcpToolsResult.Unavailable("${server.label} tools/list 超时")
}

private fun List<ToolDescriptor>.toPreviewResult(): DebugMcpToolsResult =
    DebugMcpToolsResult.Loaded(
        map { descriptor ->
            DebugMcpToolPreview(
                name = descriptor.name,
                title = descriptor.title ?: descriptor.name,
                description = descriptor.description,
            )
        },
    )

private const val SERVER_PREVIEW_TIMEOUT_MS = 60_000L
