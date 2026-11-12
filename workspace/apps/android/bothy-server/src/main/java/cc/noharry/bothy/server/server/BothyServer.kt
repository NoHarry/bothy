package cc.noharry.bothy.server.server

import android.app.Application
import android.content.Context
import cc.noharry.agent.appserver.a2a.A2aPlugin
import cc.noharry.agent.appserver.a2a.a2aRoutes
import cc.noharry.agent.androiduse.appserver.androidUseRoutes
import cc.noharry.agent.appserver.image.ImagesPlugin
import cc.noharry.agent.appserver.image.imageModelRoutes
import cc.noharry.agent.appserver.memory.MemoryPlugin
import cc.noharry.agent.appserver.memory.memoryRoutes
import cc.noharry.agent.appserver.pages.PagesPlugin
import cc.noharry.agent.appserver.pages.pageRoutes
import cc.noharry.agent.appserver.plans.PlansPlugin
import cc.noharry.agent.appserver.plans.planRoutes
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.coreModules
import cc.noharry.bothy.server.BuildConfig
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.a2a.BothyA2a
import cc.noharry.bothy.server.model.BothyModels
import cc.noharry.bothy.server.model.BothyFixtureModel
import cc.noharry.bothy.server.tools.BothyReplayCheck
import cc.noharry.bothy.server.tools.BothyRuns
import cc.noharry.bothy.server.tools.PAGES_RULE
import cc.noharry.bothy.server.tools.bothySkills
import cc.noharry.bothy.server.tools.bothyTools
import cc.noharry.bothy.server.voice.BothyVoice
import cc.noharry.bothy.server.voice.tts.BOTHY_CONFIG_SECTIONS

/**
 * Bothy's server: the app server ([AppServer]) with Bothy's features — 小博's voice, the on-device model, the checks,
 * the Playground, its own settings — and what Bothy plugs into the runtime ([BothyVoice], [BothyModels], [BothyRuns]).
 * The console, the phone screens and the overlay each connect to it as clients.
 */
object BothyServer {
    /**
     * Called from the app's [Application.onCreate]: what runs from the process's start. What an install coming from
     * v0.3.0 kept under the old names moves first ([StoredNames]), before anything reads it.
     */
    fun install(app: Application) {
        StoredNames.migrate(app)
        AppServer.install(app, bothyConfig())
    }

    /** A client's end of a new in-process connection; the server starts with the first. */
    fun connect(context: Context): MessageChannel = AppServer.connect(context)

    /** Starts the server without a front end (the wake word listens, plans run); nothing when it runs already. */
    fun wakeUp(context: Context) = AppServer.wakeUp(context)
}

/** Bothy's features, in the order they start. */
internal val bothyFeatures: List<AppServerPlugin> = listOf(PlaygroundFeature, VoiceFeature, OnDeviceFeature, ChecksFeature, BothySettingsFeature)

internal fun bothyConfig() = AppServerConfig(
    name = "bothy",
    // The model services (agent-app-models: DeepSeek and the others), operating the screen (agent-android-use) and the
    // device's tools (agent-device-capabilities) come from those modules' manifests, unlisted.
    plugins = bothyFeatures +
        cc.noharry.agent.appserver.a2ui.CardsPlugin { cc.noharry.bothy.server.cards.BothyCards(it) } +
        // The person's own agents, the local endpoint other A2A clients hand tasks to, and client mode; its tools come
        // first among the plugins' run tools, as before it was a plugin.
        A2aPlugin(BothyA2a) +
        // Long answers, comparisons and plans drawn as pages while the person looks at the conversation.
        PagesPlugin(whenToDraw = listOf(PAGES_RULE)) +
        // Timed and watched plans, their alarm and the 计划 page; before memory, so their facts come first in each round.
        PlansPlugin() +
        // What 小博 remembers about the person, read and edited on 设置 → 记忆.
        MemoryPlugin(page = BOTHY_MEMORY_PAGE) +
        // Pictures drawn with the image model set up on its settings page, shown on the overlay.
        BothyImages,
    persona = BothyPersona,
    // The Playground's 「回到车机」 and Bothy's skills; its rules and gates read the run, so they are BothyRuns'.
    tools = ::bothyTools,
    skills = ::bothySkills,
    // A new installation allows everything, as Bothy always has; a mode the person picks on its screens is kept.
    approvals = ConfirmationMode.ALLOW_ALL_SESSION,
    // The demo car, when a screen turns the simulated backend on.
    simulatedModel = ::BothyFixtureModel,
    presets = bothyPresets(),
    voice = BothyVoice,
    models = BothyModels,
    runs = BothyRuns,
    replayCheck = BothyReplayCheck,
    builtIns = BothyBuiltIns,
    // Head units whose trimmed system store lacks the roots DeepSeek's and other services' chains end in.
    extraTrustRoots = R.raw.deepseek_cas,
    // The scene its turns have always run in: the lessons learnt on devices and its voice skill's scenes are under it.
    scene = BOTHY_SCENE,
    // Plugin packages: those of the publishers this build was given (-PbothyTrustedPluginPublishers) with one
    // confirmation, other signed ones after a second warning, unsigned ones only in a debug build.
    trustedPublishers = BuildConfig.TRUSTED_PLUGIN_PUBLISHERS.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
    allowUnsignedPackages = BuildConfig.DEBUG,
    // A debug build also listens on localabstract:bothy (tools/bothy-protocol) and localabstract:bothy-acp (editors).
    debugSockets = BuildConfig.DEBUG,
    // Each run's model calls, events and replay check kept as files, as Bothy always has (debug and release alike).
    diagnostics = true,
)

internal const val BOTHY_SCENE = "debug-console"

/**
 * Everything the server offers of its own: writing the workspace, the web, scripts (exec_js, still off on the basic
 * tools page until switched on) and subagents. Operating the screen and the device's tools are the plugins of the
 * runtime's agent-android-use and agent-device-capabilities, loaded from their manifests; drawn pages, plans, memory and
 * pictures are plugins Bothy lists ([PagesPlugin], [PlansPlugin], [MemoryPlugin], [ImagesPlugin]); starting after boot
 * is its settings plugin's ([BothySettingsFeature]).
 */
internal val BothyBuiltIns = BuiltInFeatures(fileWriting = true, web = true, scripts = true, subagents = true)

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

/** Where Bothy's screens show the pictures the picture tool draws, in the words that tool says ([ImagesPlugin]). */
internal const val BOTHY_PICTURES_SHOWN_ON = "屏幕的悬浮窗上"

/** Pictures drawn with an image model, shown on the overlay; its check is skipped while it is off. */
internal val BothyImages = ImagesPlugin(shownOn = BOTHY_PICTURES_SHOWN_ON)

/** Where the person reads and edits what 小博 remembers, in the words the memory tool says ([MemoryPlugin]). */
internal const val BOTHY_MEMORY_PAGE = "设置 → 记忆"

/** Every module of requests Bothy's server answers beside the core routes ([ServerRoutes]), with all its plugins running. */
internal fun serverModules(app: Context): List<RouteModule> =
    coreModules(app) + bothyRoutes(app) + a2aRoutes(app, localServer = true) +
        androidUseRoutes(app) +
        pageRoutes(app) + planRoutes(app) + memoryRoutes(app) + imageModelRoutes(app)
