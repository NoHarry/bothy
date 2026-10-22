@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow

internal abstract class DebugRuntimeMultiSessionRuntimeStub : AgentRuntime {
    override suspend fun cancelPendingConfirmation(
        request: CancelPendingConfirmationRequest,
    ): CancelPendingResult = CancelPendingResult.NotFound

    override suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff? = null

    override suspend fun getOpenConfusion(runId: String): OpenConfusion? = null

    override suspend fun resumeUserInput(request: AgentRequest): RuntimeResult? = null

    override suspend fun resolveRuntimeProtocolRequest(
        decision: ProtocolRequestResolution,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun rejectRuntimeProtocolRequest(
        decision: ProtocolRequestRejection,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun cancelRuntimeProtocolRequest(
        decision: ProtocolRequestCancellation,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun getPendingProtocolRequests(
        sessionId: String?,
        runId: String?,
    ): List<RuntimeProtocolRequest> = emptyList()

    override suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence? = null

    override suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent> = emptyList()
}

internal class DebugRuntimeMultiSessionBarrierRuntime(
    private val request: AgentRequest,
    private val signals: BarrierRuntimeSignals,
) : DebugRuntimeMultiSessionRuntimeStub() {
    private val waiting = AtomicBoolean(signals.waitForConfirmation)
    private val terminal = AtomicReference<RuntimeResult?>(null)

    override fun run(request: AgentRequest): Flow<AgentEvent> =
        debugMultiSessionBarrierFlow(request, signals, waiting, terminal)

    override suspend fun resumeConfirmation(decision: ConfirmationDecision): ConfirmationResumeResult {
        signals.postedConfirmations += decision
        signals.confirmationPosted.countDown()
        terminal.set(completedRuntimeResult(request.requestId))
        waiting.set(false)
        signals.release.complete(Unit)
        return ConfirmationResumeResult.Accepted
    }

    override suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation? =
        pendingConfirmationOrNull(waiting.get(), pendingId, request)

    override suspend fun getRunState(runId: String): RuntimeRunSnapshot? =
        barrierRunState(runId, request, terminal.get(), signals.release.isCompleted, waiting.get())

    override suspend fun getRuntimeResult(runId: String): RuntimeResult? =
        terminal.get() ?: completedRuntimeResult(runId).takeIf { signals.release.isCompleted }

    override suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult {
        signals.postedSteering += request
        signals.steeringPosted.countDown()
        return PostUserInputResult.Accepted
    }

    /**
     * Also the deterministic hook for "something happens mid-finalization": the runner queries
     * log health from inside its finalization work, so [BarrierRuntimeSignals.onFinalizing]
     * fires strictly between finalization start and the panel write.
     */
    override suspend fun getSessionEventLogHealth(
        sessionId: String,
        runId: String,
    ): SessionEventLogHealth {
        signals.onFinalizing?.invoke()
        return completeSessionEventLogHealth()
    }
}
