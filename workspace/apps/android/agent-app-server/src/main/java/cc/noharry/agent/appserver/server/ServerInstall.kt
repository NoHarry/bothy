@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.app.Application
import cc.noharry.agent.appserver.a2a.A2aDelegations
import cc.noharry.agent.appserver.a2a.A2aServerManager
import cc.noharry.agent.appserver.androiduse.applyAndroidUseSwitch
import cc.noharry.agent.appserver.androiduse.scheduleAndroidUseRepair
import cc.noharry.agent.appserver.evidence.benchTrustedClient
import cc.noharry.agent.appserver.mcp.McpToolCatalogs
import cc.noharry.agent.appserver.memory.UserMemoryStore
import cc.noharry.agent.appserver.plans.PlanRunner
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.usage.UsageStore

/**
 * What runs in the app's process from its start, before any front end connects: the session settings, then each of
 * the host's [features] (its voice, its own models), then stores, plans, A2A (the local endpoint only when the host
 * runs one) and tools.
 */
internal fun installServer(app: Application, features: List<AppServerFeature>) {
    SessionSettings.init(app)
    features.forEach { it.install(app) }
    // The trust store (system roots plus the bundled ones older devices lack) is loaded once, here off the main
    // thread, before the first wake or request needs it.
    Thread({ runCatching { benchTrustedClient(app) } }, "bench-trust").start()
    UsageStore.init(app)
    UserMemoryStore.init(app)
    PlanRunner.init(app)
    if (AppHost.a2a.localServer) A2aServerManager.start(app)
    A2aDelegations.install(app) { report -> RuntimeSession.deliverDelegationReport(app, report) }
    AppSubagents.install(app) { report -> RuntimeSession.deliverSubagentReport(app, report) }
    AppSubagents.onLifecycle(RuntimeSession::noteSubagent)
    McpToolCatalogs.refreshOnAppStart(app)
    applyAndroidUseSwitch(app)
    scheduleAndroidUseRepair(app)
}
