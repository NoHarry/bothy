@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.server.A2aOutput

/** Message metadata a PC debug tool sets so client mode mirrors its request; external clients never set it. */
internal const val DEBUG_A2A_MIRROR_METADATA = "debugMirror"

/** Message metadata the device's own client sets; its tasks are already on screen and are never mirrored. */
internal const val DEBUG_A2A_OWN_CLIENT_METADATA = "debugCarClient"

/**
 * Maps host outputs of one inbound task to the wire events its caller receives, with the same channel
 * routing the server writes (`A2aTask.publish`/`finish`), so mirrored and own tasks share one projection.
 */
internal class DebugA2aMirrorProjection(private val taskId: String, private val contextId: String) {
    private var nextArtifact = 0

    fun events(output: A2aOutput): List<A2aStreamEvent> = when (output) {
        is A2aOutput.Status -> listOf(
            A2aStreamEvent.Task(
                task(A2aTask.Status.WORKING, output.text),
                metadata(
                    "kind" to "tool-status", "phase" to output.phase, "toolName" to output.toolName,
                    "toolCallId" to output.toolCallId, "channel" to "status",
                ),
            ),
        )
        is A2aOutput.Speech -> listOf(artifact(output.text, metadata("channel" to "speech", "phase" to if (output.final) "final" else "progress")))
        is A2aOutput.Question -> {
            val base = metadata("channel" to "question", "phase" to output.state, "questionId" to output.questionId)
            val options = "options" to A2aValue.ListValue(output.options.map(A2aValue::StringValue))
            listOf(artifact(output.text, A2aValue.ObjectValue(base.values + options)))
        }
        is A2aOutput.Result -> listOfNotNull(
            output.text.takeIf(String::isNotBlank)?.let { artifact(it, metadata("channel" to "display", "phase" to "final")) },
            A2aStreamEvent.Task(task(output.state.toTaskStatus(), output.text), metadata("channel" to "status")),
        )
    }

    private fun task(status: A2aTask.Status, text: String) = A2aTask(
        id = taskId,
        contextId = contextId,
        status = status,
        statusMessage = A2aMessage(role = A2aMessage.Role.AGENT, parts = listOf(A2aPart.Text(text)), contextId = contextId, taskId = taskId),
    )

    private fun artifact(text: String, metadata: A2aValue.ObjectValue) = A2aStreamEvent.Artifact(
        taskId = taskId,
        contextId = contextId,
        artifact = A2aArtifact("$taskId-mirror-${nextArtifact++}", parts = listOf(A2aPart.Text(text)), metadata = metadata),
    )

}

private fun metadata(vararg pairs: Pair<String, String?>) =
    A2aValue.ObjectValue(pairs.mapNotNull { (key, value) -> value?.let { key to A2aValue.StringValue(it) } }.toMap())

/** Same mapping as the direct decoder: rejected is reported as failed. */
private fun A2aOutput.State.toTaskStatus(): A2aTask.Status = when (this) {
    A2aOutput.State.COMPLETED -> A2aTask.Status.COMPLETED
    A2aOutput.State.CANCELED -> A2aTask.Status.CANCELLED
    A2aOutput.State.INPUT_REQUIRED -> A2aTask.Status.INPUT_REQUIRED
    A2aOutput.State.FAILED, A2aOutput.State.REJECTED -> A2aTask.Status.FAILED
}

/**
 * Where an inbound task came from, for its read-only mirror on this car: a PC debug tool (marked) or any other
 * client. Every task is shown so the device visibly responds to A2A; only this car's own client's tasks are not
 * mirrored, since they are on screen already. The caller is always answered either way.
 */
internal fun debugA2aMirrorSource(metadata: Map<String, String>): DebugA2aInputSource? =
    when {
        metadata[DEBUG_A2A_OWN_CLIENT_METADATA] == "true" -> null
        metadata[DEBUG_A2A_MIRROR_METADATA] == "true" -> DebugA2aInputSource.PC
        else -> DebugA2aInputSource.EXTERNAL
    }

/**
 * Read-only mirror of one inbound task. Called sequentially from the server handler's flow; [post]
 * hands work to the client console's Main scope so the handler never blocks on presentation.
 */
internal class DebugA2aMirror(
    private val taskId: String,
    contextId: String,
    text: String,
    source: DebugA2aInputSource,
    private val post: ((DebugA2aClientSession) -> Unit) -> Unit,
) {
    private val projection = DebugA2aMirrorProjection(taskId, contextId)
    private var finished = false

    init {
        post { it.mirrorStart(taskId, text, source, contextId) }
    }

    fun publish(output: A2aOutput) {
        if (finished) return
        finished = output is A2aOutput.Result
        val events = projection.events(output)
        val end = finished
        post { session ->
            events.forEach { session.mirrorEvent(taskId, it) }
            if (end) session.mirrorEnd(taskId)
        }
    }

    /** A cancelled or broken handler flow never produced a result; the mirror still ends the task. */
    fun close() = publish(A2aOutput.Result("任务已取消或连接中断", A2aOutput.State.CANCELED))
}
