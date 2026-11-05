package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.kernel.api.AgentEvent

/**
 * The network domains approvals are about: the latest a run asked for, each waiting approval's (by session: runs in
 * other sessions may wait at the same time), and those a session approved already (an approval for one of them is
 * given at once). Main thread.
 */
internal class ConfirmationDomains {
    private val confirmedBySession = linkedMapOf<String, LinkedHashSet<String>>()
    private val pendingBySession = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val latestByRun = linkedMapOf<String, String>()

    /** The domain the approval waiting under [pendingId] is about; blank when none. */
    fun of(pendingId: String): String = pendingBySession.values.firstNotNullOfOrNull { it[pendingId] }.orEmpty()

    fun remember(sessionId: String, pendingId: String, domain: String) {
        if (domain.isNotBlank()) pendingBySession.getOrPut(sessionId) { linkedMapOf() }[pendingId] = domain
    }

    /** The domains [sessionId] approved. */
    fun confirmed(sessionId: String): LinkedHashSet<String> = confirmedBySession.getOrPut(sessionId) { linkedSetOf() }

    /** [pendingId] was approved in [sessionId]: its domain counts as approved there from now on. */
    fun approved(sessionId: String, pendingId: String) {
        of(pendingId).takeIf { it.isNotBlank() }?.let { confirmed(sessionId).add(it) }
    }

    /** Remembers which domain a run of [sessionId] asked about with its network tool, and which approval that is. */
    fun track(sessionId: String, event: AgentEvent) {
        if (event is AgentEvent.NetworkToolActivity &&
            event.decision.contains(CONFIRMATION_REQUIRED_DECISION, ignoreCase = true) &&
            event.domain.isNotBlank()
        ) {
            latestByRun[event.runId] = event.domain
        }
        if (event is AgentEvent.ConfirmationRequired) {
            remember(sessionId, event.pending.pendingId, latestByRun[event.pending.runId].orEmpty())
        }
    }

    /** A run of [sessionId] comes on screen: approvals that waited there before were an earlier run's. */
    fun runShown(sessionId: String, runId: String?) {
        pendingBySession.remove(sessionId)
        runId?.let(latestByRun::remove)
    }

    /** A session starts over: nothing it approved counts any more. */
    fun forget(sessionId: String) {
        confirmedBySession.remove(sessionId)
        pendingBySession.remove(sessionId)
    }
}
