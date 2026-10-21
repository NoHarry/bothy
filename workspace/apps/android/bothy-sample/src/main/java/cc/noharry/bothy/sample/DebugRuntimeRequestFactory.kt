package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentRequest

internal fun debugAgentRequest(
    requestId: String,
    sessionId: String,
    input: String,
    config: DebugProviderConfig,
    profile: DebugProviderProfile,
): AgentRequest =
    AgentRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = DEBUG_SCENE,
        modelRef = config.modelRef(profile),
    )

internal const val DEBUG_SCENE = "debug-console"
