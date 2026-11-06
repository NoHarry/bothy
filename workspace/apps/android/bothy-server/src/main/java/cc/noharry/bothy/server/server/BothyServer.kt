package cc.noharry.bothy.server.server

import android.app.Application
import android.content.Context
import cc.noharry.agent.appserver.deepseek.DeepSeekAdapter
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.HostPlaces
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerFeature
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.coreModules
import cc.noharry.bothy.server.BuildConfig
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.a2a.BothyA2a
import cc.noharry.bothy.server.model.BothyModels
import cc.noharry.bothy.server.tools.BothyRuns
import cc.noharry.bothy.server.voice.BothyVoice
import cc.noharry.bothy.server.voice.tts.BOTHY_CONFIG_SECTIONS

/**
 * Bothy's server: the app server ([AppServer]) with Bothy's features — 小博's voice, the on-device model, the checks,
 * the Playground, its own settings — and what Bothy plugs into the runtime ([BothyVoice], [BothyModels], [BothyRuns]).
 * The console, the phone screens and the overlay each connect to it as clients.
 */
object BothyServer {
    /** Called from the app's [Application.onCreate]: what runs from the process's start. */
    fun install(app: Application) = AppServer.install(app, bothyConfig())

    /** A client's end of a new in-process connection; the server starts with the first. */
    fun connect(context: Context): MessageChannel = AppServer.connect(context)

    /** Starts the server without a front end (the wake word listens, plans run); nothing when it runs already. */
    fun wakeUp(context: Context) = AppServer.wakeUp(context)
}

/** Bothy's features, in the order they start. */
internal val bothyFeatures: List<AppServerFeature> = listOf(PlaygroundFeature, VoiceFeature, OnDeviceFeature, ChecksFeature, BothySettingsFeature)

internal fun bothyConfig() = AppServerConfig(
    name = "bothy",
    features = bothyFeatures,
    persona = BothyPersona,
    presets = bothyPresets(),
    places = BothyPlaces,
    voice = BothyVoice,
    models = BothyModels,
    runs = BothyRuns,
    a2a = BothyA2a,
    builtIns = BothyBuiltIns,
    // Head units whose trimmed system store lacks the roots DeepSeek's and other services' chains end in.
    extraTrustRoots = R.raw.deepseek_cas,
    // DeepSeek beyond chat: its balance and relay check, its Search and Fetch APIs, its native history updates.
    providerAdapters = listOf(DeepSeekAdapter),
    // The scene its turns have always run in: the lessons learnt on devices and its voice skill's scenes are under it.
    scene = BOTHY_SCENE,
)

internal const val BOTHY_SCENE = "debug-console"

/** Everything the server offers of its own: plans, operating the screen, device media, cards, and starting after boot. */
internal val BothyBuiltIns = BuiltInFeatures(plans = true, androidUse = true, deviceMedia = true, cards = true, startOnBoot = true)

/** The model services this build carries, from local.properties (see this module's build.gradle.kts). */
internal fun bothyPresets() = ProviderPresets(
    configText = BuildConfig.ENV_MODEL_PROVIDER_CONFIG,
    localOverridesText = BuildConfig.ENV_MODEL_PROVIDER_LOCAL_OVERRIDES,
    values = mapOf(
        "AGENT_RUNTIME_MODEL_PROVIDER" to BuildConfig.ENV_AGENT_RUNTIME_MODEL_PROVIDER,
        "AGENT_RUNTIME_LIVE_PROVIDER" to BuildConfig.ENV_AGENT_RUNTIME_LIVE_PROVIDER,
        "AGENT_RUNTIME_MODEL_PROVIDER_STREAM" to BuildConfig.ENV_AGENT_RUNTIME_MODEL_PROVIDER_STREAM,
        "AGENT_RUNTIME_LIVE_PROVIDER_STREAM" to BuildConfig.ENV_AGENT_RUNTIME_LIVE_PROVIDER_STREAM,
        "OPENAI_API_KEY" to BuildConfig.ENV_OPENAI_API_KEY,
        "OPENAI_MODEL" to BuildConfig.ENV_OPENAI_MODEL,
        "OPENAI_BASE_URL" to BuildConfig.ENV_OPENAI_BASE_URL,
        "ANTHROPIC_API_KEY" to BuildConfig.ENV_ANTHROPIC_API_KEY,
        "ANTHROPIC_MODEL" to BuildConfig.ENV_ANTHROPIC_MODEL,
        "ANTHROPIC_BASE_URL" to BuildConfig.ENV_ANTHROPIC_BASE_URL,
    ),
    source = BuildConfig.PROVIDER_ENV_SOURCE,
    sections = BOTHY_CONFIG_SECTIONS,
)

/** 小博, the assistant of Bothy. */
internal val BothyPersona = Persona(
    name = "小博",
    identity = "You are 小博 (Xiaobo), the assistant of the Bothy sample app, running in its debug console. Answer in concise " +
        "Chinese. Help with the user's tasks through tools; do not present yourself as a generic cloud chatbot or model " +
        "provider. If asked why you are called 小博, say naturally you don't know (e.g. 这个我还真不清楚); do not invent a " +
        "reason or mention missing data. Do not expose API keys or secrets.",
    language = "zh-CN",
)

/** Where Bothy's screens show what the memory and picture tools leave, in the words those tools say. */
internal val BothyPlaces = HostPlaces(memoryPage = "设置 → 记忆", picturesShownOn = "屏幕的悬浮窗上")

/** Every module of requests Bothy's server answers beside the core routes ([ServerRoutes]). */
internal fun serverModules(app: Context): List<RouteModule> = coreModules(app) + bothyFeatures.flatMap { it.routes(app) }
