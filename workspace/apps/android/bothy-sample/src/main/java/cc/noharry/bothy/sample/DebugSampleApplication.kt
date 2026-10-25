@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.app.Application

class DebugSampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugDisplaySettings.init(this)
        DebugSessionSettings.init(this)
        DebugPlaygroundMode.init(this)
        DebugSpeechFocus.attach(this)
        DebugSystemTts.attach(this)
        // The trust store (system roots plus the bundled ones older devices lack) is loaded once, here off the main
        // thread, before the first wake or request needs it.
        Thread({ runCatching { benchTrustedClient(this) } }, "bench-trust").start()
        DebugUsageStore.init(this)
        DebugUserMemoryStore.init(this)
        DebugPlanRunner.init(this)
        DebugAsrTextServerManager.start(this)
        DebugA2aServerManager.start(this)
        DebugA2aDelegations.install(this) { report -> DebugRuntimeSession.deliverDelegationReport(this, report) }
        DebugSubagents.install(this) { report -> DebugRuntimeSession.deliverSubagentReport(this, report) }
        DebugSubagents.onLifecycle(DebugRuntimeSession::noteSubagent)
        McpToolCatalogs.refreshOnAppStart(this)
        applyDebugAndroidUseSwitch(this)
        scheduleDebugAndroidUseRepair(this)
    }
}
