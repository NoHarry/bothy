package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentRequest

internal const val DEBUG_SESSION_ID = "debug-console-session"
internal const val DEFAULT_USE_SIMULATED_MODEL = false
internal const val DEFAULT_STREAM_ENABLED = true
internal const val LIVE_MODEL_LABEL = "live model"
internal const val SIMULATED_MODEL_LABEL = "simulated MCP"

internal fun AgentRequest.toDebugCurrentRequest(text: String): DebugCurrentRequest =
    DebugCurrentRequest(
        requestId = requestId,
        text = text,
        providerLabel = "",
        streamLabel = "",
        backendLabel = "",
        status = "",
    )

internal fun DebugCurrentRequest.withRuntimeLabels(
    providerLabel: String,
    streamLabel: String,
    backendLabel: String,
    status: String,
): DebugCurrentRequest =
    copy(
        providerLabel = providerLabel,
        streamLabel = streamLabel,
        backendLabel = backendLabel,
        status = status,
    )
