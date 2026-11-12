package cc.noharry.bothy.server.server

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PluginRequests
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.server.Assistant
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.Speech
import cc.noharry.agent.appserver.server.Turns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What Bothy's own code uses of the app server beyond its protocol: the conversation's turns, speech, the assistant on
 * screen and requests as a client sends them, as the server gave them to Bothy's voice plugin ([VoiceFeature]), which
 * always runs and starts with the process.
 */
internal object ServerLink {
    @Volatile
    private var plugin: PluginContext? = null

    /** Where Bothy's code that does not wait (a broadcast, a port) sends its requests: the main thread, at once. */
    val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Before the voice plugin started (a test without the server) nothing runs or speaks, and nothing can start. */
    val turns: Turns get() = plugin?.turns ?: NotStarted

    val speech: Speech get() = plugin?.speech ?: NotStarted

    /** Before the voice plugin started (a test without the server) nobody presents the assistant, as with no overlay. */
    val assistant: Assistant get() = plugin?.assistant ?: NoAssistant

    /** [method] with [params] as a client's request, answered by the server and its plugins on the main thread. */
    suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R = context().request(method, params)

    /**
     * Whether the plugin [id] runs now (`plugins/list`); true before the voice plugin started (a test without the
     * server), where every check runs as it always did.
     */
    suspend fun pluginRunning(id: String): Boolean {
        val ctx = plugin ?: return true
        return ctx.request(PluginRequests.LIST, Empty).plugins.any { it.id == id && it.running }
    }

    /** The voice plugin started: from now on Bothy's code reaches the server through [ctx]. */
    fun attach(ctx: PluginContext) {
        plugin = ctx
    }

    private fun context(): PluginContext = checkNotNull(plugin) { "Bothy's voice plugin has not started" }
}

/** The conversation and speech while the server has not started: nothing runs or speaks, and nothing can start. */
private object NotStarted : Turns, Speech {
    override val running: Boolean get() = false

    override val speaking: Boolean get() = false

    override fun start(text: String, spoken: Boolean, modelText: String?, live: Boolean, stream: Boolean?) = notStarted()

    override fun replace(text: String, live: Boolean, stream: Boolean?) = notStarted()

    override fun stop() = Unit

    override fun watch(listener: (ServerState) -> Unit): () -> Unit = {}

    override fun newConversation() = notStarted()

    override fun newConversationIfQuiet(): Boolean = false

    override suspend fun recentExchanges(limit: Int): List<Pair<String, String>> = emptyList()

    override fun record(words: String, reply: String, label: String, speak: Boolean) = notStarted()

    override fun checkHostedSearch(text: String, stream: Boolean) = notStarted()

    override fun note(text: String) = Unit

    override fun heard(text: String, forRun: String, modelText: String?, onNewTurn: () -> Unit) = notStarted()

    override fun announceFirst(text: String): Boolean = false

    override fun preview(text: String, done: (Boolean) -> Unit) = done(false)

    override fun silence() = Unit

    private fun notStarted(): Nothing = error(NOT_STARTED)
}

/** The assistant on screen while the server has not started: nobody presents it. */
private object NoAssistant : Assistant {
    override val ready: Boolean get() = false

    override val inFront: Boolean get() = false

    override fun ensureRunning(done: (String?) -> Unit) = done(NOT_STARTED)

    override fun start(open: Boolean, done: (String?) -> Unit) = done(NOT_STARTED)

    override suspend fun card(command: OverlayCommand): String = NOT_STARTED

    override fun wake(origin: String, taken: (Boolean) -> Unit) = taken(false)

    override suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R? = null

    override fun <P> tell(method: NotificationMethod<P>, params: P) = Unit
}

private const val NOT_STARTED = "the server has not started"

/** A debug door's backend: the live model profile for "live" (or its old name "catalog"), else as it is. */
internal fun debugLiveBackend(backend: String?): Boolean = backend?.lowercase() in LIVE_BACKEND_NAMES

/** A debug door's stream switch: "1" or "true" streams, "0" or "false" does not, anything else leaves it. */
internal fun debugStream(stream: String?): Boolean? = when (stream?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

/** A debug door's on-or-off value: 1, true, on or yes; 0, false, off or no; anything else is neither. */
internal fun debugSwitch(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "1", "true", "on", "yes" -> true
    "0", "false", "off", "no" -> false
    else -> null
}

/** What a debug door (the text broadcast, the ASR port) calls the live backend. */
internal const val DEBUG_BACKEND_LIVE = "live"

private val LIVE_BACKEND_NAMES = setOf(DEBUG_BACKEND_LIVE, "catalog")
