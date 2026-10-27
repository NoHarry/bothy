@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.app.a2ui.QuestionAnswerMode
import cc.noharry.bothy.app.a2ui.questionCards
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.plans.describe
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.runtime.formatWorkedDuration
import cc.noharry.bothy.app.runtime.reduce

/** Where a client-mode message was typed or injected; shown on the user turn. */
internal enum class A2aInputSource(val label: String) {
    CONSOLE("输入框"),
    OVERLAY("悬浮窗"),
    ADB("adb"),
    ASR("ASR"),
    /** An inbound request from a PC debug tool (`metadata.debugMirror=true`), mirrored read-only. */
    PC("PC 调试"),
    /** An inbound request from any other A2A client, mirrored read-only while "显示外部 A2A 任务" is on. */
    EXTERNAL("外部 A2A"),
}

/** Wire-only client projection: every item comes from what crossed A2A, never from local runtime events. */
internal sealed interface A2aClientItem {
    val id: Long

    data class User(override val id: Long, val text: String, val source: A2aInputSource, val kind: Kind) : A2aClientItem {
        enum class Kind { MESSAGE, STEER, ANSWER }
    }
    data class Status(override val id: Long, val taskId: String, val text: String) : A2aClientItem
    data class Tool(
        override val id: Long,
        val taskId: String,
        val callId: String,
        val name: String,
        val phase: String,
        val startedAtEpochMillis: Long,
        val finishedAtEpochMillis: Long?,
    ) : A2aClientItem
    data class Speech(override val id: Long, val taskId: String, val text: String, val final: Boolean) : A2aClientItem
    data class Display(override val id: Long, val taskId: String, val text: String) : A2aClientItem
    data class Question(
        override val id: Long,
        val taskId: String,
        val questionId: String,
        val text: String,
        val options: List<String>,
        val phase: String,
    ) : A2aClientItem
    data class Terminal(override val id: Long, val taskId: String, val state: A2aTask.Status, val text: String, val elapsedMillis: Long?) : A2aClientItem
    data class Notice(override val id: Long, val text: String, val error: Boolean = false) : A2aClientItem
}

internal data class A2aClientState(
    val enabled: Boolean = false,
    /** Inbound tasks from other A2A clients are also spoken here (they are always shown); PC debug ones always are. */
    val speakExternal: Boolean = false,
    val endpoint: String = DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT,
    val speechReady: Boolean = false,
    val contextId: String? = null,
    val running: Boolean = false,
    val activeTaskId: String? = null,
    val lastTaskId: String? = null,
    val runStartedAtEpochMillis: Long? = null,
    /** Mirrored inbound debug tasks still running, by task ID, with their start time. */
    val mirrors: Map<String, Long> = emptyMap(),
    val lastOutcome: String? = null,
    val items: List<A2aClientItem> = emptyList(),
    val nextItemId: Long = 1,
) {
    /** Questions of the active task, or when idle of the latest task; ended tasks answer late as a new message. */
    val questionCards: List<Pair<A2aClientItem.Question, QuestionAnswerMode>>
        get() {
            val taskId = activeTaskId ?: lastTaskId ?: return emptyList()
            val late = activeTaskId == null
            return items.filterIsInstance<A2aClientItem.Question>().filter { it.taskId == taskId }.map { question ->
                question to when {
                    question.phase == QUESTION_PENDING -> if (late) QuestionAnswerMode.LATE else QuestionAnswerMode.ACTIVE
                    question.phase == QUESTION_EXPIRED && late -> QuestionAnswerMode.LATE
                    else -> QuestionAnswerMode.NONE
                }
            }
        }

    fun question(questionId: String): A2aClientItem.Question? =
        items.lastOrNull { it is A2aClientItem.Question && it.questionId == questionId } as? A2aClientItem.Question

    fun append(item: (Long) -> A2aClientItem): A2aClientState =
        copy(items = (items + item(nextItemId)).takeLast(MAX_CLIENT_ITEMS), nextItemId = nextItemId + 1)

    fun notice(text: String, error: Boolean = false): A2aClientState =
        append { A2aClientItem.Notice(it, text, error) }

    fun replaceItem(old: A2aClientItem, new: A2aClientItem): A2aClientState =
        copy(items = items.map { if (it.id == old.id) new else it })
}

