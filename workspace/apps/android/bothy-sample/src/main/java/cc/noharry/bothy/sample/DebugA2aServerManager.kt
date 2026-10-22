package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.server.A2aServer

/** Process-lifetime loopback endpoint, separate from the ASR and outbound A2A client paths. */
internal object DebugA2aServerManager {
    private var server: A2aServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val candidate = A2aServer(handler = DebugA2aRequestHandler(context))
        try {
            val port = candidate.start()
            server = candidate
            Log.i(TAG, "A2A text server listening on 127.0.0.1:$port/a2a")
        } catch (error: Exception) {
            candidate.close()
            Log.e(TAG, "A2A text server could not start", error)
        }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    private const val TAG = "DebugA2aServer"
}
