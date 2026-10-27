@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.plans.describe
import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.runtime.reduce
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One A2A client conversation: one active task, steering and answers on the original task, late answers as a new
 * message in the same context.
 *
 * State changes run on [scope] (Main in the app). The direct adapter reads SSE in the collector context,
 * so streams are always collected through [ioDispatcher].
 */
internal class A2aClientSession(
    private val client: A2aClient,
    private val remote: RemoteAgentRef,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSpeech: (A2aSpeech) -> Unit = {},
    private val onState: (A2aClientState) -> Unit = {},
    initial: A2aClientState = A2aClientState(),
) {
    var state: A2aClientState = initial
        private set
    private var activeJob: Job? = null
    private var cancelRequested = false
    /** Contexts of mirrored tasks still running, by task ID; see [mirrorEnd]. */
    private val mirrorContexts = mutableMapOf<String, String>()
    /** Mirrored tasks shown but never spoken: other clients' tasks, which their callers voice themselves. */
    private val silentMirrors = mutableSetOf<String>()

    /** Starts a streamed task; refused while another task of this conversation runs. */
    fun send(text: String, source: A2aInputSource): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (state.running) {
            update { it.notice("上一条仍在执行，已忽略来自${source.label}的输入；可停止后再发。") }
            return false
        }
        start(trimmed, source, A2aClientItem.User.Kind.MESSAGE)
        return true
    }

    /** ASR semantics: cancel the running task, wait for its stream to end, then send. */
    fun replace(text: String, source: A2aInputSource): Job = scope.launch {
        if (state.running) {
            cancel()
            val job = activeJob
            if (withTimeoutOrNull(REPLACE_WAIT_MILLIS) { job?.join() } == null && job?.isActive == true) {
                job.cancel()
                job.join()
            }
        }
        send(text, source)
    }

    /** Extra instructions for the running task's next model request, on the original stream. */
    /** [wireText] is what the task receives when it differs from what is shown (the voice append's framing). */
    fun steer(text: String, source: A2aInputSource, wireText: String = text) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!state.running || state.activeTaskId == null) {
            update { it.notice("没有可补充要求的活跃任务。") }
            return
        }
        update { it.append { id -> A2aClientItem.User(id, trimmed, source, A2aClientItem.User.Kind.STEER) } }
        scope.launch { postInput(wireText.trim(), questionId = null) }
    }

    /** Answers on the active task, or as a new message quoting the question when the task has ended. */
    fun answer(questionId: String, text: String, source: A2aInputSource) {
        val trimmed = text.trim()
        val question = state.question(questionId) ?: return
        if (trimmed.isEmpty()) return
        val active = state.running && question.taskId == state.activeTaskId && question.phase == QUESTION_PENDING
        if (!active) {
            lateAnswer(question, trimmed, source)
            return
        }
        update { it.append { id -> A2aClientItem.User(id, "回答「${question.text}」：$trimmed", source, A2aClientItem.User.Kind.ANSWER) } }
        scope.launch {
            if (!postInput(trimmed, questionId)) return@launch
            // The server refused because the task already ended; never revive it.
            update { it.notice("原任务已结束；等待其收尾后作为同会话新消息发送。") }
            activeJob?.join()
            state.question(questionId)?.let { lateAnswer(it, trimmed, source) }
        }
    }

    /** Cancels the running task; before the task ID arrives the cancel is delivered on the first task event. */
    fun cancel() {
        if (!state.running) return
        val taskId = state.activeTaskId
        if (taskId == null) {
            cancelRequested = true
            update { it.notice("正在等待服务端分配任务 ID，随后取消。") }
            return
        }
        requestCancel(taskId)
    }

    fun newConversation() {
        if (state.running) {
            update { it.notice("请先等待当前任务结束或停止。") }
            return
        }
        // Mirrors keep streaming into the new view, but none may hand its context over: the next message starts fresh.
        mirrorContexts.clear()
        update {
            // Mirrored PC tasks belong to their own contexts and keep streaming into the new view.
            A2aClientState(
                enabled = it.enabled, speakExternal = it.speakExternal, endpoint = it.endpoint, speechReady = it.speechReady,
                mirrors = it.mirrors, nextItemId = it.nextItemId,
            ).notice("已新建会话；下条消息不携带历史。")
        }
    }

    fun updateState(transform: (A2aClientState) -> A2aClientState) = update(transform)

    /**
     * Starts a read-only mirror of an inbound task; it never becomes this conversation's task, and its replies go
     * only to its caller. Other clients' tasks are silent unless 收到 A2A 播报 is on.
     */
    fun mirrorStart(
        taskId: String,
        text: String,
        source: A2aInputSource = A2aInputSource.PC,
        contextId: String? = null,
    ) {
        contextId?.let { mirrorContexts[taskId] = it }
        if (source == A2aInputSource.EXTERNAL && !state.speakExternal) silentMirrors += taskId
        update { current ->
            current.append { id -> A2aClientItem.User(id, text, source, A2aClientItem.User.Kind.MESSAGE) }
                .copy(mirrors = current.mirrors + (taskId to clock()))
        }
    }

    /** A PC debug task's speech is played like this client's own `channel=speech`; an external task's only when allowed. */
    fun mirrorEvent(taskId: String, event: A2aStreamEvent) {
        val startedAt = state.mirrors[taskId] ?: return
        val reduction = state.reduce(event, clock(), startedAt, bindTask = false)
        update { reduction.state }
        if (taskId !in silentMirrors) reduction.speech?.let(onSpeech)
    }

    /**
     * What is on screen is what the device continues: once a mirrored task ends and this client is idle, its next
     * message (a tapped pick, "第二个" by voice, typed text) goes into that task's context, where the offer was
     * made. The original caller is not told; it keeps its own replies.
     */
    fun mirrorEnd(taskId: String) {
        val context = mirrorContexts.remove(taskId)
        silentMirrors -= taskId
        update { current ->
            val ended = current.copy(mirrors = current.mirrors - taskId)
            if (context != null && !current.running) ended.copy(contextId = context) else ended
        }
    }

    private fun start(text: String, source: A2aInputSource, kind: A2aClientItem.User.Kind) {
        cancelRequested = false
        update {
            it.append { id -> A2aClientItem.User(id, text, source, kind) }
                .copy(running = true, activeTaskId = null, runStartedAtEpochMillis = clock(), lastOutcome = null)
        }
        val message = A2aMessage(
            messageId = UUID.randomUUID().toString(),
            role = A2aMessage.Role.USER,
            parts = listOf(A2aPart.Text(text)),
            contextId = state.contextId,
            // This client usually talks to the device's own server; the mark keeps the task from also being mirrored.
            metadata = A2aValue.ObjectValue(mapOf(DEBUG_A2A_OWN_CLIENT_METADATA to A2aValue.StringValue("true"))),
        )
        val job = scope.launch {
            try {
                client.streamMessage(remote, A2aSendMessageRequest(message)).flowOn(ioDispatcher).collect { event ->
                    val reduction = state.reduce(event, clock())
                    update { reduction.state }
                    reduction.speech?.let(onSpeech)
                    val taskId = state.activeTaskId
                    if (cancelRequested && taskId != null) {
                        cancelRequested = false
                        requestCancel(taskId)
                    }
                }
            } finally {
                update {
                    it.copy(
                        running = false,
                        activeTaskId = null,
                        lastTaskId = it.activeTaskId ?: it.lastTaskId,
                        runStartedAtEpochMillis = null,
                    )
                }
            }
        }
        activeJob = job
    }

    /** Returns true only when the server refused the input because the task is no longer active (-32002). */
    private suspend fun postInput(text: String, questionId: String?): Boolean {
        val taskId = state.activeTaskId ?: return questionId != null
        val message = A2aMessage(
            messageId = UUID.randomUUID().toString(),
            role = A2aMessage.Role.USER,
            parts = listOf(A2aPart.Text(text)),
            contextId = state.contextId,
            taskId = taskId,
            metadata = questionId?.let { A2aValue.ObjectValue(mapOf("questionId" to A2aValue.StringValue(it))) },
        )
        return when (val result = withContext(ioDispatcher) { client.sendMessage(remote, A2aSendMessageRequest(message)) }) {
            is A2aResult.Success -> {
                update { it.notice("[输入] 已送达，原任务继续执行。") }
                false
            }
            is A2aResult.Failure -> {
                val ended = questionId != null && (result.error as? A2aClientError.Rpc)?.code == TASK_NOT_ACTIVE
                if (!ended) update { it.notice("[输入失败] ${result.error.describe()}", error = true) }
                ended
            }
        }
    }

    private fun lateAnswer(question: A2aClientItem.Question, text: String, source: A2aInputSource) {
        if (state.running) {
            update { it.notice("上一条仍在执行，请等待或停止后再回答。") }
            return
        }
        update { it.replaceItem(question, question.copy(phase = QUESTION_LATE_ANSWERED)).notice("原任务已结束；回答作为同会话新消息发送。") }
        start("关于你之前的问题「${question.text}」，我的回答是：$text", source, A2aClientItem.User.Kind.MESSAGE)
    }

    private fun requestCancel(taskId: String) {
        scope.launch {
            when (val result = withContext(ioDispatcher) { client.cancelTask(remote, taskId) }) {
                is A2aResult.Success -> update { it.notice("[取消] ${result.value.status.wireLabel()}") }
                is A2aResult.Failure -> update { it.notice("[取消失败] ${result.error.describe()}", error = true) }
            }
        }
    }

    private fun update(transform: (A2aClientState) -> A2aClientState) {
        state = transform(state)
        onState(state)
    }

    private companion object {
        const val TASK_NOT_ACTIVE = -32002
        const val REPLACE_WAIT_MILLIS = 15_000L
    }
}
