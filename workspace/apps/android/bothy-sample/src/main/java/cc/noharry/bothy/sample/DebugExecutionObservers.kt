package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent
import kotlinx.coroutines.*

/** Independent subscriber jobs survive producer cancellation long enough to drain required logs. */
internal class DebugExecutionObservers(
    scope: CoroutineScope,
    private val runId: String,
    onEvent: (AgentEvent) -> Unit,
    onRawTool: (String, String) -> Unit,
    record: (DebugExecutionMessage) -> Unit,
) {
    private val workers = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val logs = DebugExecutionEvents.subscribe(runId, required = true)
    private val ui = DebugExecutionEvents.subscribe(runId)
    @Volatile private var failure: Throwable? = null
    private val logJob = workers.launch(Dispatchers.IO) {
        try { for (message in logs.events) record(message) } catch (error: Exception) { failure = error }
    }
    private val uiJob = workers.launch {
        try {
            for (message in ui.events) when (message) {
                is DebugExecutionMessage.Runtime -> onEvent(message.event)
                is DebugExecutionMessage.RawTool -> onRawTool(message.title, message.body)
                else -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugExecutionEvents.publish(runId, DebugExecutionMessage.ObserverIssue("ui"))
        }
    }

    suspend fun closeAndJoin(drainUi: Boolean): Throwable? {
        logs.finish()
        if (drainUi) ui.finish() else ui.close()
        try {
            logJob.join()
            uiJob.join()
            return failure
        } finally {
            logs.close()
            ui.close()
            workers.cancel()
        }
    }
}
