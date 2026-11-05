package cc.noharry.bothy.app.console

import cc.noharry.agent.appserver.protocol.client.ServerState

/**
 * The items the person opened or folded, per thread, for as long as the process lives. A screen's own connection
 * keeps them only until the activity is recreated (a rotation, a fold, a language change) or the thread switches; the
 * runtime's transcript used to keep them for the process. Main thread.
 */
internal object ItemFolds {
    private val byThread = mutableMapOf<String, Map<Long, Boolean>>()

    fun set(threadId: String, itemId: Long, folded: Boolean) {
        byThread[threadId] = byThread[threadId].orEmpty() + (itemId to folded)
    }

    /** [state] with its current thread's remembered folds, under what the connection holds itself. */
    fun over(state: ServerState): ServerState {
        val remembered = byThread[state.activeSessionId] ?: return state
        return state.copy(folded = remembered + state.folded)
    }

    internal fun clearForTest() = byThread.clear()
}
