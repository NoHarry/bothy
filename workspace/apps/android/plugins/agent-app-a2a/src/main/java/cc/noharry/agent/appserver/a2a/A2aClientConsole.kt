@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.agent.appserver.a2a
import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.agent.a2a.server.A2aRequest
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT
import cc.noharry.agent.appserver.runtime.RunAnnouncer
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opt-in A2A client mode: the app talks to its own local A2A endpoint through the packaged direct client and speaks
 * only `channel=speech`, so it is there only while the A2A plugin runs that endpoint ([A2aHost.localServer]).
 * Independent of the server's runtime: it owns none, never touches the Debug transcript or session selection, and
 * leaves the server unchanged. Off with the plugin: its running task cancelled, its client closed, input refused.
 *
 * Main thread only, like the other entry points.
 */
object A2aClientConsole {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = linkedSetOf<(A2aClientState) -> Unit>()
    private var session: A2aClientSession? = null
    private var client: cc.noharry.agent.a2a.A2aClient? = null
    private var announcer: RunAnnouncer? = null
    @Volatile private var enabled: Boolean? = null
    @Volatile private var speakExternal: Boolean? = null

    val state: A2aClientState
        get() = session?.state ?: A2aClientState(enabled = enabled == true, speakExternal = speakExternal == true)

    /** On only while the A2A plugin runs the local endpoint the mode talks to, and the person turned it on. */
    fun isEnabled(context: Context): Boolean = A2aRuntime.running && A2aRuntime.host.localServer && (enabled ?: run {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getBoolean(KEY_ENABLED, false).also { enabled = it }
    })

