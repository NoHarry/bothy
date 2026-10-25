package cc.noharry.bothy.sample

import android.content.Context

/** One row of 设置 → 连接 → MCP: a fixed provider (Tools JSON, calendar, notifications) or a configured [server]. */
internal data class DebugMcpProviderState(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val server: DebugMcpServer? = null,
)

/** Tools JSON, then the configured standard MCP servers, then the device providers. */
internal fun loadDebugMcpProviders(context: Context): List<DebugMcpProviderState> {
    val prefs = context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
    return providerRows(loadDebugMcpServers(context)) { definition -> prefs.getBoolean(definition.prefKey, definition.defaultEnabled) }
}

internal fun defaultDebugMcpProviders(): List<DebugMcpProviderState> =
    providerRows(emptyList()) { it.defaultEnabled }

internal fun saveDebugMcpProviderEnabled(context: Context, id: String, enabled: Boolean): List<DebugMcpProviderState> {
    val definition = MCP_PROVIDER_DEFINITIONS.firstOrNull { it.id == id }
    if (definition == null) {
        setDebugMcpServerEnabled(context, id, enabled)
    } else {
        context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(definition.prefKey, enabled).apply()
    }
    return loadDebugMcpProviders(context)
}

internal fun List<DebugMcpProviderState>.enabledProviderLabels(): List<String> =
    filter { it.enabled }.map { it.label }

internal fun List<DebugMcpProviderState>.isProviderEnabled(id: String): Boolean =
    firstOrNull { it.id == id }?.enabled == true

private fun providerRows(
    servers: List<DebugMcpServer>,
    enabled: (DebugMcpProviderDefinition) -> Boolean,
): List<DebugMcpProviderState> {
    val fixed = MCP_PROVIDER_DEFINITIONS.map { DebugMcpProviderState(it.id, it.label, enabled(it)) }
    val configured = servers.map { DebugMcpProviderState(it.id, it.label, it.enabled, it) }
    return fixed.take(1) + configured + fixed.drop(1)
}

private data class DebugMcpProviderDefinition(
    val id: String,
    val label: String,
    val prefKey: String,
    val defaultEnabled: Boolean,
)

internal const val DEBUG_MCP_PROVIDER_TOOLS_JSON = "tools_json"
internal const val DEBUG_MCP_PROVIDER_DEVICE_CALENDAR = "device_calendar"
internal const val DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION = "device_notification"
internal const val DEBUG_MCP_PROVIDER_DEVICE_MEDIA = "device_media"

/** Tools JSON first; the device providers follow the configured servers. */
private val MCP_PROVIDER_DEFINITIONS = listOf(
    DebugMcpProviderDefinition(DEBUG_MCP_PROVIDER_TOOLS_JSON, "Tools JSON", "mcp_provider_tools_json", false),
    DebugMcpProviderDefinition(DEBUG_MCP_PROVIDER_DEVICE_CALENDAR, "Device Calendar", "mcp_provider_device_calendar", false),
    DebugMcpProviderDefinition(
        DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION,
        "Device Notifications",
        "mcp_provider_device_notification",
        false,
    ),
    // On from the start: media keys and the volume need no permission; notification access adds what plays and which app.
    DebugMcpProviderDefinition(DEBUG_MCP_PROVIDER_DEVICE_MEDIA, "Device Media", "mcp_provider_device_media", true),
)

internal const val MCP_PREFS_NAME = "debug_mcp_provider_config"
