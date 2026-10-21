@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus
import cc.noharry.bothy.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

internal data class DebugRuntimeStartupFailure(val snapshot: DebugActiveRunSnapshot, val message: String)

internal fun createDebugRunRecorders(
    context: Context,
    request: AgentRequest,
    text: String,
    profile: DebugProviderProfile,
    simulated: Boolean,
    streamLabel: String,
    backendLabel: String,
): DebugRuntimeRunRecorders = DebugRuntimeRunRecorders(
    evidenceRecorder = if (simulated) McpProgressiveEvidenceRecorder(context, request.requestId) else null,
    runEvidenceRecorder = debugRunEvidenceRecorder(context, request, profile.displayLabel, streamLabel, backendLabel, text),
    turnRecorder = DebugSessionTurnRecorder(request, text),
)

/** Construction happens before registry ownership; a failed constructor must not kill the UI scope. */
internal suspend fun constructDebugRuntime(
    runScope: DebugRuntimeRunScope,
    recorders: DebugRuntimeRunRecorders,
    coordinator: DebugSessionCoordinator,
    startedAt: Long?,
    onFailure: (DebugRuntimeStartupFailure) -> Unit,
    onCancelled: () -> Unit,
    build: () -> AgentRuntime,
): AgentRuntime? = try {
    buildDebugRuntimeOffMain(build)
} catch (error: CancellationException) {
    onCancelled()
    throw error
} catch (error: Exception) {
    val failure = withContext(Dispatchers.IO) { recordDebugStartupFailure(runScope, recorders, coordinator, startedAt, error) }
    onFailure(failure)
    null
}

/** Own the constructor result across the cancellable dispatcher hand-off, including late cancellation. */
internal suspend fun buildDebugRuntimeOffMain(build: () -> AgentRuntime): AgentRuntime {
    var constructed: AgentRuntime? = null
    try {
        return withContext(Dispatchers.IO) { build().also { constructed = it } }
    } catch (error: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { constructed?.close() }
        throw error
    }
}

private fun recordDebugStartupFailure(
    runScope: DebugRuntimeRunScope,
    recorders: DebugRuntimeRunRecorders,
    coordinator: DebugSessionCoordinator,
    startedAt: Long?,
    error: Exception,
): DebugRuntimeStartupFailure {
    val message = "Runtime initialization failed: ${redactDebugToolPayload(error.message ?: error.javaClass.simpleName)}"
    val now = System.currentTimeMillis()
    val summary = formatRunSummary(now - (startedAt ?: now), DebugRunOutcome.FAILED)
    recorders.runEvidenceRecorder.recordRuntimeFailure()
    recorders.turnRecorder.recordRuntimeFailure(message)
    // No Kernel run or EventLog writer exists yet: never synthesize RUN_FINISHED or COMPLETE.
    runCatching { recorders.runEvidenceRecorder.write() }
    runCatching { coordinator.appendTurn(recorders.turnRecorder.snapshot()) }
    return DebugRuntimeStartupFailure(
        snapshot = DebugActiveRunSnapshot(
            sessionId = runScope.sessionId, runId = runScope.requestId, requestId = runScope.requestId,
            isActive = false, startedAtEpochMillis = null, lastRunSummary = summary,
            lifecycleStatus = RuntimeRunStatus.FAILED.name, completionOutcome = RuntimeCompletionOutcome.UNKNOWN.name,
            logHealth = SessionEventLogHealthStatus.UNKNOWN.name,
        ),
        message = message,
    )
}
