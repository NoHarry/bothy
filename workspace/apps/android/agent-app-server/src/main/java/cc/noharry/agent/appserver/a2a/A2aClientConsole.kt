@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.agent.appserver.a2a
import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.agent.a2a.server.A2aRequest
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.model.PREFS_NAME
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT
import cc.noharry.agent.appserver.runtime.RunAnnouncer
import cc.noharry.agent.appserver.runtime.RuntimeVoice
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opt-in A2A client mode: the sample talks to an A2A endpoint (by default its own server) through the
 * packaged direct client and speaks only `channel=speech`. Independent of [RuntimeSession]: it owns
 * no runtime, never touches the Debug transcript or session selection, and leaves the server unchanged.
 *
 * Main thread only, like the other sample entry points.
 */
object A2aClientConsole {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = linkedSetOf<(A2aClientState) -> Unit>()
    private var session: A2aClientSession? = null
    private var announcer: RunAnnouncer? = null
    @Volatile private var enabled: Boolean? = null
    @Volatile private var speakExternal: Boolean? = null

    val state: A2aClientState
        get() = session?.state ?: A2aClientState(enabled = enabled == true, speakExternal = speakExternal == true)

    fun isEnabled(context: Context): Boolean =
        enabled ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false).also { enabled = it }

    internal fun isSpeakingExternal(context: Context): Boolean =
        speakExternal ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPEAK_EXTERNAL, false).also { speakExternal = it }

    /**
     * 收到 A2A 播报: whether other clients' tasks are also spoken on this car. Off by default: a caller such as the
     * car's voice assistant speaks for itself, and the device only shows the task. Typed input on the device and PC debug
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

    @InternalAppServerApi
    fun newConversation(context: Context) = session(context).newConversation()

    /**
     * Read-only mirror for every inbound task but this car's own client's: a PC debug tool's
     * (`metadata.debugMirror=true`) or any other client's. Callable from the server's IO thread.
     */
    @InternalAppServerApi
    fun mirror(context: Context, request: A2aRequest): A2aMirror? {
        val source = a2aMirrorSource(request.metadata) ?: return null
        InboundFocus.inbound()
        val app = context.applicationContext
        return A2aMirror(request.taskId, request.contextId, request.text, source) { action -> scope.launch { action(session(app)) } }
    }

    private fun session(context: Context): A2aClientSession {
        session?.let { return it }
        val app = context.applicationContext
        val client = A2aModuleRegistry.packaged().create(DIRECT_MODULE_ID).factory().create(
            A2aClientConfig(
                // OkHttp callTimeout spans the whole SSE stream; outlive the server's 10 minute task bound.
                requestTimeout = 11.minutes,
                allowLoopbackHttpForDebug = true,
            ),
        )
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
        ?: RuntimeVoice.port.deviceAnnouncer(context, scope, followsSwitch = false) { message ->
            if (message.startsWith("TTS failed")) session?.updateState { it.notice(message, error = true) }
        }?.also { announcer = it }

    /** Whether speech is set up, read off Main; speech before it is known is shown but not played. */
    private fun refreshSpeechConfig(context: Context) {
        scope.launch {
            val ready = runCatching { RuntimeVoice.port.ready(context) }.getOrDefault(false)
            session?.updateState { current ->
                if (current.speechReady == ready) current else current.copy(speechReady = ready)
            }
        }
    }

    private const val TAG = "DebugA2aClient"
    private const val PREFS_NAME = "debug_a2a_client"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SPEAK_EXTERNAL = "speak_external"
    private const val REMOTE_ID = "sample-a2a-client"
}
