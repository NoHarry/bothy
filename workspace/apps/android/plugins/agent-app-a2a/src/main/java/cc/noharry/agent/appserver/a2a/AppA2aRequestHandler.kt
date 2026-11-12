package cc.noharry.agent.appserver.a2a
import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.server.*
import cc.noharry.agent.appserver.runtime.ExecutionMessage
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.server.OutsideTurns
import java.util.concurrent.ConcurrentHashMap
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.SteerResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Transport adapter only: subscribe before submitting to the server's turns for another agent ([OutsideTurns]), as the
 * console's are run. [stopAll] stops the tasks still running (the plugin stopping).
 */
internal class AppA2aRequestHandler(context: Context, private val host: A2aHost, private val turns: OutsideTurns) : A2aRequestHandler {
    private val context = context.applicationContext

    /** The tasks running now: task id to their conversation. */
    private val running = ConcurrentHashMap<String, String>()

    override suspend fun postInput(request: A2aRequest, messageId: String, text: String, questionId: String?): A2aInputReceipt =
        when (val result = turns.input("a2a-${request.contextId}", request.taskId, messageId, text, questionId)) {
            SteerResult.ACCEPTED -> A2aInputReceipt(true)
            SteerResult.NOT_RUNNING -> A2aInputReceipt(false, code = "RUN_NOT_ACTIVE")
            SteerResult.QUESTION_NOT_FOUND -> A2aInputReceipt(false, code = "QUESTION_NOT_FOUND")
            SteerResult.EMPTY_INPUT -> A2aInputReceipt(false, code = "EMPTY_INPUT")
            SteerResult.QUEUE_FULL -> A2aInputReceipt(false, code = "QUEUE_FULL")
            SteerResult.REQUEST_ID_CONFLICT -> A2aInputReceipt(false, code = "REQUEST_ID_CONFLICT")
        }

    override fun execute(request: A2aRequest): Flow<A2aOutput> = flow {
        // A command of the host's own goes first, so what it shows is there from the task's first event.
        host.intercept(request.metadata)?.let { ended ->
            emit(ended)
            return@flow
        }
        val sessionId = "a2a-${request.contextId}"
        val subscription = turns.follow(request.taskId)
        // Every caller's task is listed on the device; never the device's own client's, which it already shows.
        val mirror = A2aClientConsole.mirror(context, request)
        var result: A2aOutput.Result? = null
        try {
            if (mirror != null) announceInbound(request.metadata)
            running[request.taskId] = sessionId
            turns.run(request.text, request.taskId, sessionId)
            for (message in subscription.events) {
                if (message == ExecutionMessage.Ended) break
                if (message is ExecutionMessage.Runtime && (message.event as? ThreadEvent.ItemStarted)?.item is cc.noharry.agent.Approval) {
                    result = A2aOutput.Result(
                        modelText("操作需要本地确认，A2A 入口暂不支持批准", "The action needs a confirmation on the device, which A2A cannot give"),
                        A2aOutput.State.REJECTED,
                    )
                    turns.cancel(sessionId, request.taskId)
                    continue
                }
                val output = a2aExecutionOutput(message) ?: continue
                if (output is A2aOutput.Result) {
                    if (result?.state != A2aOutput.State.REJECTED) result = output
                } else {
                    mirror?.publish(output)
                    emit(output)
                }
            }
            val terminal = result ?: A2aOutput.Result(modelText("运行结束但没有终态结果", "The run ended without a result"), A2aOutput.State.FAILED)
            mirror?.publish(terminal)
            emit(terminal)
        } finally {
            mirror?.close()
            subscription.close()
            running.remove(request.taskId)
            withContext(NonCancellable) { turns.cancel(sessionId, request.taskId) }
        }
    }.flowOn(Dispatchers.IO)

    /** The plugin stopped: every task still running is stopped; each ends as cancelled to a caller still listening. */
    fun stopAll() {
        val stopping = running.toMap()
        if (stopping.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch { stopping.forEach { (taskId, sessionId) -> turns.cancel(sessionId, taskId) } }
    }

    /** The host's own doing must not keep the task from running, nor its mirror and subscription from closing. */
    private suspend fun announceInbound(metadata: Map<String, String>) {
        try {
            host.inboundStarted(metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "The host's inboundStarted failed; the task runs", failure)
        }
    }
}

private const val TAG = "AppA2a"

internal fun a2aExecutionOutput(message: ExecutionMessage): A2aOutput? = when (message) {
    is ExecutionMessage.Runtime -> a2aRuntimeOutput(message.event)
    is ExecutionMessage.Speech -> A2aOutput.Speech(message.text, message.final)
    is ExecutionMessage.ToolStatus -> A2aOutput.Status(
        "${message.name} " + when (message.phase) {
            "started" -> modelText("正在调用", "calling")
            "completed" -> modelText("调用完成", "done")
            else -> modelText("调用失败", "failed")
        },
        message.phase, message.name, message.callId,
    )
    is ExecutionMessage.Failure ->
        A2aOutput.Result(modelText("执行服务失败，请查看设备日志", "The run failed; see the device's log"), A2aOutput.State.FAILED)
    ExecutionMessage.Stopped -> A2aOutput.Result(modelText("任务已取消", "Task cancelled"), A2aOutput.State.CANCELED)
    is ExecutionMessage.ObserverIssue ->
        A2aOutput.Status(modelText("展示订阅中断，执行继续", "The display subscription broke; the run goes on"), "observer-failed")
    else -> null
}
