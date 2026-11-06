package cc.noharry.agent.appserver.tools

/**
 * A tool of a service the agent called (a map, a shop, the host's devices): any MCP-style tool (`mcp__<server>__<tool>`)
 * but the card and option tools, the plan tools, operating the screen, and those that hand a task to an agent of the
 * user's. The server's memory, page and media tools count as services.
 */
fun isServiceTool(toolName: String): Boolean {
    if (!toolName.startsWith(MCP_PREFIX)) return false
    val server = toolName.removePrefix(MCP_PREFIX).substringBefore("__")
    return server !in OWN_TOOL_SERVERS && !server.startsWith(A2A_SERVER_PREFIX)
}

/** The server's own tools as their names read (`mcp__<server>__<tool>`): the card and option tools'. */
internal const val CARD_TOOL_SERVER = "app_ui"

internal const val PLAN_TOOL_SERVER = "plans"

internal const val ANDROID_USE_TOOL_SERVER = "android_use"

/** The tools that hand a task to an agent of the user's, one server per agent. */
internal const val A2A_SERVER_PREFIX = "experimental_a2a"

private val OWN_TOOL_SERVERS = setOf(CARD_TOOL_SERVER, PLAN_TOOL_SERVER, ANDROID_USE_TOOL_SERVER)

private const val MCP_PREFIX = "mcp__"