    internal fun isSpeakingExternal(context: Context): Boolean =
        speakExternal ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPEAK_EXTERNAL, false).also { speakExternal = it }

    /**
     * 收到 A2A 播报: whether other clients' tasks are also spoken on this device. Off by default: a caller such as
     * another voice assistant speaks for itself, and the device only shows the task. Typed input on the device and PC debug
     * tasks are spoken either way. Independent of client mode.
     */
    fun setSpeakExternal(context: Context, value: Boolean) {
        val app = context.applicationContext
        if (isSpeakingExternal(app) == value) return
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEAK_EXTERNAL, value).apply()
        speakExternal = value
        Log.i(TAG, "A2A external task speech ${if (value) "on" else "off"}")
        session(app).updateState { it.copy(speakExternal = value) }
        if (value) refreshSpeechConfig(app)
    }

    fun attach(context: Context, listener: (A2aClientState) -> Unit): () -> Unit {
        session(context)
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    /** Leaving the mode only stops routing new input; a task already running finishes and is spoken. */
    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        if (isEnabled(app) == value) return
        if (value && !(A2aRuntime.running && A2aRuntime.host.localServer)) {
            Log.w(TAG, "A2A client mode needs the host's local endpoint, which does not run")
            return
        }
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
        Log.i(TAG, "A2A client mode ${if (value) "on" else "off"}")
        session(app).updateState { it.copy(enabled = value) }
        if (value) refreshSpeechConfig(app)
    }

    fun send(context: Context, text: String, source: A2aInputSource): Boolean {
        val accepted = session(context).send(text, source)
        if (accepted) announcer?.stop() else Log.w(TAG, "Refused ${source.name} input while a client task runs")
        return accepted
    }

    /** ASR barge-in: stop speech, cancel the running client task, then send. */
    fun replace(context: Context, text: String, source: A2aInputSource) {
        announcer?.stop()
        session(context).replace(text, source)
    }

    fun steer(context: Context, text: String, source: A2aInputSource, wireText: String = text) =
        session(context).steer(text, source, wireText)

    fun answer(context: Context, questionId: String, text: String, source: A2aInputSource) =
        session(context).answer(questionId, text, source)

    fun cancel(context: Context) {
        announcer?.stop()
        session(context).cancel()
    }

    internal fun newConversation(context: Context) = session(context).newConversation()

    /**
     * Read-only mirror for every inbound task but this device's own client's: a PC debug tool's
     * (`metadata.debugMirror=true`) or any other client's. Callable from the server's IO thread.
     */
    internal fun mirror(context: Context, request: A2aRequest): A2aMirror? {
        val source = a2aMirrorSource(request.metadata) ?: return null
        InboundFocus.inbound()
        val app = context.applicationContext
        return A2aMirror(request.taskId, request.contextId, request.text, source) { action ->
            if (A2aRuntime.running) scope.launch { action(session(app)) }
        }
    }

    /**
     * The plugin stopped: the running client task is cancelled, speech stops, the client is closed and what the mode
     * showed is let go; turned on again, a new session starts from the person's switch.
     */
    internal fun stop() {
        announcer?.stop()
        announcer = null
        session?.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        session = null
        client?.let { runCatching { it.close() } }
        client = null
        enabled = null
        speakExternal = null
    }

    private fun session(context: Context): A2aClientSession {
        session?.let { return it }
        val app = context.applicationContext
        val client = A2aModuleRegistry.packaged().create(DIRECT_MODULE_ID).factory().create(
            A2aClientConfig(
                // OkHttp callTimeout spans the whole SSE stream; outlive the server's 10 minute task bound.
                requestTimeout = 11.minutes,
                allowLoopbackHttpForDebug = true,
                authProvider = cc.noharry.agent.a2a.A2aAuthProvider { auth ->
                    if (auth.remoteId == REMOTE_ID) cc.noharry.agent.a2a.A2aProtectedHeaders.of(
                        mapOf("Authorization" to "Bearer ${A2aServerManager.serverToken(app)}"),
                    ) else cc.noharry.agent.a2a.A2aProtectedHeaders.empty()
                },
            ),
        )
        this.client = client
        val created = A2aClientSession(
            texts = app.resources,
            client = client,
            remote = RemoteAgentRef(id = REMOTE_ID, endpoint = URI(DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT)),
            scope = scope,
            onSpeech = { speech -> speak(app, speech) },
            onState = { current -> listeners.toList().forEach { it(current) } },
            initial = A2aClientState(enabled = isEnabled(app), speakExternal = isSpeakingExternal(app)),
        )
        session = created
        refreshSpeechConfig(app)
        return created
    }

    /** Speaks [text] before anything queued (a voice append's acknowledgement); false when speech is not ready. */
    fun announceFirst(context: Context, text: String): Boolean = announcer(context.applicationContext)?.announceFirst(text) ?: false

    private fun speak(context: Context, speech: A2aSpeech) {
        announcer(context)?.speakWireSpeech(speech.taskId, speech.text, speech.final)
    }

    /** The voice the remote agent's speech is said in, whenever speech is set up (not only with 播报 on). */
    private fun announcer(context: Context): RunAnnouncer? = announcer
        ?: A2aRuntime.voice()?.deviceAnnouncer(context, scope, followsSwitch = false) { message ->
            if (message.startsWith("TTS failed")) session?.updateState { it.notice(message, error = true) }
        }?.also { announcer = it }

    /** Whether speech is set up, read off Main; speech before it is known is shown but not played. */
    private fun refreshSpeechConfig(context: Context) {
        scope.launch {
            val ready = runCatching { A2aRuntime.voice()?.ready(context) == true }.getOrDefault(false)
            session?.updateState { current ->
                if (current.speechReady == ready) current else current.copy(speechReady = ready)
            }
        }
    }

    private const val TAG = "DebugA2aClient"
    private const val PREFS_NAME = "app_server_a2a_client"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SPEAK_EXTERNAL = "speak_external"
    private const val REMOTE_ID = "sample-a2a-client"
}

/** A debug broadcast's extra that turns client mode on or off (`on`/`off`). */
const val DEBUG_A2A_CLIENT_EXTRA = "a2a_client"
