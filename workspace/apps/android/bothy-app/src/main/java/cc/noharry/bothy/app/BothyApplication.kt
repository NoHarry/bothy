@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import cc.noharry.bothy.app.a2a.A2aDelegations
import cc.noharry.bothy.app.a2a.A2aServerManager
import cc.noharry.bothy.app.androiduse.applyAndroidUseSwitch
import cc.noharry.bothy.app.androiduse.scheduleAndroidUseRepair
import cc.noharry.bothy.app.console.AppLanguages
import cc.noharry.bothy.app.console.DisplaySettings
import cc.noharry.bothy.app.evidence.benchTrustedClient
import cc.noharry.bothy.app.mcp.McpToolCatalogs
import cc.noharry.bothy.app.memory.UserMemoryStore
import cc.noharry.bothy.app.model.OnDeviceModel
import cc.noharry.bothy.app.plans.PlanRunner
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.session.SessionSettings
import cc.noharry.bothy.app.skills.BothySubagents
import cc.noharry.bothy.app.tools.PlaygroundMode
import cc.noharry.bothy.app.usage.UsageStore
import cc.noharry.bothy.app.voice.AsrTextServerManager
import cc.noharry.bothy.app.voice.SpeechFocus
import cc.noharry.bothy.app.voice.tts.SystemTts
import java.util.Locale

class BothyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguages.wrap(base))
    }

    private var shownLocale: Locale? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A change of the screens' language (Settings → General, or the system's) rewords what the loaded config names.
        // The app's own resources tell it: before Android 13 they keep the chosen language when the system's changes.
        val locale = resources.configuration.locales.get(0)
        if (shownLocale != null && locale != shownLocale) RuntimeSession.languageChanged(this)
        shownLocale = locale
    }

    override fun onCreate() {
        super.onCreate()
        shownLocale = resources.configuration.locales.get(0)
        DisplaySettings.init(this)
        SessionSettings.init(this)
        PlaygroundMode.init(this)
        SpeechFocus.attach(this)
        SystemTts.attach(this)
        // The trust store (system roots plus the bundled ones older devices lack) is loaded once, here off the main
        // thread, before the first wake or request needs it.
        Thread({ runCatching { benchTrustedClient(this) } }, "bench-trust").start()
        UsageStore.init(this)
        OnDeviceModel.init(this)
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
