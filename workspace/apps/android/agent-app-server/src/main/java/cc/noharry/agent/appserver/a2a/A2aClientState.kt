@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.agent.appserver.a2a
import android.content.res.Resources
import androidx.annotation.StringRes
import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.model.describe
import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.A2aOutcome
import cc.noharry.agent.appserver.protocol.A2aTaskStatus
import cc.noharry.agent.appserver.protocol.view.wireLabel
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.appserver.runtime.reduce

/** Speech the server routed to the receiver; the client plays it verbatim. */
@InternalAppServerApi
data class A2aSpeech(val taskId: String, val text: String, val final: Boolean)

internal data class A2aReduction(val state: A2aClientState, val speech: A2aSpeech? = null)

/**
 * Applies one wire event. Routing follows `metadata.channel`; legacy `kind` is used only without a channel.
 * Mirrored events pass [bindTask] false so they never become this client's own task. [texts] words the notices.
 */
internal fun A2aClientState.reduce(
    texts: Resources,
    event: A2aStreamEvent,
    nowEpochMillis: Long,
    startedAtEpochMillis: Long? = runStartedAtEpochMillis,
    bindTask: Boolean = true,
): A2aReduction = when (event) {
    is A2aStreamEvent.Task -> A2aReduction(reduceTask(event, nowEpochMillis, startedAtEpochMillis, bindTask))
    is A2aStreamEvent.Artifact -> reduceArtifact(texts, event.taskId, event.artifact)
    is A2aStreamEvent.Message -> A2aReduction(reduceMessage(event.value))
    A2aStreamEvent.Completed -> A2aReduction(this)
    is A2aStreamEvent.Failed ->
        A2aReduction(notice(texts.getString(R.string.a2a_notice_error, event.error.describe()), error = true))
    is A2aStreamEvent.Incomplete -> A2aReduction(notice(texts.getString(R.string.a2a_notice_incomplete), error = true))
}

private fun A2aClientState.reduceTask(
    event: A2aStreamEvent.Task,
    now: Long,
    startedAt: Long?,
    bindTask: Boolean,
): A2aClientState {
    val task = event.value
    var next = if (bindTask && running && activeTaskId == null) copy(activeTaskId = task.id, contextId = task.contextId ?: contextId) else this
    val text = task.statusMessage?.text().orEmpty()
    if (task.status in TERMINAL_STATES) {
        val elapsed = startedAt?.let { (now - it).coerceAtLeast(0L) }
        next = next.copy(lastOutcome = A2aOutcome(task.status.wire(), elapsed))
        return next.append { A2aClientItem.Terminal(it, task.id, task.status.wire(), text, elapsed) }
    }
    val toolName = event.metadata.string("toolName")
    if (toolName != null) {
        val callId = event.metadata.string("toolCallId") ?: toolName
        val phase = event.metadata.string("phase").orEmpty()
        val existing = next.items.lastOrNull { it is A2aClientItem.Tool && it.taskId == task.id && it.callId == callId } as? A2aClientItem.Tool
        val finishedAt = now.takeIf { phase != TOOL_STARTED }
        return if (existing == null) {
            next.append { A2aClientItem.Tool(it, task.id, callId, toolName, phase, now, finishedAt) }
        } else {
            next.replaceItem(existing, existing.copy(phase = phase, finishedAtEpochMillis = finishedAt))
        }
    }
    if (text.isBlank()) return next
    val last = next.items.lastOrNull()
    if (last is A2aClientItem.Status && last.taskId == task.id && last.text == text) return next
    return next.append { A2aClientItem.Status(it, task.id, text) }
}

private fun A2aClientState.reduceArtifact(texts: Resources, taskId: String, artifact: A2aArtifact): A2aReduction {
    val text = artifact.parts.text()
    val metadata = artifact.metadata
    return when (val channel = metadata.channel()) {
        CHANNEL_SPEECH -> {
            val final = metadata.string("phase") == PHASE_FINAL
            A2aReduction(
                append { A2aClientItem.Speech(it, taskId, text, final) },
                A2aSpeech(taskId, text, final).takeIf { text.isNotBlank() },
            )
        }
        CHANNEL_DISPLAY -> A2aReduction(append { A2aClientItem.Display(it, taskId, text) })
        CHANNEL_QUESTION -> A2aReduction(reduceQuestion(texts, taskId, text, metadata))
        else -> A2aReduction(notice(texts.getString(R.string.a2a_notice_unknown_output, channel ?: "-", text)))
    }
}

