package cc.noharry.bothy.server.server

import android.app.Application
import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PreferencesSource
import cc.noharry.agent.appserver.server.PublishedState
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.PlaygroundRequests
import cc.noharry.bothy.protocol.VoiceNotifications
import cc.noharry.bothy.protocol.speechLevel
import cc.noharry.bothy.protocol.withChecks
import cc.noharry.bothy.protocol.withOnDevice
import cc.noharry.bothy.protocol.withSpeechLevel
import cc.noharry.bothy.protocol.withToolSearch
import cc.noharry.bothy.protocol.withVoice
import cc.noharry.bothy.server.evidence.HealthCheckRuns
import cc.noharry.bothy.server.evidence.view
import cc.noharry.bothy.server.model.OnDeviceModel
import cc.noharry.bothy.server.tools.PlaygroundMode
import cc.noharry.bothy.server.voice.AsrTextServerManager
import cc.noharry.bothy.server.voice.BothyVoice
import cc.noharry.bothy.server.voice.SpeechFocus
import cc.noharry.bothy.server.voice.VoiceController
import cc.noharry.bothy.server.voice.VoiceFeedback
import cc.noharry.bothy.server.voice.VoiceForegroundService
import cc.noharry.bothy.server.voice.WakeAck
import cc.noharry.bothy.server.voice.WakeWord
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadVoiceConfig
import cc.noharry.bothy.server.voice.realtime.RealtimeShown
import cc.noharry.bothy.server.voice.tts.SystemTts
import cc.noharry.bothy.server.voice.tts.TtsActivity
import cc.noharry.bothy.server.voice.tts.TtsLevels
import cc.noharry.bothy.server.voice.view
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 小博's voice as a feature of the server: the voice's own services from the start, its requests, the voice section and
 * the speaker's level, Jev's tool search, the strip's dismissals, and the microphone service and the resting assistant
 * when the app comes to the front.
 */
internal object VoiceFeature : AppServerPlugin {
    override val id: String = "voice"

    // 小博 is a voice assistant: its microphone, wake word and speech are wired through the whole app.
    override val required: Boolean = true

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        // Bothy's code reaches the conversation, speech and the assistant on screen through this plugin's context.
        ServerLink.attach(ctx)
        SpeechFocus.attach(app)
        SystemTts.attach(app)
        BothyVoice.start(app)
        AsrTextServerManager.start(app)
        voiceRoutes(app).forEach(ctx::routes)
        ctx.publish { publish(app, this) }
        ctx.onAppInFront { appInFront(app, it) }
        ctx.onPresenterReady { ready -> if (ready) WakeWord.presenterReady(app) else WakeWord.refresh() }
    }

    private fun publish(app: Context, state: PublishedState) {
        listOf(VoiceController.state, VoiceController.active, VoiceController.notice, TtsActivity.active, WakeWord.enabled, WakeWord.status,
            VoiceFeedback.notice, wakeNoticeShown, RealtimeShown.exchange).forEach { flow -> state.follow(flow) { withVoice(voiceView(app)) } }
        state.update { withToolSearch(loadJevConfig(app).view()) }
        state.follow(PreferencesSource.changes) { withToolSearch(loadJevConfig(app).view()) }
        sampleSpeech(state)
        main.launch { VoiceController.dismissals.collect { state.broadcast(dismissed()) } }
        WakeWord.load(app)
        WakeAck.prepare(app)
    }

    /** In front: the wake word's microphone service may start, and with 语音唤醒 on 小博 comes up with the app. */
    private fun appInFront(app: Context, inFront: Boolean) {
        if (!inFront) return
        VoiceForegroundService.sync(app)
        // A phone has no resting 小博, so nothing comes up there (the presentation decides).
        if (loadVoiceConfig(app).overlayWake) ServerLink.assistant.ensureRunning()
    }

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The reply's loudness at the speaker, sampled while one plays: the overlay's light follows what is heard. A
     * stream measures its level at the track's playback head ([TtsLevels]); nothing plays, nothing is sent.
     */
    private fun sampleSpeech(state: PublishedState) {
        main.launch {
            TtsActivity.active.collectLatest { speaking ->
                if (!speaking) {
                    speech(state, null)
                    return@collectLatest
                }
                while (true) {
                    speech(state, TtsLevels.current())
                    delay(SPEECH_LEVEL_MILLIS)
                }
            }
        }
    }

    private fun speech(state: PublishedState, level: Float?) {
        if (state.current.speechLevel != level) state.update { withSpeechLevel(level) }
    }

    private fun dismissed() = JsonRpcNotification(
        VoiceNotifications.DISMISSED.name,
        ProtocolJson.encodeToJsonElement(VoiceNotifications.DISMISSED.params, Empty),
    )

    /** 50 a second, as the microphone's meter. */
    private const val SPEECH_LEVEL_MILLIS = 20L
}

