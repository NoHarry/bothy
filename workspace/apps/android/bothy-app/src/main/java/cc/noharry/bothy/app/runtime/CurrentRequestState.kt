package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.CurrentRequest

internal const val DEBUG_SESSION_ID = "debug-console-session"
internal const val DEFAULT_USE_SIMULATED_MODEL = false
internal const val DEFAULT_STREAM_ENABLED = true
internal const val LIVE_MODEL_LABEL = "live model"
internal const val SIMULATED_MODEL_LABEL = "simulated MCP"

internal fun AgentRequest.toCurrentRequest(text: String): CurrentRequest =
    CurrentRequest(
        requestId = requestId,
        text = text,
        providerLabel = "",
        streamLabel = "",
        backendLabel = "",
        status = "",
    )

internal fun CurrentRequest.withRuntimeLabels(
    providerLabel: String,
    streamLabel: String,
    backendLabel: String,
    status: String,
): CurrentRequest =
    copy(
        providerLabel = providerLabel,
        streamLabel = streamLabel,
        backendLabel = backendLabel,
        status = status,
    )
