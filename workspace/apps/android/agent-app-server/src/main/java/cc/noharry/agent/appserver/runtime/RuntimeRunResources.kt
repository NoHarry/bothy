@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.trajectory.TrajectoryRepository
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent per-run teardown; a replaced or drained run must never close twice. */
@InternalAppServerApi
class ActiveRunResources(
    private val closeRuntime: () -> Unit,
    private val closeMemorySession: () -> Unit,
    private val closeSubscription: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching(closeSubscription)
        runCatching(closeRuntime)
        runCatching(closeMemorySession)
    }
}

/** A run parked on confirmation is not finished: its resources and registry slot must survive. */
internal suspend fun AgentRuntime.isWaitingForConfirmation(runId: String): Boolean =
    runCatching { getRunState(runId)?.status == RuntimeRunStatus.WAITING_CONFIRMATION }
        .getOrDefault(false)

/** Named-args wrap: a trailing lambda here is closeSubscription, not closeMemorySession. */
internal fun String.toConfirmationStatus(): ConfirmationStatus = when {
    contains("expired", ignoreCase = true) -> ConfirmationStatus.EXPIRED
    contains("consumed", ignoreCase = true) -> ConfirmationStatus.CONSUMED
    contains("missing", ignoreCase = true) -> ConfirmationStatus.FAILED
    contains("failed", ignoreCase = true) -> ConfirmationStatus.FAILED
    else -> ConfirmationStatus.FAILED
}

internal const val CONFIRMATION_REQUIRED_DECISION = "confirmation_required"

internal fun bindTrajectorySubscription(
    repository: TrajectoryRepository?,
    runtime: AgentRuntime,
    sessionId: String,
    resources: ActiveRunResources,
): ActiveRunResources {
    val subscription = repository?.attachWriter(runtime, sessionId)
    return ActiveRunResources(
        closeRuntime = { resources.close() },
        closeMemorySession = {},
        closeSubscription = { subscription?.close() },
    )
}
