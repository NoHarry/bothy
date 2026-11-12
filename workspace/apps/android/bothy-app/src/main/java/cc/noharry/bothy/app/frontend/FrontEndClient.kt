package cc.noharry.bothy.app.frontend

import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.bothy.server.server.BothyServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A front end's connection to [BothyServer] (the console, the phone screens, the overlay): [start] shakes hands and
 * hands every state to the screen on the main thread; [send] asks the server for something and, when it answers,
 * gives the answer back on the main thread. A refused request is logged, and passed on to whoever waits for it.
 */
internal class FrontEndClient(
    context: Context,
    name: String,
    capabilities: ClientCapabilities = ClientCapabilities(),
) : ServerSender {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client = ServerClient(
        channel = BothyServer.connect(context),
        clientInfo = ClientInfo(name),
        capabilities = capabilities,
        scope = io,
        onError = { error -> Log.w(TAG, "$name: ${error.message}") },
    )

    val state: ServerState get() = client.state.value

    /** Completed once the handshake is done: a request sent before waits for it, as the server would refuse it. */
    private val ready = CompletableDeferred<Unit>()

    fun start(onState: (ServerState) -> Unit) {
        main.launch {
            try {
                client.connect()
            } catch (refused: JsonRpcException) {
                // Closed before the server answered (a screen gone right after it opened), or refused: no state comes.
                Log.w(TAG, "handshake failed: ${refused.message}")
                return@launch
            }
            ready.complete(Unit)
            client.state.collect(onState)
        }
    }

    override fun <P, R> send(
        method: RequestMethod<P, R>,
        params: P,
        onRefused: (JsonRpcException) -> Unit,
        onAnswer: (R) -> Unit,
    ) {
        main.launch {
            ready.await()
            val answer = try {
                client.request(method, params)
            } catch (refused: JsonRpcException) {
                Log.w(TAG, "${method.name} refused: ${refused.message}")
                onRefused(refused)
                return@launch
            }
            onAnswer(answer)
        }
    }

    fun close() {
        // The screen's work stops first: closing the connection fails what still waits on it, which no one hears then.
        main.cancel()
        client.close()
        io.cancel()
    }

    private companion object {
        const val TAG = "FrontEndClient"
    }
}
