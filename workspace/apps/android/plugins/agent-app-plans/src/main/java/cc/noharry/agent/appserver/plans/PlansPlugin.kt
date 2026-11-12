package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext

/**
 * Timed and watched plans, for a host that lists it among its plugins: the `plan_manage` tool (the main agent's alone;
 * making, changing or deleting a plan asks in the asking modes, listing does not) with the host's words on it
 * ([PluginContext.describeTool]), today's date and the plans going before every round, the plans page's requests
 * (`plans/setEnabled`, `plans/delete`, `plans/create`) and sections, the book in `files/plans/plans.json`, the alarm
 * for the next due plan ([PlanAlarmReceiver], in this module's manifest), the watches on MCP servers ([PlanWatcher]), and
 * the app's start after a reboot or an update, with the plans checked again when the clock or the zone is set. A plan
 * coming due chimes in the host's voice, shows in the popup of the assistant on screen, and is spoken as written or run
 * as a turn ([PluginContext.deliver]); its actions run unasked within `automation-policy.json`, except in "deny all".
 * While it runs it offers [ServerFeatures.PLANS].
 *
 * Turned off, the alarm and the watches are let go and its sections emptied; the book stays and sets them again when
 * it is turned on. A plan that came due before keeps its delivery, and actions already running finish and are recorded.
 */
@ExperimentalAppServerApi
class PlansPlugin : AppServerPlugin {
    override val id: String = "plans"

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        PlanRunner.start(ctx)
        ctx.onStop { PlanRunner.stop(app) }
        ctx.offer(ServerFeatures.PLANS)
        ctx.routes(planRoutes(app))
        ctx.ownToolServer(PLAN_TOOL_SERVER, mainAgentOnly = true) { _, arguments -> planCallActs(arguments) }
        // As before it was a plugin: every run, a subagent's too, mounts it (the subagent's calls are refused), unwrapped.
        ctx.runTools { facts -> listOf(PlanToolProvider(PlanHost(facts.context.applicationContext, ctx::describeTool) { PlanRunner.running })) }
        ctx.contextProviders { facts -> listOf(PlanContext(facts.context)) }
        ctx.startOnBoot { PlanRunner.init(app) }
        ctx.publish {
            follow(PlanRunner.plans) { copy(plans = it) }
            follow(PlanRunner.history) { copy(planHistory = it) }
            follow(PlanWatcher.states) { copy(planFeeds = it) }
            // Turned off, the plans leave the screens with it.
            ctx.onStop { update { copy(plans = emptyList(), planHistory = emptyList(), planFeeds = emptyMap()) } }
        }
    }
}
