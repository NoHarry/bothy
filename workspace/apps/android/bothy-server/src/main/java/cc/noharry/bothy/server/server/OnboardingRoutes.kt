package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.server.Presenter
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.device.DeviceMediaMcpProvider
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CheckLine
import cc.noharry.bothy.protocol.OnboardingRequests
import cc.noharry.bothy.protocol.OnboardingView
import cc.noharry.bothy.server.evidence.CheckState
import cc.noharry.bothy.server.evidence.checkChatModel
import cc.noharry.bothy.server.voice.tts.loadDoubaoTtsKey
import cc.noharry.bothy.server.voice.tts.saveDoubaoTtsKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The first launch's setup: whether it shows, the keys it saves, and what it reads back. */
internal class OnboardingRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(OnboardingRequests.READ) {
            withContext(Dispatchers.IO) {
                OnboardingView(
                    needed = Onboarding.needed(app),
                    doubaoKeySaved = loadDoubaoTtsKey(app).isNotEmpty(),
                    notificationAccess = DeviceMediaMcpProvider.notificationAccessGranted(app),
                    accessibilityEnabled = AndroidUseAccessibility.isEnabled(app),
                )
            }
        }
        on(OnboardingRequests.SAVE_MODEL_KEY) { params ->
            val profile = withContext(Dispatchers.IO) { ProviderConfig.load(app).activeProfile }
            RuntimeSession.saveProviderOverride(app, profile.provider, params.text.trim(), profile.model, profile.baseUrl, profile.endpoint)
            val result = withContext(Dispatchers.IO) { checkChatModel(app) }
            val found = result.finding ?: text("")
            if (result.state == CheckState.OK) {
                CheckLine(Finding(BothyFindingCode.ONBOARDING_MODEL_READY, more = listOf(found)))
            } else {
                CheckLine(found, failed = true)
            }
        }
        on(OnboardingRequests.SAVE_DOUBAO_KEY) { params -> SaveResult(withContext(Dispatchers.IO) { saveDoubaoTtsKey(app, params.text) }) }
        on(OnboardingRequests.FINISH) {
            Onboarding.finish(app)
            Empty
        }
    }
}

/**
 * The first launch without a model key (owner 2026-10-25: someone who installs it should need one model key and nothing
 * more to try 小博 on their own tablet): the DeepSeek key, checked at once; a 豆包语音 key, recommended, skippable; the
 * permissions, each with its status and the screen that grants it; and what to say first. Done, it never shows again.
 */
internal object Onboarding {
    /**
     * Whether the setup shows: on a launch without a model key, and from then on until 开始使用, so leaving it for a
     * settings screen (the app maybe recycled meanwhile) comes back to it, not to a console with permissions missing.
     */
    fun needed(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(DONE, false)) return false
        if (prefs.getBoolean(STARTED, false)) return true
        val config = ProviderConfig.load(context)
        if (config.isReady(config.activeProfile)) return false
        prefs.edit().putBoolean(STARTED, true).apply()
        return true
    }

    /**
     * 开始使用. Wake word stays off until the person turns it on in 设置 → 语音. A fresh install used to switch it on
     * here whenever the microphone was already granted.
     */
    fun finish(context: Context) {
        prefs(context).edit().putBoolean(DONE, true).apply()
        Presenter.ensureRunning()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "debug_onboarding"
    private const val DONE = "done"
    private const val STARTED = "started"
}
