@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app
import android.app.Application
import cc.noharry.bothy.app.a2a.A2aDelegations
import cc.noharry.bothy.app.a2a.A2aServerManager
import cc.noharry.bothy.app.androiduse.applyAndroidUseSwitch
import cc.noharry.bothy.app.androiduse.scheduleAndroidUseRepair
import cc.noharry.bothy.app.console.DisplaySettings
import cc.noharry.bothy.app.evidence.benchTrustedClient
import cc.noharry.bothy.app.mcp.McpToolCatalogs
import cc.noharry.bothy.app.memory.UserMemoryStore
import cc.noharry.bothy.app.plans.PlanRunner
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.session.SessionSettings
import cc.noharry.bothy.app.skills.BothySubagents
import cc.noharry.bothy.app.tools.PlaygroundMode
import cc.noharry.bothy.app.usage.UsageStore
import cc.noharry.bothy.app.voice.AsrTextServerManager
import cc.noharry.bothy.app.voice.SpeechFocus
import cc.noharry.bothy.app.voice.tts.SystemTts

class BothyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DisplaySettings.init(this)
        SessionSettings.init(this)
        PlaygroundMode.init(this)
        SpeechFocus.attach(this)
        SystemTts.attach(this)
        // The trust store (system roots plus the bundled ones older devices lack) is loaded once, here off the main
        // thread, before the first wake or request needs it.
        Thread({ runCatching { benchTrustedClient(this) } }, "bench-trust").start()
        UsageStore.init(this)
        UserMemoryStore.init(this)
        PlanRunner.init(this)
        AsrTextServerManager.start(this)
        A2aServerManager.start(this)
        A2aDelegations.install(this) { report -> RuntimeSession.deliverDelegationReport(this, report) }
        BothySubagents.install(this) { report -> RuntimeSession.deliverSubagentReport(this, report) }
        BothySubagents.onLifecycle(RuntimeSession::noteSubagent)
        McpToolCatalogs.refreshOnAppStart(this)
        applyAndroidUseSwitch(this)
        scheduleAndroidUseRepair(this)
    }
}
