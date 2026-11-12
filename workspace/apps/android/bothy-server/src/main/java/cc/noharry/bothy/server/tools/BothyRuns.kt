@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools

import android.content.Context
import cc.noharry.agent.appserver.a2ui.CardHooks
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.plans.PLAN_TOOL
import cc.noharry.agent.appserver.runtime.AnswerProblems
import cc.noharry.agent.appserver.runtime.ReplayCheck
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.tools.isTransaction
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.bothy.server.cards.BothyCards
import cc.noharry.bothy.server.evidence.nameRouteProblems
import cc.noharry.bothy.server.evidence.requiresCarAction
import cc.noharry.bothy.server.mcp.playgroundDeviceServers
import cc.noharry.bothy.server.voice.JevPrefetchProvider
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadJevKey

/**
 * What Bothy adds to each run that depends on the run ([RunAddOns]): Jev picking the relevant tools before the first
 * model call; the Playground's mode in the run's context, no demo car while it navigates for real, and its virtual car
 * and home, and 「回到车机」, as devices a subagent only queries unless allowed; its subagent types
 * ([BOTHY_SUBAGENT_KINDS]); its rules (the web for weather and events, 网上核实) and gates
 * (an order waits for the user's yes, 网上核实 only after a service's data). Its own tools ([bothyTools]) and skills
 * ([bothySkills]) are plain fields of its config, and what its bench counts as done is [BothyReplayCheck].
 */
internal object BothyRuns : RunAddOns {
    override fun loopSettings(defaults: cc.noharry.agent.runtime.LoopSettings): cc.noharry.agent.runtime.LoopSettings = defaults.copy(
        memory = cc.noharry.agent.runtime.RuntimeMemoryRetriever(topics = BOTHY_MEMORY_TOPICS),
        narration = cc.noharry.agent.spi.NarrationCheck { text -> defaults.narration.isNarration(text) || isChineseNarration(text) },
        // The cabin and home services answer in their own terms as structuredContent.status.name (NO_ROUTE and the like).
        serviceAnswer = cc.noharry.agent.runtime.McpStatusServiceAnswerCheck,
    )

    override fun protocolRules(): cc.noharry.agent.runtime.ProtocolRules = cc.noharry.agent.runtime.ProtocolRules { defaults ->
        defaults + "- For web search without a search provider, call https://www.bing.com/search?q={query}&format=rss first (XML results)."
    }

    override fun contextLines(facts: RunFacts): List<String> = listOfNotNull(PlaygroundMode.line())

    override fun systemRules(facts: RunFacts): List<String> =
        listOfNotNull(WEB_SEARCH_RULE.takeIf { facts.webSearch }, WEB_VERIFY_RULE.takeIf { WEB_VERIFY_KIND in facts.subagentTypes })

    // Orders and payments wait for the user's yes, read in the user's own words, never in a quoted reply or a child's task.
    override fun runHooks(facts: RunFacts): List<RuntimeHook> = listOfNotNull(
        WebVerifyGate(facts.userInput).takeIf { WEB_VERIFY_KIND in facts.subagentTypes },
        ActionGate(facts.userInput, child = facts.child, previousQuestion = facts.previousQuestion),
    )

    override fun describeTool(tool: ToolDescriptor): ToolDescriptor = when {
        isTransaction(tool.name) -> tool.copy(description = "${tool.description} $TRANSACTION_HINT")
        tool.name == PLAN_TOOL -> tool.copy(description = tool.description + PLAN_CLOSING)
        else -> tool
    }

    override fun toolNote(): cc.noharry.agent.appserver.runtime.ToolNote = cc.noharry.bothy.server.voice.tts.SPOKEN_NOTE

    override fun toolWrapper(context: Context, sessionContext: String, simulated: Boolean): (ToolProvider) -> ToolProvider {
        val jev = loadJevConfig(context).let { if (simulated) it.copy(enabled = false) else it }
        val key = if (jev.enabled) loadJevKey(context) else ""
        val prefetch = jev.enabled && jev.validationError() == null && key.isNotBlank()
        return { provider -> if (prefetch) JevPrefetchProvider(provider, jev, key, sessionContext) else provider }
    }

    override fun mountsBundledTools(): Boolean = !PlaygroundMode.real

    override fun subagentKinds(): List<SubagentKind> = BOTHY_SUBAGENT_KINDS

    // 「回到车机」 brings a Playground screen to the front over whatever the user is doing: working the screen too.
    // The bundled example tools are a demo car.
    override fun queryOnlyInSubagents(context: Context): Set<String> =
        playgroundDeviceServers(context) + PLAYGROUND_TOOL_SERVER_ID + DEBUG_MCP_PROVIDER_TOOLS_JSON
}

/** Bothy's own tools: the Playground's 「回到车机」 while the Playground is installed. */
internal fun bothyTools(context: Context): List<ToolProvider> =
    if (playgroundInstalled(context)) listOf(PlaygroundToolProvider(context)) else emptyList()

/** What Bothy's bench counts as done: a car's request must act ([requiresCarAction]), a route names its places ([nameRouteProblems]). */
internal object BothyReplayCheck : ReplayCheck {
    override fun requiresAction(request: String): Boolean = requiresCarAction(request)

    override fun answerProblems(request: String, answer: String): AnswerProblems = nameRouteProblems(request, answer)
}

