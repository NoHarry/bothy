@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.runtime.eventlog

import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.SessionEvent
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalAgentRuntimeApi::class)
internal class SessionEventLogListeners {
    private val listeners = CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    fun notifyAccepted(event: SessionEvent) {
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }
}
