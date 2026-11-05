package cc.noharry.bothy.app.frontend

import android.content.Context
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod

/**
 * The connection for what talks to the server without a screen of its own: receivers a head unit or adb sends to,
 * the onboarding. Opened on first use and kept for the process, as the server is.
 */
internal object AppClient {
    private const val CLIENT_NAME = "app"

    @Volatile private var front: FrontEndClient? = null

    fun state(context: Context): ServerState = connection(context).state

    fun <P, R> send(
        context: Context,
        method: RequestMethod<P, R>,
        params: P,
        onRefused: (JsonRpcException) -> Unit = {},
        onAnswer: (R) -> Unit = {},
    ) = connection(context).send(method, params, onRefused, onAnswer)

    private fun connection(context: Context): FrontEndClient = front ?: synchronized(this) {
        front ?: FrontEndClient(context.applicationContext, CLIENT_NAME).also {
            it.start {}
            front = it
        }
    }
}
