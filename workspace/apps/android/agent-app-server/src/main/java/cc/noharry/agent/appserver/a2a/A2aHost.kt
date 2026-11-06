package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.a2a.server.A2aOutput

/**
 * What the host does with the tasks other A2A clients hand this device: whether its local endpoint runs at all, a
 * command of its own a request may carry, and what it does when such a task starts (show it, say). An app without
 * any keeps [NONE]: no local endpoint, so no other client can hand its agent a task.
 */
interface A2aHost {
    /**
     * Whether the local endpoint (`127.0.0.1:8766/a2a`) runs, so other A2A clients on this device can hand tasks; read
     * once, when the server is installed. The A2A client mode talks to this endpoint, so it stays off without it.
     */
    val localServer: Boolean get() = false

    /**
     * A request to the local endpoint, the device's own client's too, before its task runs: null to run it, or the
     * result to end it with instead (a request that only carries a command of the host's own in [metadata]).
     */
    suspend fun intercept(metadata: Map<String, String>): A2aOutput.Result? = null

    /**
     * Another client's task starts here, listed with this device's A2A tasks; [metadata] is its request's. Called on an
     * IO thread before the task is submitted, which waits for it; a failure is logged and the task runs.
     */
    suspend fun inboundStarted(metadata: Map<String, String>) = Unit

    /** What a task this agent hands another agent names as its source, beside the device's time zone and local time. */
    val contextSource: String get() = "device"

    companion object {
        val NONE: A2aHost = object : A2aHost {}
    }
}
