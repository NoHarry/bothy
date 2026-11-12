@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2a

import android.content.Context
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aModuleState
import cc.noharry.agent.appserver.protocol.DELEGATION_TURN_LABEL
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.Delivery
import cc.noharry.agent.appserver.server.PluginContext

/**
 * The person's own agents over A2A, for a host that lists it among its plugins: their profiles (`a2a/profile/…`, the
 * `a2a` section), a bridge tool per enabled profile (`a2a_message_send`, the main agent's alone, given to the app's own
 * runs and never to a subagent's or an editor's) that goes out only when the person named the agent, where their tasks
 * stand in each run's context, the tasks it handed over followed until they settle and reported back into the
 * conversation ([PluginContext.deliver], the `delegations` section), the skills' `a2a` sections while one is connected,
 * and the labels of the turns those reports open. With [host]'s `localServer` it also runs the local endpoint
 * (`127.0.0.1:8766/a2a`) other A2A clients on the device hand tasks to, answered as turns of their own
 * ([PluginContext.outsideTurns], the person's approval mode answering a call that acts), the endpoint's token
 * (`a2a/server/token`), and client mode (`a2aClient/…`, the `a2aClient` section), which talks to that endpoint.
 *
 * Turned off: the endpoint closes and an inbound task still running is stopped (its caller's connection ends); client
 * mode stops, its running task cancelled; the profiles' clients close; delegations are no longer followed, those still
 * open kept on the device and followed again from where they are when it runs again, as after a restart; a report
 * already handed to the conversation is still delivered; its sections empty.
 */
@ExperimentalAppServerApi
class A2aPlugin(private val host: A2aHost = A2aHost.NONE) : AppServerPlugin {
    override val id: String = "a2a"

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        A2aRuntime.start(ctx, host)
        ctx.onStop(A2aRuntime::stop)
        A2aProfiles.start(app)
        ctx.onStop(A2aProfiles::close)
        if (host.localServer) {
            A2aServerManager.start(app, host, ctx.outsideTurns)
            ctx.onStop(A2aServerManager::stop)
        }
        A2aDelegations.start(app) { report -> ctx.deliver(report.toDelivery(app)) }
        ctx.onStop(A2aDelegations::stop)
        ctx.onStop(A2aClientConsole::stop)
        ctx.routes(a2aRoutes(app, host.localServer))
        ctx.ownToolServer("$A2A_SERVER_PREFIX*", mainAgentOnly = true)
        ctx.runTools { facts -> if (delegates(facts)) A2aProfiles.bridgeProviders(facts.context) else emptyList() }
        ctx.runHooks { facts -> namingGate(facts) }
        ctx.contextLines { facts -> if (delegates(facts)) A2aProfiles.bridgeProviders(facts.context).map { it.runNote() }.filter(String::isNotBlank) else emptyList() }
        ctx.skillSections { context -> if (A2aModuleConfig.hasEnabledProfiles(context)) setOf(A2A_SKILL_SECTION) else emptySet() }
        ctx.turnLabel { text -> DELEGATION_TURN_LABEL.takeIf { text.isDelegationReport() } }
        // A run started on the device takes the focus back from another client's task.
        ctx.onLocalTurn(InboundFocus::localRun)
        // Off, no other client's task holds the focus.
        ctx.onStop(InboundFocus::localRun)
        ctx.publish {
            follow(A2aProfiles.state) { copy(a2aClients = it) }
            follow(A2aDelegations.views) { copy(delegations = it) }
            val detach = A2aClientConsole.attach(app) { state -> update { copy(a2aClient = state.copy(inboundFocus = InboundFocus.external.value)) } }
            follow(InboundFocus.external) { copy(a2aClient = a2aClient.copy(inboundFocus = it)) }
            // Turned off, the profiles, client mode and delegations leave the screens with it.
            ctx.onStop {
                detach()
                update { copy(a2aClients = A2aModuleState(), a2aClient = A2aClientState(), delegations = emptyList()) }
            }
        }
    }

    /** A hand-off goes out only when the person named the agent (or answers its question), in a run that has it. */
    private fun namingGate(facts: RunFacts): List<cc.noharry.agent.spi.RuntimeHook> {
        if (!delegates(facts)) return emptyList()
        val agents = A2aProfiles.bridgeProviders(facts.context).map { it.agentLabel }
        if (agents.isEmpty()) return emptyList()
        return listOf(A2aNamingGate(facts.userInput, agents, host.nicknames, previousQuestion = facts.previousQuestion))
    }
}

/** The app's own runs hand tasks to the person's agents; a subagent's and an editor's (over ACP) never did. */
private fun delegates(facts: RunFacts): Boolean = !facts.child && !facts.editor

/** A settled delegation as the conversation takes it: a turn the agent runs, marked delivered once it got there. */
internal fun A2aReport.toDelivery(context: Context): Delivery = Delivery(
    shown = display,
    title = context.getString(R.string.a2a_delegation_report_title),
    forModel = model,
    delivered = { A2aDelegations.markDelivered(taskId) },
)

/**
 * The running A2A plugin's host ([A2aHost]) and context; while it is off nothing of it runs and client mode is off.
 * Any thread.
 */
internal object A2aRuntime {
    @Volatile
    var host: A2aHost = A2aHost.NONE
        private set

    @Volatile
    var running: Boolean = false
        private set

    fun start(ctx: PluginContext, host: A2aHost) {
        this.host = host
        running = true
        voice = { ctx.voice }
    }

    fun stop() {
        running = false
        voice = { null }
    }

    /** The host's settings alone, as a test gives them, the plugin not running. */
    internal fun useHost(host: A2aHost) {
        this.host = host
    }

    /** The host's voice, which says the remote agent's speech in client mode; null while the plugin is off. */
    @Volatile
    var voice: () -> cc.noharry.agent.appserver.runtime.VoicePort? = { null }
        private set
}

/** The key of the skills' conditional sections that hold while the person's own agent is connected (an enabled profile). */
const val A2A_SKILL_SECTION = "a2a"

/** The tool servers that hand a task to one of the person's agents, one per enabled profile. */
internal const val A2A_SERVER_PREFIX = "experimental_a2a"
