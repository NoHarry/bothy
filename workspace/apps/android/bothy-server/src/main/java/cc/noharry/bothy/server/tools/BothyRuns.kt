@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools

import android.content.Context
import cc.noharry.agent.appserver.a2ui.CardHooks
import cc.noharry.agent.appserver.plans.PLAN_TOOL
import cc.noharry.agent.appserver.runtime.AnswerProblems
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.tools.isTransaction
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.bothy.server.cards.BothyCards
import cc.noharry.bothy.server.evidence.nameRouteProblems
import cc.noharry.bothy.server.evidence.requiresCarAction
import cc.noharry.bothy.server.mcp.playgroundDeviceServers
import cc.noharry.bothy.server.voice.JevPrefetchProvider
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadJevKey

/**
 * What Bothy adds to each run ([RunAddOns]): Jev picking the relevant tools before the first model call; the
 * Playground: its mode in the run's context, its 「回到车机」 tool, no demo car while it navigates for real, and its
 * virtual car and home, and 「回到车机」 itself, as devices a subagent only queries unless allowed; its skills and
 * subagent types ([bothySkills], [BOTHY_SUBAGENT_KINDS]); its rules (the web for weather and events, 网上核实, drawing
 * long answers as pages) and gates (an order waits for the user's yes, 网上核实 only after a service's data); its trip
 * cards ([BothyCards]); and what its bench counts as done ([requiresCarAction], [nameRouteProblems]).
 */
internal object BothyRuns : RunAddOns {
    override fun contextLines(facts: RunFacts): List<String> = listOfNotNull(PlaygroundMode.line(), PAGES_RULE.takeIf { facts.drawsPages })

    override fun systemRules(facts: RunFacts): List<String> =
        listOfNotNull(WEB_SEARCH_RULE.takeIf { facts.webSearch }, WEB_VERIFY_RULE.takeIf { WEB_VERIFY_KIND in facts.subagentTypes })

    // Orders and payments wait for the user's yes, read in the user's own words, never in a quoted reply or a child's task.
    override fun runHooks(facts: RunFacts): List<RuntimeHook> = listOfNotNull(
        WebVerifyGate(facts.userInput).takeIf { WEB_VERIFY_KIND in facts.subagentTypes },
        ActionGate(facts.userInput, child = facts.child),
    )

    override fun describeTool(tool: ToolDescriptor): ToolDescriptor = when {
        isTransaction(tool.name) -> tool.copy(description = "${tool.description} $TRANSACTION_HINT")
        tool.name == PLAN_TOOL -> tool.copy(description = tool.description + PLAN_CLOSING)
        else -> tool
    }

    override fun cards(context: Context): CardHooks = BothyCards(context)

    override fun requiresAction(request: String): Boolean = requiresCarAction(request)

    override fun answerProblems(request: String, answer: String): AnswerProblems = nameRouteProblems(request, answer)

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
