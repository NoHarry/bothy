package cc.noharry.bothy.app.frontend

import androidx.compose.runtime.compositionLocalOf
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod

/** The server as a screen's composables reach it: the [state] to draw, and [send] to ask it for something. */
internal class ServerAccess(val state: ServerState, private val client: FrontEndClient?) {
    fun <P, R> send(method: RequestMethod<P, R>, params: P, onRefused: (JsonRpcException) -> Unit = {}, onAnswer: (R) -> Unit = {}) {
        client?.send(method, params, onRefused, onAnswer)
    }
}

/** Provided at a screen's root: settings panes read what they show from it and save through it. */
internal val LocalServer = compositionLocalOf { ServerAccess(ServerState(connected = false), null) }
