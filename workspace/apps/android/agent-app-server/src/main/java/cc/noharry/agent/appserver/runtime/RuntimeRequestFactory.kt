package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.kernel.api.AgentRequest

@InternalAppServerApi
fun agentRequest(
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
