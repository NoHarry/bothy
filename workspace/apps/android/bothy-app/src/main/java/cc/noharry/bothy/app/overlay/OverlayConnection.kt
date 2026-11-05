package cc.noharry.bothy.app.overlay

import android.content.Context
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.bothy.app.frontend.FrontEndClient
import cc.noharry.bothy.protocol.VoiceNotifications
import cc.noharry.bothy.protocol.VoiceView
import cc.noharry.bothy.protocol.voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The overlay's connection to the server, as a protocol client like the console's: [OverlayService] opens it while it
 * runs, and every part of the overlay reads [state] and sends through it. The state stays readable (empty) before the
 * service is up and after it went, so a part that outlives it never reaches the runtime.
 */
internal object OverlayConnection {
    private const val CLIENT_NAME = "overlay"

    @Volatile private var front: FrontEndClient? = null
    private var follow: CoroutineScope? = null
    private val mutableState = MutableStateFlow(ServerState(connected = false))
    private val mutableDismissals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val state: StateFlow<ServerState> = mutableState.asStateFlow()

    /** The speaker sent a woken session away by voice. */
    val dismissals: SharedFlow<Unit> = mutableDismissals.asSharedFlow()

    val voice: VoiceView get() = mutableState.value.voice

    /** Connects for the overlay service; [onState] hears every state on the main thread. */
    fun open(context: Context, onState: (ServerState) -> Unit) {
        close()
        val opened = FrontEndClient(context, CLIENT_NAME)
        front = opened
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { follow = it }
        scope.launch {
            opened.client.notifications.filter { it.method == VoiceNotifications.DISMISSED.name }.collect { mutableDismissals.emit(Unit) }
        }
        opened.start { next ->
            mutableState.value = next
            onState(next)
        }
    }

    fun <P, R> send(method: RequestMethod<P, R>, params: P, onRefused: (JsonRpcException) -> Unit = {}, onAnswer: (R) -> Unit = {}) {
        front?.send(method, params, onRefused, onAnswer)
    }

    fun close() {
        follow?.cancel()
        follow = null
        front?.close()
        front = null
        mutableState.value = ServerState(connected = false)
    }
}
