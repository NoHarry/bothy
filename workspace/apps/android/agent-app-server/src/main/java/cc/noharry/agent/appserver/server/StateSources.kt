@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.content.Context
import android.content.SharedPreferences
import cc.noharry.agent.appserver.a2a.A2aClientConsole
import cc.noharry.agent.appserver.a2a.A2aDelegations
import cc.noharry.agent.appserver.a2a.InboundFocus
import cc.noharry.agent.appserver.a2ui.OfferStore
import cc.noharry.agent.appserver.image.PictureBoard
import cc.noharry.agent.appserver.memory.UserMemoryStore
import cc.noharry.agent.appserver.model.CONTEXT_WINDOW_PREFS
import cc.noharry.agent.appserver.model.loadContextWindow
import cc.noharry.agent.appserver.plans.PlanRunner
import cc.noharry.agent.appserver.plans.PlanWatcher
import cc.noharry.agent.appserver.protocol.ContextUsage
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.usage.UsageStore
import cc.noharry.agent.appserver.usage.contextUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Everything the server publishes, gathered on the main thread: the runtime's state ([RuntimeSession]), with client
 * mode ([A2aClientConsole]), the plans ([PlanRunner], [PlanWatcher]) and delegations ([A2aDelegations]) beside it.
 * The server lives as long as the process, so none of these is ever let go.
 */
internal class StateSources(
    private val app: Context,
    private val hub: ServerHub,
    private val features: List<AppServerFeature>,
) : PublishedState {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: ServerState? = null
    private var extras = ServerState()

    // The subagent board is the runtime's experimental API; the lanes sent are the protocol's own.
    @OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
    fun start() {
        RuntimeSession.attach(app) { state -> runtime = state; publish() }
        A2aClientConsole.attach(app) { state -> extras = extras.copy(a2aClient = state); publish() }
        follow(InboundFocus.external) { this }
        // Without plans their sections stay empty and the book is not read.
        if (AppHost.builtIns.plans) {
            follow(PlanRunner.plans) { copy(plans = it) }
            follow(PlanRunner.history) { copy(planHistory = it) }
            follow(PlanWatcher.states) { copy(planFeeds = it) }
        }
        follow(A2aDelegations.views) { copy(delegations = it) }
        follow(SessionSettings.policy) { withPreferences(app) }
        follow(AppSubagents.settings) { withPreferences(app) }
        follow(UserMemoryStore.memories) { copy(memory = memory.copy(memories = it)) }
        follow(UserMemoryStore.enabled) { copy(memory = memory.copy(enabled = it)) }
        follow(UsageStore.usage) { copy(usageRevision = usageRevision + 1) }
        follow(UsageStore.prices) { copy(usageRevision = usageRevision + 1) }
        main.launch { PreferencesSource.changes.collect { extras = extras.withPreferences(app); publish() } }
        follow(OfferStore.latestOffers) { copy(offers = it) }
        follow(PictureBoard.all) { copy(offerPictures = it) }
        follow(AppSubagents.board) { copy(subagents = it.lanes()) }
        follow(AppSubagents.deliveries) { copy(subagentDeliveries = it) }
        watchContextWindows()
        if (AppHost.builtIns.plans) CoroutineScope(Dispatchers.IO).launch { PlanRunner.book(app) }
        features.forEach { it.publish(app, this) }
    }

    override val current: ServerState get() = extras

    override fun update(change: ServerState.() -> ServerState) {
        extras = extras.change()
        publish()
    }

    override fun broadcast(notification: JsonRpcNotification) = hub.broadcast(notification)


    /** A context window set in the provider settings changes the ring at once. */
    private fun watchContextWindows() {
        val prefs = app.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(windowListener)
    }

    private val windowListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        contextUse.forget()
        publish()
    }

    private val contextUse = ContextUseCache()


    override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) {
        main.launch {
            flow.collect { value ->
                extras = extras.into(value)
                publish()
            }
        }
    }

    private fun publish() {
        val base = runtime ?: return
        hub.publish(
            base.copy(
                contextUsage = contextUse.of(base, app),
                preferences = extras.preferences,
                a2aClient = extras.a2aClient.copy(inboundFocus = InboundFocus.external.value),
                plans = extras.plans,
                planHistory = extras.planHistory,
                planFeeds = extras.planFeeds,
                delegations = extras.delegations,
                memory = extras.memory,
                usageRevision = extras.usageRevision,
                offers = extras.offers,
                offerPictures = extras.offerPictures,
                subagents = extras.subagents,
                subagentDeliveries = extras.subagentDeliveries,
                extensions = base.extensions + extras.extensions,
            ),
        )
    }
}

/**
 * How full the context window is, worked out again only when what it reads changed: the trajectory, the run, the
 * model, or (after [forget]) the window set for it. Main thread.
 */
private class ContextUseCache {
    private var key: List<Any?>? = null
    private var value = ContextUsage()

    fun of(state: ServerState, context: Context): ContextUsage {
        val profile = state.configEditor
        val next = listOf(state.trajectory.sessionId, state.trajectory.revision, state.isRunning, state.currentRequest?.requestId,
            profile.profileId, profile.provider, profile.endpoint, profile.baseUrl, profile.model, profile.variant)
        if (next != key) {
            key = next
            value = contextUsage(state, loadContextWindow(context, profile))
        }
        return value
    }

    fun forget() {
        key = null
    }
}


/** [this] with what the person set. */
private fun ServerState.withPreferences(app: Context): ServerState = copy(preferences = PreferencesSource.read(app))
