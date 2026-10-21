package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Per-run asynchronous projections. The Kernel SessionEventLog remains the canonical content ledger. */
internal sealed interface DebugExecutionMessage {
    data class Runtime(val event: AgentEvent) : DebugExecutionMessage
    data class RawTool(val title: String, val body: String) : DebugExecutionMessage
    data class ToolStatus(val name: String, val callId: String, val phase: String) : DebugExecutionMessage
    data class Speech(val text: String, val final: Boolean) : DebugExecutionMessage
    data class Failure(val message: String) : DebugExecutionMessage
    data class ObserverIssue(val observer: String) : DebugExecutionMessage
    data object Stopped : DebugExecutionMessage
    data object Ended : DebugExecutionMessage
}

internal class DebugExecutionSubscription(
    private val channel: Channel<DebugExecutionMessage>,
    private val unsubscribe: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val detached = AtomicBoolean()
    val events: ReceiveChannel<DebugExecutionMessage> get() = channel
    fun finish() {
        detach()
        channel.close()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detach()
            channel.cancel()
        }
    }

    private fun detach() {
        if (detached.compareAndSet(false, true)) unsubscribe()
    }
}

/** Shared process-wide fan-out; callbacks never execute on the model/tool producer thread. */
internal object DebugExecutionEvents {
    private data class Subscriber(val channel: Channel<DebugExecutionMessage>, val required: Boolean)
    private val lock = Any()
    private val topics = mutableMapOf<String, MutableList<Subscriber>>()

    fun subscribe(runId: String, required: Boolean = false, capacity: Int = DEFAULT_CAPACITY): DebugExecutionSubscription {
        val subscriber = Subscriber(Channel(capacity), required)
        synchronized(lock) { topics.getOrPut(runId) { mutableListOf() }.add(subscriber) }
        return DebugExecutionSubscription(subscriber.channel) {
            synchronized(lock) {
                topics[runId]?.let { list -> if (list.remove(subscriber) && list.isEmpty()) topics.remove(runId) }
            }
        }
    }

    fun publish(runId: String, message: DebugExecutionMessage) {
        synchronized(lock) {
            var requiredFailure: IllegalStateException? = null
            val subscribers = topics[runId] ?: return
            val iterator = subscribers.iterator()
            while (iterator.hasNext()) {
                val subscriber = iterator.next()
                if (subscriber.channel.trySend(message).isSuccess) continue
                val failure = IllegalStateException("Execution subscriber overflow or closed: $runId")
                subscriber.channel.close(failure)
                iterator.remove()
                if (subscriber.required) requiredFailure = failure
            }
            if (subscribers.isEmpty()) topics.remove(runId)
            requiredFailure?.let { throw it }
        }
    }

    fun closeRun(runId: String) {
        synchronized(lock) { topics.remove(runId)?.forEach { it.channel.close() } }
    }

    private const val DEFAULT_CAPACITY = 512
}
