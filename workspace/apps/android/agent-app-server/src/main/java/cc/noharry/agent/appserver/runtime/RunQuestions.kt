package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

/** The run that stopped to ask the person something, and the question; null for any other event. */
fun AgentEvent.followUpQuestion(): Pair<String, String>? = when (this) {
    is AgentEvent.RuntimeBlocked -> if (blocker.code == RuntimeErrorCode.WAITING_USER_INPUT.wireCode) {
        blocker.runId to blocker.message
    } else null
    is AgentEvent.ResultAvailable -> if (result.status == RuntimeRunStatus.WAITING_USER_INPUT) {
        result.runId to result.response
    } else null
    else -> null
}

/** A turn answered outside a run (陪聊 and the like), as its session records it. */
internal const val OUTSIDE_TURN_SCENE = "companion"

/** How many of the latest exchanges a reply outside a run reads. */
internal const val OUTSIDE_CONTEXT_TURNS = 4
