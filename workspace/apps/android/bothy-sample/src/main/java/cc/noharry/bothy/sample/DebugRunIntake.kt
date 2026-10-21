package cc.noharry.bothy.sample

/**
 * Input adapters supply identity and presentation policy; the shared runner owns execution. A null [sessionId] runs
 * in the selected session (a spoken turn names only its request, see [DebugSpokenRuns]).
 */
internal data class DebugRunIntake(val requestId: String, val sessionId: String? = null, val external: Boolean = false)

/**
 * Runs asked for by voice (the overlay's wake, the console's mic, the ASR port). A spoken request gets a spoken
 * answer: their final reply is always played, partial and failed ones included, where a typed request's partial
 * reply is only spoken when it asks something (owner 2026-10-11). Bounded to the latest few.
 */
internal object DebugSpokenRuns {
    private val ids = ArrayDeque<String>()

    /** A request id for a new spoken run, already recorded. */
    fun next(): String = DebugRequestIds.next("debug").also { mark(it) }

    @Synchronized
    fun mark(runId: String) {
        ids.addLast(runId)
        while (ids.size > MAX_REMEMBERED) ids.removeFirst()
    }

    @Synchronized
    fun contains(runId: String): Boolean = runId in ids

    private const val MAX_REMEMBERED = 32
}

internal fun failDebugExternalIntake(runId: String, message: String) {
    DebugExecutionEvents.publish(runId, DebugExecutionMessage.Failure(message))
    DebugExecutionEvents.publish(runId, DebugExecutionMessage.Ended)
    DebugExecutionEvents.closeRun(runId)
}
