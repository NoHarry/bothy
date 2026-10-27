@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.console.DEBUG_UPDATE_TODO_TOOL_NAME
import cc.noharry.bothy.app.console.status
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class BarrierRuntimeSignals(
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
    val entered: CountDownLatch = CountDownLatch(1),
    val finished: CountDownLatch = CountDownLatch(1),
    val postedSteering: CopyOnWriteArrayList<PostUserInputRequest> = CopyOnWriteArrayList(),
    val postedConfirmations: CopyOnWriteArrayList<ConfirmationDecision> = CopyOnWriteArrayList(),
    val steeringPosted: CountDownLatch = CountDownLatch(1),
    val confirmationPosted: CountDownLatch = CountDownLatch(1),
    val emitToken: Boolean = false,
    val emitTodo: Boolean = false,
    val waitForConfirmation: Boolean = false,
    val ignoreCancel: Boolean = false,
    /** Invoked from inside the runner's finalization work; see the barrier runtime. */
    val onFinalizing: (() -> Unit)? = null,
)

internal class RuntimeMultiSessionFixture(
    val sessionId: String,
    val runId: String,
    emitToken: Boolean = false,
    emitTodo: Boolean = false,
    waitForConfirmation: Boolean = false,
    ignoreCancel: Boolean = false,
    onFinalizing: (() -> Unit)? = null,
) {
    private val signals = BarrierRuntimeSignals(
        emitToken = emitToken,
        emitTodo = emitTodo,
        waitForConfirmation = waitForConfirmation,
        ignoreCancel = ignoreCancel,
        onFinalizing = onFinalizing,
    )
    val release = signals.release
    val entered = signals.entered
    val finished = signals.finished
    val steeringPosted = signals.steeringPosted
    val confirmationPosted = signals.confirmationPosted
    val runtimeCloses = AtomicInteger(0)
    val memoryCloses = AtomicInteger(0)
    val postedSteering = signals.postedSteering
    val postedConfirmations = signals.postedConfirmations
    val request = AgentRequest(
        requestId = runId,
        sessionId = sessionId,
        input = "hello $sessionId",
        scene = "debug-console",
    )
    val runtime = RuntimeMultiSessionBarrierRuntime(request, signals)

    fun resources(): ActiveRunResources =
        ActiveRunResources(
            { runtimeCloses.incrementAndGet() },
            { memoryCloses.incrementAndGet() },
        )
}

internal fun completedRuntimeResult(runId: String): RuntimeResult =
    RuntimeResult(
        runId = runId,
        status = RuntimeRunStatus.COMPLETED,
        response = "done",
        completion = RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.SUCCEEDED),
    )

internal const val DEBUG_TEST_TODO_RESULT_JSON =
    """{"ok":true,"todoCount":2,"todos":[{"content":"导航到加油站","status":"in_progress"},{"content":"播报到达","status":"pending"}]}"""

internal fun multiSessionStartEvents(
    request: AgentRequest,
    emitToken: Boolean,
    waitForConfirmation: Boolean,
    emitTodo: Boolean = false,
): List<AgentEvent> {
    val events = mutableListOf<AgentEvent>(AgentEvent.Started(request.requestId))
    if (emitToken) {
        events += AgentEvent.TokenUsageDelta(
            runId = request.requestId,
            inputTokens = 11,
            outputTokens = 7,
        )
    }
    if (emitTodo) {
        events += AgentEvent.NativeToolCallFinished(
            runId = request.requestId,
            toolName = DEBUG_UPDATE_TODO_TOOL_NAME,
            result = DEBUG_TEST_TODO_RESULT_JSON,
            toolCallId = "todo-1",
        )
    }
    if (waitForConfirmation) {
        events += AgentEvent.ConfirmationRequired(
            PendingConfirmation(
                pendingId = "pending-confirm",
                sessionId = request.sessionId,
                runId = request.requestId,
                expiresAtEpochMillis = Long.MAX_VALUE,
                idempotencyKey = "pending-confirm",
            ),
        )
    }
    return events
}

internal fun multiSessionBarrierFlow(
    request: AgentRequest,
    signals: BarrierRuntimeSignals,
    waiting: AtomicBoolean,
    terminal: AtomicReference<RuntimeResult?>,
): Flow<AgentEvent> = flow {
    signals.entered.countDown()
    multiSessionStartEvents(request, signals.emitToken, signals.waitForConfirmation, signals.emitTodo)
        .forEach { emit(it) }
    if (signals.ignoreCancel) {
        withContext(NonCancellable) { signals.release.await() }
    } else {
        signals.release.await()
    }
    waiting.set(false)
    emit(AgentEvent.ResultAvailable(terminal.get() ?: completedRuntimeResult(request.requestId)))
    emit(AgentEvent.Finished(request.requestId))
    signals.finished.countDown()
}

internal fun pendingConfirmationOrNull(
    waiting: Boolean,
    pendingId: String,
    request: AgentRequest,
): PendingConfirmation? =
    if (waiting) {
        PendingConfirmation(
            pendingId = pendingId,
            sessionId = request.sessionId,
            runId = request.requestId,
            expiresAtEpochMillis = Long.MAX_VALUE,
            idempotencyKey = pendingId,
        )
    } else {
        null
    }

internal fun barrierRunState(
    runId: String,
    request: AgentRequest,
    terminal: RuntimeResult?,
    released: Boolean,
    waiting: Boolean,
): RuntimeRunSnapshot =
    RuntimeRunSnapshot(
        runId = runId,
        sessionId = request.sessionId,
        status = when {
            terminal != null || released -> RuntimeRunStatus.COMPLETED
            waiting -> RuntimeRunStatus.WAITING_CONFIRMATION
            else -> RuntimeRunStatus.RUNNING
        },
        updatedAtEpochMillis = 1L,
        pendingConfirmationId = "pending-confirm".takeIf { waiting },
    )

internal fun completeSessionEventLogHealth(): SessionEventLogHealth =
    SessionEventLogHealth(
        status = SessionEventLogHealthStatus.COMPLETE,
        attemptedCount = 1,
        appendedCount = 1,
        backendDurability = SessionEventLogDurability.DURABLE,
    )