/** Speech the server routed to the receiver; the client plays it verbatim. */
internal data class A2aSpeech(val taskId: String, val text: String, val final: Boolean)

internal data class A2aReduction(val state: A2aClientState, val speech: A2aSpeech? = null)

/**
 * Applies one wire event. Routing follows `metadata.channel`; legacy `kind` is used only without a channel.
 * Mirrored events pass [bindTask] false so they never become this client's own task.
 */
internal fun A2aClientState.reduce(
    event: A2aStreamEvent,
    nowEpochMillis: Long,
    startedAtEpochMillis: Long? = runStartedAtEpochMillis,
    bindTask: Boolean = true,
): A2aReduction = when (event) {
    is A2aStreamEvent.Task -> A2aReduction(reduceTask(event, nowEpochMillis, startedAtEpochMillis, bindTask))
    is A2aStreamEvent.Artifact -> reduceArtifact(event.taskId, event.artifact)
    is A2aStreamEvent.Message -> A2aReduction(reduceMessage(event.value))
    A2aStreamEvent.Completed -> A2aReduction(this)
    is A2aStreamEvent.Failed -> A2aReduction(notice("A2A 错误：${event.error.describe()}", error = true))
    is A2aStreamEvent.Incomplete -> A2aReduction(notice("连接已结束，但未收到任务终态；结果未知，不会自动重试。", error = true))
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
        next = next.copy(lastOutcome = listOfNotNull(task.status.wireLabel(), elapsed?.let(::formatWorkedDuration)).joinToString(" · "))
        return next.append { A2aClientItem.Terminal(it, task.id, task.status, text, elapsed) }
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

private fun A2aClientState.reduceArtifact(taskId: String, artifact: A2aArtifact): A2aReduction {
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
        CHANNEL_QUESTION -> A2aReduction(reduceQuestion(taskId, text, metadata))
        else -> A2aReduction(notice("[未知输出 ${channel ?: "-"}] $text"))
    }
}

private fun A2aClientState.reduceQuestion(taskId: String, text: String, metadata: A2aValue.ObjectValue?): A2aClientState {
    val questionId = metadata.string("questionId") ?: return notice("[问题] $text")
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

internal fun A2aTask.Status.wireLabel(): String = when (this) {
    A2aTask.Status.SUBMITTED -> "submitted"
    A2aTask.Status.WORKING -> "working"
    A2aTask.Status.INPUT_REQUIRED -> "input-required"
    A2aTask.Status.COMPLETED -> "completed"
    A2aTask.Status.CANCELLED -> "canceled"
    A2aTask.Status.FAILED -> "failed"
    A2aTask.Status.UNKNOWN -> "unknown"
}

internal fun A2aClientError.describe(): String = when (this) {
    is A2aClientError.Rpc -> "RPC $code"
    else -> reason
}

private val TERMINAL_STATES = setOf(
    A2aTask.Status.COMPLETED, A2aTask.Status.CANCELLED, A2aTask.Status.FAILED, A2aTask.Status.INPUT_REQUIRED,
)

internal const val DEBUG_A2A_CLIENT_DEFAULT_ENDPOINT = "http://127.0.0.1:8766/a2a"
internal const val QUESTION_PENDING = "pending"
internal const val QUESTION_EXPIRED = "expired"
internal const val QUESTION_LATE_ANSWERED = "late-answered"
private const val CHANNEL_SPEECH = "speech"
private const val CHANNEL_DISPLAY = "display"
private const val CHANNEL_QUESTION = "question"
private const val LEGACY_KIND_TTS = "tts"
private const val LEGACY_KIND_RESPONSE = "response"
private const val PHASE_FINAL = "final"
private const val TOOL_STARTED = "started"
private const val MAX_CLIENT_ITEMS = 400
