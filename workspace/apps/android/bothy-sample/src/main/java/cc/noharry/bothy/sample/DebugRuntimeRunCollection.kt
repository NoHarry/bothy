package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
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
import java.util.concurrent.atomic.AtomicInteger

internal fun CoroutineScope.launchDebugRuntime(
    runtime: AgentRuntime,
    request: AgentRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: DebugRunEvidenceRecorder,
    turnRecorder: DebugSessionTurnRecorder,
    onEvent: (AgentEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (DebugRunEvidenceRecorder, DebugSessionTurnRecorder) -> Unit,
    onCompleted: (Job) -> Unit,
    onRawTool: (String, String) -> Unit = { _, _ -> },
): Job {
    val job = launch {
        collectDebugRuntime(
            runtime, request, evidenceRecorder, runEvidenceRecorder, turnRecorder,
            onEvent, onStopped, onFailure, onFinally, onRawTool,
        )
    }
    job.invokeOnCompletion { launch { onCompleted(job) } }
    return job
}

internal suspend fun collectDebugRuntime(
    runtime: AgentRuntime,
    request: AgentRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: DebugRunEvidenceRecorder,
    turnRecorder: DebugSessionTurnRecorder,
    onEvent: (AgentEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (DebugRunEvidenceRecorder, DebugSessionTurnRecorder) -> Unit,
    onRawTool: (String, String) -> Unit = { _, _ -> },
) = coroutineScope {
    val eventProbe = DebugRuntimeEventDispatchProbe()
    val eventDispatcher = DebugRuntimeEventDispatcher(this, onEvent, eventProbe)
    val observers = DebugExecutionObservers(this, request.requestId, eventDispatcher::dispatch, onRawTool) {
        recordExecutionMessage(it, evidenceRecorder, runEvidenceRecorder, turnRecorder)
    }
    var drainUiEvents = true
    try {
        withContext(Dispatchers.IO) {
            val speech = DebugTtsAnnouncer(this, textOutput = { text, final ->
                DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Speech(text, final))
            })
            runtime.run(request).collect { event ->
                DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Runtime(event))
                speech.observe(DebugRuntimeRunScope(request.requestId, request.sessionId), event, DebugTtsConfig()) { true }
            }
        }
    } catch (error: CancellationException) {
        drainUiEvents = false
        DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Stopped)
        onStopped()
    } catch (error: Exception) {
        DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Failure(error.message ?: "Runtime failed"))
        onFailure(error.message)
    } finally {
        withContext(NonCancellable) {
            val observerFailure = observers.closeAndJoin(drainUiEvents)
            try { DebugDiagnosticWrites.flush(request.requestId + ".log") } catch (error: Exception) {
                DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Failure("Diagnostic log write failed"))
                onFailure(error.message)
            }
            val dispatchFailure = eventDispatcher.closeAndJoin(drainUiEvents)
            if (dispatchFailure != null) DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.ObserverIssue("ui"))
            runEvidenceRecorder.recordEventDispatch(eventProbe.snapshot())
            observerFailure?.let { error ->
                runEvidenceRecorder.recordRuntimeFailure()
                turnRecorder.recordRuntimeFailure(error.message)
                DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Failure("Execution observer failed"))
                onFailure(error.message)
            }
            try { onFinally(runEvidenceRecorder, turnRecorder) } finally {
                DebugExecutionEvents.publish(request.requestId, DebugExecutionMessage.Ended)
                DebugExecutionEvents.closeRun(request.requestId)
            }
        }
    }
}

private class DebugRuntimeEventDispatcher(
    scope: CoroutineScope,
    private val onEvent: (AgentEvent) -> Unit,
    private val probe: DebugRuntimeEventDispatchProbe,
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

private class DebugRuntimeEventDispatchProbe(
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

    fun snapshot(): DebugRuntimeEventDispatchEvidence =
        synchronized(lock) {
            DebugRuntimeEventDispatchEvidence(
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
    message: DebugExecutionMessage,
    evidence: McpProgressiveEvidenceRecorder?,
    run: DebugRunEvidenceRecorder,
    turn: DebugSessionTurnRecorder,
) {
    when (message) {
        is DebugExecutionMessage.Runtime -> {
            run.recordEvent(message.event)
            turn.recordEvent(message.event)
            McpProgressiveEvidenceCollector.record(message.event, evidence)
        }
        is DebugExecutionMessage.RawTool -> {
            evidence?.recordRawToolEvent(message.title, message.body)
            run.recordRawToolEvent(message.title, message.body)
            turn.recordRawToolEvent(message.title, message.body)
        }
        DebugExecutionMessage.Stopped -> { run.recordCancelled(); turn.recordCancelled() }
        is DebugExecutionMessage.Failure -> { run.recordRuntimeFailure(); turn.recordRuntimeFailure(message.message) }
        else -> Unit
    }
}