/** The on-device model ([OnDeviceModel]): its downloads and inference server, its requests and its section. */
internal object OnDeviceFeature : AppServerPlugin {
    override val id: String = "on-device-model"

    // BothyModels hands the server this model's profile and client whether or not anything is downloaded.
    override val required: Boolean = true

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        OnDeviceModel.init(app)
        onDeviceRoutes().forEach(ctx::routes)
        ctx.publish {
            listOf(OnDeviceModel.downloads, OnDeviceModel.fileChanges, OnDeviceModel.server.state).forEach { flow ->
                follow(flow) { withOnDevice(onDeviceView(app)) }
            }
        }
    }
}

/** Settings → 检查: the checks and their latest results. */
internal object ChecksFeature : AppServerPlugin {
    override val id: String = "checks"

    override fun apply(ctx: PluginContext) {
        checkRoutes(ctx.app).forEach(ctx::routes)
        ctx.publish { follow(HealthCheckRuns.state) { withChecks(it.view()) } }
    }
}

/** The Bothy Playground beside the app: its mode, reported by the Playground. */
internal object PlaygroundFeature : AppServerPlugin {
    override val id: String = "playground"

    // BothyRuns reads the Playground's mode for every run.
    override val required: Boolean = true

    override fun apply(ctx: PluginContext) {
        PlaygroundMode.init(ctx.app)
        playgroundRoutes(ctx.app).forEach(ctx::routes)
    }
}

/** The rest of Bothy's own settings: first run (豆包's key, the permissions) and 连接应用. */
internal object BothySettingsFeature : AppServerPlugin {
    override val id: String = "bothy-settings"

    override val required: Boolean = true

    override fun apply(ctx: PluginContext) {
        settingsRoutes(ctx.app).forEach(ctx::routes)
        // Bothy starts after a reboot or an update, plans on or off: its wake word listens again without the app opened.
        ctx.startOnBoot()
    }
}

/** `playground/setReal`: the Playground navigates for real, so the demo car's tools go away. */
internal class PlaygroundRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(PlaygroundRequests.SET_REAL) {
            PlaygroundMode.report(app, it.on)
            Empty
        }
    }
}

private fun voiceRoutes(app: Context): List<RouteModule> =
    listOf(VoiceSettingsRoutes(app), VoiceControlRoutes(app), RealtimeRoutes(app), CompanionRoutes(app), ToolSearchRoutes(app))

private fun onDeviceRoutes(): List<RouteModule> = listOf(OnDeviceRoutes())

private fun checkRoutes(app: Context): List<RouteModule> = listOf(ChecksRoutes(app))

private fun playgroundRoutes(app: Context): List<RouteModule> = listOf(PlaygroundRoutes(app))

private fun settingsRoutes(app: Context): List<RouteModule> = listOf(OnboardingRoutes(app), ConnectorRoutes(app))

/** The requests Bothy's own plugins answer, as each adds them when it starts. */
internal fun bothyRoutes(app: Context): List<RouteModule> =
    voiceRoutes(app) + onDeviceRoutes() + checkRoutes(app) + playgroundRoutes(app) + settingsRoutes(app)