private fun A2aClientState.reduceQuestion(
    texts: Resources,
    taskId: String,
    text: String,
    metadata: A2aValue.ObjectValue?,
): A2aClientState {
    val questionId = metadata.string("questionId") ?: return notice(texts.getString(R.string.a2a_notice_question, text))
    val phase = metadata.string("phase").orEmpty()
    val options = (metadata?.values?.get("options") as? A2aValue.ListValue)?.values
        ?.mapNotNull { (it as? A2aValue.StringValue)?.value }.orEmpty()
    val existing = question(questionId)
    if (existing == null) {
        return append { A2aClientItem.Question(it, taskId, questionId, text, options, phase) }
    }
    return replaceItem(existing, existing.copy(phase = phase, options = options.ifEmpty { existing.options }))
}

private fun A2aClientState.reduceMessage(message: A2aMessage): A2aClientState {
    val text = message.parts.text()
    if (text.isBlank()) return this
    val taskId = message.taskId ?: activeTaskId ?: return notice(text)
    return append { A2aClientItem.Display(it, taskId, text) }
}

private fun A2aMessage.text(): String = parts.text()

private fun List<A2aPart>.text(): String = filterIsInstance<A2aPart.Text>().joinToString("\n") { it.text }

private fun A2aValue.ObjectValue?.string(key: String): String? =
    (this?.values?.get(key) as? A2aValue.StringValue)?.value

private fun A2aValue.ObjectValue?.channel(): String? = string("channel") ?: when (string("kind")) {
    LEGACY_KIND_TTS -> CHANNEL_SPEECH
    LEGACY_KIND_RESPONSE -> CHANNEL_DISPLAY
    else -> null
}

internal fun A2aTask.Status.wireLabel(): String = wire().wireLabel()


fun A2aClientError.describe(): String = when (this) {
    is A2aClientError.Rpc -> "RPC $code"
    else -> reason
}

private val TERMINAL_STATES = setOf(
    A2aTask.Status.COMPLETED, A2aTask.Status.CANCELLED, A2aTask.Status.FAILED, A2aTask.Status.INPUT_REQUIRED,
)

@InternalAppServerApi
const val QUESTION_LATE_ANSWERED = "late-answered"
private const val CHANNEL_SPEECH = "speech"
private const val CHANNEL_DISPLAY = "display"
private const val CHANNEL_QUESTION = "question"
private const val LEGACY_KIND_TTS = "tts"
private const val LEGACY_KIND_RESPONSE = "response"
private const val PHASE_FINAL = "final"
private const val TOOL_STARTED = "started"
private const val MAX_CLIENT_ITEMS = 400

@InternalAppServerApi
fun A2aClientState.append(item: (Long) -> A2aClientItem): A2aClientState =
    copy(items = (items + item(nextItemId)).takeLast(MAX_CLIENT_ITEMS), nextItemId = nextItemId + 1)

@InternalAppServerApi
fun A2aClientState.notice(text: String, error: Boolean = false): A2aClientState =
    append { A2aClientItem.Notice(it, text, error) }

internal fun A2aClientState.replaceItem(old: A2aClientItem, new: A2aClientItem): A2aClientState =
    copy(items = items.map { if (it.id == old.id) new else it })

/** Where a message came from, as the client turn shows it. */
@get:StringRes
@InternalAppServerApi
val A2aInputSource.label: Int
    get() = when (this) {
        A2aInputSource.CONSOLE -> R.string.a2a_source_console
        A2aInputSource.OVERLAY -> R.string.a2a_source_overlay
        A2aInputSource.ADB -> R.string.a2a_source_adb
        A2aInputSource.ASR -> R.string.a2a_source_asr
        A2aInputSource.PC -> R.string.a2a_source_pc
        A2aInputSource.EXTERNAL -> R.string.a2a_source_external
    }

/** The runtime's task status as the protocol carries it. */
internal fun A2aTask.Status.wire(): A2aTaskStatus = A2aTaskStatus.valueOf(name)
