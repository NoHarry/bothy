package cc.noharry.agent.appserver.mcp
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.runtime.AppHost

/** Tools JSON, then the configured standard MCP servers, then the device providers. */
fun loadMcpProviders(context: Context): List<McpProviderState> {
    val prefs = context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
    return providerRows(loadMcpServers(context)) { definition -> prefs.getBoolean(definition.prefKey, definition.defaultEnabled) }
}

internal fun defaultMcpProviders(): List<McpProviderState> =
    providerRows(emptyList()) { it.defaultEnabled }

@InternalAppServerApi
fun saveMcpProviderEnabled(context: Context, id: String, enabled: Boolean): List<McpProviderState> {
    val definition = MCP_PROVIDER_DEFINITIONS.firstOrNull { it.id == id }
    if (definition == null) {
        setMcpServerEnabled(context, id, enabled)
    } else {
        context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(definition.prefKey, enabled).apply()
    }
    return loadMcpProviders(context)
}

internal fun List<McpProviderState>.enabledProviderLabels(): List<String> =
    filter { it.enabled }.map { it.label }

internal fun List<McpProviderState>.isProviderEnabled(id: String): Boolean =
    firstOrNull { it.id == id }?.enabled == true

/** The name the screen shows for a fixed provider, in the app's language; null for a configured server. */
@StringRes
internal fun fixedMcpProviderTitle(id: String): Int? = MCP_PROVIDER_DEFINITIONS.firstOrNull { it.id == id }?.title

private fun providerRows(
    servers: List<McpServer>,
    enabled: (McpProviderDefinition) -> Boolean,
): List<McpProviderState> {
    // Device Media only for a host that offers it ([BuiltInFeatures.deviceMedia]).
    val fixed = MCP_PROVIDER_DEFINITIONS.filter { it.id != DEBUG_MCP_PROVIDER_DEVICE_MEDIA || AppHost.builtIns.deviceMedia }
        .map { McpProviderState(it.id, it.label, enabled(it)) }
    val configured = servers.map { McpProviderState(it.id, it.label, it.enabled, it) }
    return fixed.take(1) + configured + fixed.drop(1)
}

private data class McpProviderDefinition(
    val id: String,
    val label: String,
    val prefKey: String,
    val defaultEnabled: Boolean,
    @StringRes val title: Int,
)

const val DEBUG_MCP_PROVIDER_TOOLS_JSON = "tools_json"
const val DEBUG_MCP_PROVIDER_DEVICE_CALENDAR = "device_calendar"
const val DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION = "device_notification"
const val DEBUG_MCP_PROVIDER_DEVICE_MEDIA = "device_media"

/** Tools JSON first; the device providers follow the configured servers. */
private val MCP_PROVIDER_DEFINITIONS = listOf(
    McpProviderDefinition(
        DEBUG_MCP_PROVIDER_TOOLS_JSON,
        "Tools JSON",
        "mcp_provider_tools_json",
        false,
        R.string.mcp_provider_tools_json,
    ),
    McpProviderDefinition(
        DEBUG_MCP_PROVIDER_DEVICE_CALENDAR,
        "Device Calendar",
        "mcp_provider_device_calendar",
        false,
        R.string.mcp_provider_device_calendar,
    ),
    McpProviderDefinition(
        DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION,
        "Device Notifications",
        "mcp_provider_device_notification",
        false,
        R.string.mcp_provider_device_notifications,
    ),
    // On from the start: media keys and the volume need no permission; notification access adds what plays and which app.
    McpProviderDefinition(
        DEBUG_MCP_PROVIDER_DEVICE_MEDIA,
        "Device Media",
        "mcp_provider_device_media",
        true,
        R.string.mcp_provider_device_media,
    ),
)

internal const val MCP_PREFS_NAME = "app_server_mcp_provider_config"
