package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.protocol.CurrentRequest
import cc.noharry.agent.kernel.api.AgentRequest

@InternalAppServerApi
const val DEBUG_SESSION_ID = "debug-console-session"
internal const val DEFAULT_USE_SIMULATED_MODEL = false
internal const val DEFAULT_STREAM_ENABLED = true

internal fun AgentRequest.toCurrentRequest(text: String): CurrentRequest =
    CurrentRequest(requestId = requestId, text = text)
