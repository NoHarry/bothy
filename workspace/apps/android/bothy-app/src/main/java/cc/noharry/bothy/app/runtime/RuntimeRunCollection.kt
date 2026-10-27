package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.bothy.app.evidence.DiagnosticWrites
import cc.noharry.bothy.app.evidence.RunEvidenceRecorder
import cc.noharry.bothy.app.evidence.RuntimeEventDispatchEvidence
import cc.noharry.bothy.app.mcp.McpProgressiveEvidenceCollector
import cc.noharry.bothy.app.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.bothy.app.session.SessionTurnRecorder
import cc.noharry.bothy.app.voice.tts.TtsAnnouncer
import cc.noharry.bothy.app.voice.tts.TtsConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal fun CoroutineScope.launchRuntime(
    runtime: AgentRuntime,
    request: AgentRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    onEvent: (AgentEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (RunEvidenceRecorder, SessionTurnRecorder) -> Unit,
    onCompleted: (Job) -> Unit,
    onRawTool: (String, String) -> Unit = { _, _ -> },
): Job {
    val job = launch {
        collectRuntime(
            runtime, request, evidenceRecorder, runEvidenceRecorder, turnRecorder,
            onEvent, onStopped, onFailure, onFinally, onRawTool,
        )
    }
    job.invokeOnCompletion { launch { onCompleted(job) } }
    return job
}

internal suspend fun collectRuntime(
    runtime: AgentRuntime,
    request: AgentRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    onEvent: (AgentEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (RunEvidenceRecorder, SessionTurnRecorder) -> Unit,
    onRawTool: (String, String) -> Unit = { _, _ -> },
) = coroutineScope {
    val eventProbe = RuntimeEventDispatchProbe()
    val eventDispatcher = RuntimeEventDispatcher(this, onEvent, eventProbe)
    val observers = ExecutionObservers(this, request.requestId, eventDispatcher::dispatch, onRawTool) {
        recordExecutionMessage(it, evidenceRecorder, runEvidenceRecorder, turnRecorder)
    }
    var drainUiEvents = true
    try {
        withContext(Dispatchers.IO) {
            val speech = TtsAnnouncer(this, textOutput = { text, final ->
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Speech(text, final))
            })
            runtime.run(request).collect { event ->
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Runtime(event))
                speech.observe(RuntimeRunScope(request.requestId, request.sessionId), event, TtsConfig()) { true }
            }
        }
    } catch (error: CancellationException) {
        drainUiEvents = false
        ExecutionEvents.publish(request.requestId, ExecutionMessage.Stopped)
        onStopped()
    } catch (error: Exception) {
        ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure(error.message ?: "Runtime failed"))
        onFailure(error.message)
    } finally {
        withContext(NonCancellable) {
            val observerFailure = observers.closeAndJoin(drainUiEvents)
            try { DiagnosticWrites.flush(request.requestId + ".log") } catch (error: Exception) {
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure("Diagnostic log write failed"))
                onFailure(error.message)
            }
            val dispatchFailure = eventDispatcher.closeAndJoin(drainUiEvents)
            if (dispatchFailure != null) ExecutionEvents.publish(request.requestId, ExecutionMessage.ObserverIssue("ui"))
            runEvidenceRecorder.recordEventDispatch(eventProbe.snapshot())
            observerFailure?.let { error ->
                runEvidenceRecorder.recordRuntimeFailure()
                turnRecorder.recordRuntimeFailure(error.message)
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure("Execution observer failed"))
                onFailure(error.message)
            }
            try { onFinally(runEvidenceRecorder, turnRecorder) } finally {
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Ended)
                ExecutionEvents.closeRun(request.requestId)
            }
        }
    }
}

private class RuntimeEventDispatcher(
    scope: CoroutineScope,
    private val onEvent: (AgentEvent) -> Unit,
    private val probe: RuntimeEventDispatchProbe,
) {
    private val events = Channel<AgentEvent>(512)
    private val pending = AtomicInteger(0)
    @Volatile private var failure: Throwable? = null

    private val job = scope.launch {
        for (event in events) {
            yield()
            dispatchOne(event)
        }
    }

    fun dispatch(event: AgentEvent) {
        failure?.let { throw RuntimeException("Debug event dispatch failed.", it) }
        val startedAt = probe.mark()
        val queueDepth = pending.incrementAndGet()
        val result = events.trySend(event)
        probe.recordEnqueue(startedAt, queueDepth)
        if (result.isFailure) {
            pending.decrementAndGet()
            throw RuntimeException("Debug event dispatch queue closed.", result.exceptionOrNull())
        }
    }

    suspend fun closeAndJoin(drain: Boolean): Throwable? {
        if (drain) {
            events.close()
            job.join()
        } else {
            events.cancel()
            job.join()
        }
        return failure
    }

    private fun dispatchOne(event: AgentEvent) {
        val startedAt = probe.mark()
        try {
            onEvent(event)
        } catch (error: Throwable) {
            failure = error
        } finally {
            pending.decrementAndGet()
            probe.recordHandle(startedAt)
        }
    }
}

private class RuntimeEventDispatchProbe(
    private val nowNanos: () -> Long = { System.nanoTime() },
) {
    private val lock = Any()
    private var eventCount = 0
    private var maxQueueDepth = 0
    private var enqueueDurationNanos = 0L
    private var handleDurationNanos = 0L
    private var maxHandleDurationNanos = 0L

    fun mark(): Long = nowNanos()

    fun recordEnqueue(startedAt: Long, queueDepth: Int) {
        synchronized(lock) {
            eventCount += 1
            maxQueueDepth = maxOf(maxQueueDepth, queueDepth)
            enqueueDurationNanos += nowNanos() - startedAt
        }
    }

    fun recordHandle(startedAt: Long) {
        val elapsed = nowNanos() - startedAt
        synchronized(lock) {
            handleDurationNanos += elapsed
            maxHandleDurationNanos = maxOf(maxHandleDurationNanos, elapsed)
        }
    }

    fun snapshot(): RuntimeEventDispatchEvidence =
        synchronized(lock) {
            RuntimeEventDispatchEvidence(
                eventCount = eventCount,
                maxQueueDepth = maxQueueDepth,
                enqueueDurationMs = enqueueDurationNanos.toMillis(),
                handleDurationMs = handleDurationNanos.toMillis(),
                maxHandleDurationMs = maxHandleDurationNanos.toMillis(),
            )
        }
}

private fun Long.toMillis(): Long = this / 1_000_000L


private fun recordExecutionMessage(
    message: ExecutionMessage,
    evidence: McpProgressiveEvidenceRecorder?,
    run: RunEvidenceRecorder,
    turn: SessionTurnRecorder,
) {
    when (message) {
        is ExecutionMessage.Runtime -> {
            run.recordEvent(message.event)
            turn.recordEvent(message.event)
            McpProgressiveEvidenceCollector.record(message.event, evidence)
        }
        is ExecutionMessage.RawTool -> {
            evidence?.recordRawToolEvent(message.title, message.body)
            run.recordRawToolEvent(message.title, message.body)
            turn.recordRawToolEvent(message.title, message.body)
        }
        ExecutionMessage.Stopped -> { run.recordCancelled(); turn.recordCancelled() }
        is ExecutionMessage.Failure -> { run.recordRuntimeFailure(); turn.recordRuntimeFailure(message.message) }
        else -> Unit
    }
}
