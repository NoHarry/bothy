package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.appserver.protocol.A2aModuleState


/** The A2A clients in one line, for the transcript's status lines. */
internal val A2aModuleState.summary: String
    get() = when {
        profiles.isEmpty() -> error ?: "No A2A client profiles"
        enabledProfileIds.isEmpty() -> "A2A clients disabled"
        else -> "${enabledProfileIds.size} enabled · ${loadedModuleIds.size} loaded · ${errorsByProfileId.size} errors"
    }
