package cc.noharry.bothy.server.tools

import android.content.Context
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.bothy.server.mcp.playgroundDeviceServers
import cc.noharry.bothy.server.voice.JevPrefetchProvider
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadJevKey

/**
 * What Bothy adds to each run ([RunAddOns]): Jev picking the relevant tools before the first model call; the
 * Playground: its mode in the run's context, its 「回到车机」 tool, no demo car while it navigates for real, and its
 * virtual car and home, and 「回到车机」 itself, as devices a subagent only queries unless allowed; and its skills
 * and subagent types ([bothySkills], [BOTHY_SUBAGENT_KINDS]).
 */
internal object BothyRuns : RunAddOns {
    override fun contextLines(context: Context): List<String> = listOf(PlaygroundMode.line())

    override fun toolWrapper(context: Context, sessionContext: String, simulated: Boolean): (ToolProvider) -> ToolProvider {
        val jev = loadJevConfig(context).let { if (simulated) it.copy(enabled = false) else it }
        val key = if (jev.enabled) loadJevKey(context) else ""
        val prefetch = jev.enabled && jev.validationError() == null && key.isNotBlank()
        return { provider -> if (prefetch) JevPrefetchProvider(provider, jev, key, sessionContext) else provider }
    }

    override fun exampleToolsAllowed(): Boolean = !PlaygroundMode.real

    override fun toolProviders(context: Context): List<ToolProvider> =
        if (playgroundInstalled(context)) listOf(PlaygroundToolProvider(context)) else emptyList()

    override fun builtinSkills(context: Context): List<RuntimeSkill> = bothySkills(context)

    override fun subagentKinds(): List<SubagentKind> = BOTHY_SUBAGENT_KINDS

    // 「回到车机」 brings a Playground screen to the front over whatever the user is doing: working the screen too.
    override fun deviceServers(context: Context): Set<String> = playgroundDeviceServers(context) + PLAYGROUND_TOOL_SERVER_ID
}
