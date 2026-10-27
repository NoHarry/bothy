package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.ProviderProfile

internal fun agentRequest(
    requestId: String,
    sessionId: String,
    input: String,
    config: ProviderConfig,
    profile: ProviderProfile,
): AgentRequest =
    AgentRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = DEBUG_SCENE,
        modelRef = config.modelRef(profile),
    )

internal const val DEBUG_SCENE = "debug-console"
