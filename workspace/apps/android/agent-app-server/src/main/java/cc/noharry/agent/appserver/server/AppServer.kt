package cc.noharry.agent.appserver.server

import android.app.Application
import android.content.Context
import androidx.annotation.RawRes
import cc.noharry.agent.appserver.BuildConfig
import cc.noharry.agent.appserver.a2a.A2aHost
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.pages.PageRoutes
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.transport.InProcessChannels
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.HostModels
import cc.noharry.agent.appserver.runtime.HostPlaces
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.runtime.RuntimeVoice
import cc.noharry.agent.appserver.runtime.VoicePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * A feature a host app adds to its server (a voice, an on-device model, checks of its own): what it starts with the
 * process, the requests it answers, what it publishes beside the runtime's state (its own sections), and what it does
 * when the app comes to the front or the assistant on screen comes and goes. Every part is optional.
 */
interface AppServerFeature {
    /** From the process's start (the app's `onCreate`), before any client connects. */
    fun install(app: Application) = Unit

    fun routes(app: Context): List<RouteModule> = emptyList()

    /** Starts publishing into [state], once, when the server starts its state; main thread. */
    fun publish(app: Context, state: PublishedState) = Unit

    fun appInFront(app: Context, inFront: Boolean) = Unit

    /** The assistant on screen came up ([ready]) or went. */
    fun presenterReady(app: Context, ready: Boolean) = Unit
}

/** What the server publishes, as a feature adds to it: each change goes out at once. Main thread. */
interface PublishedState {
    /** What features published so far (their sections). */
    val current: ServerState

    fun update(change: ServerState.() -> ServerState)

    /** Every value of [flow] into what is published. */
    fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState)

    /** A notification beside the state, to every client. */
    fun broadcast(notification: JsonRpcNotification)
}

/**
 * What a host app gives its server: its name, its features, who the assistant is, the model services its build carries,
 * where its screens show what tools leave, what it plugs into the runtime, what it does with other A2A clients' tasks,
 * which of the server's own features it turns on ([BuiltInFeatures]; none by default), [extraTrustRoots]: a raw
 * resource of PEM certificates trusted beside the system's for every HTTPS call the server makes (devices whose system
 * store lacks a root a service needs; none by default), and the model services it supports beyond chat
 * ([providerAdapters]: a balance, web lookups, native history updates; none by default).
 */
class AppServerConfig(
    val name: String,
    val features: List<AppServerFeature> = emptyList(),
    val persona: Persona = Persona.GENERIC,
    val presets: ProviderPresets = ProviderPresets.NONE,
    val places: HostPlaces = HostPlaces.NONE,
    val voice: VoicePort = VoicePort.NONE,
    val models: HostModels = HostModels.NONE,
    val runs: RunAddOns = RunAddOns.NONE,
    val a2a: A2aHost = A2aHost.NONE,
    val builtIns: BuiltInFeatures = BuiltInFeatures.NONE,
    @RawRes val extraTrustRoots: Int? = null,
    val providerAdapters: List<ProviderAdapter> = emptyList(),
)

/**
 * The app server, as Codex's app-server is to its TUI and desktop app: one runtime ([RuntimeSession]) behind the
 * protocol, which every front end connects to as a client. A host app [install]s it with its [AppServerConfig] when
 * its process starts; [connect] gives a client its end of a new in-process connection, and the server starts with the
 * first one.
 */
object AppServer {
    @Volatile private var config = AppServerConfig(name = "app")

    @Volatile private var hub: ServerHub? = null

    val features: List<AppServerFeature> get() = config.features

    fun install(app: Application, config: AppServerConfig) {
        this.config = config
        RuntimeVoice.install(config.voice)
        AppHost.install(config.models, config.runs, config.persona, config.presets, config.places, config.a2a, config.builtIns, config.extraTrustRoots, config.providerAdapters)
        installServer(app, config.features)
    }

    fun connect(context: Context): MessageChannel {
        val (client, server) = InProcessChannels.pair()
        hub(context).connect(server)
        return client
    }

    /** Starts the server without a front end (a wake word listens, plans run); nothing when it runs already. */
    fun wakeUp(context: Context) {
        hub(context).ensureStarted()
    }

    private fun hub(context: Context): ServerHub = hub ?: synchronized(this) {
        hub ?: start(context.applicationContext).also { hub = it }
    }

    private fun start(app: Context): ServerHub {
        val runtime = RuntimeBackend(app)
        val features = config.features
        lateinit var created: ServerHub
        created = ServerHub(
            backend = runtime,
            settings = runtime,
            main = Dispatchers.Main.immediate,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            serverInfo = ServerInfo(config.name, app.versionName()),
            modules = coreModules(app) + features.flatMap { it.routes(app) },
            // The state's sources start with the first client that takes state, not with the app's presentation.
            startSources = { StateSources(app, created, features).start() },
        )
        RuntimeSession.threads.followed = created::isFollowed
        Presenter.link = created.presentation.apply {
            changed = { ready -> features.forEach { it.presenterReady(app, ready) } }
        }
        // From outside the app (a PC over adb) only in a debug build.
        if (BuildConfig.DEBUG) LocalSocketServer(created, config.name).start()
        return created
    }

    private fun Context.versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
}

/**
 * The modules of requests every app server answers beside the core routes ([ServerRoutes]), and the requests of the
 * host's provider adapters ([AppHost.providerAdapters]).
 */
fun coreModules(app: Context): List<RouteModule> = listOf(
    ImageModelRoutes(app),
    MemoryRoutes(app),
    McpRoutes(app),
    UsageRoutes(app),
    ModelSettingsRoutes(app),
    AppRoutes(app),
    FileRoutes(app),
    PageRoutes(app),
) + AppHost.providerAdapters.flatMap { it.routes(app) }
