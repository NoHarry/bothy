package cc.noharry.agent.appserver.a2a
import android.content.Context
import android.util.Log
import cc.noharry.agent.a2a.server.*
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.ExecutionEvents
import cc.noharry.agent.appserver.runtime.ExecutionMessage
import cc.noharry.agent.appserver.runtime.RunIntake
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.PostUserInputResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Transport adapter only: subscribe before submitting to the same service used by the console. */
internal class AppA2aRequestHandler(context: Context) : A2aRequestHandler {
    private val context = context.applicationContext

    override suspend fun postInput(request: A2aRequest, messageId: String, text: String, questionId: String?): A2aInputReceipt =
        when (val result = RuntimeSession.postExternalInput("a2a-${request.contextId}", request.taskId, messageId, text, questionId)) {
            PostUserInputResult.Accepted -> A2aInputReceipt(true)
            PostUserInputResult.Duplicate -> A2aInputReceipt(true, duplicate = true)
            is PostUserInputResult.Rejected -> A2aInputReceipt(false, code = result.code)
            PostUserInputResult.NotFound -> A2aInputReceipt(false, code = "RUN_NOT_FOUND")
            PostUserInputResult.RunNotActive -> A2aInputReceipt(false, code = "RUN_NOT_ACTIVE")
        }

    override fun execute(request: A2aRequest): Flow<A2aOutput> = flow {
        // A command of the host's own goes first, so what it shows is there from the task's first event.
        AppHost.a2a.intercept(request.metadata)?.let { ended ->
            emit(ended)
            return@flow
        }
        val sessionId = "a2a-${request.contextId}"
        val subscription = ExecutionEvents.subscribe(request.taskId)
        // Every caller's task is listed on the device; never the device's own client's, which it already shows.
        val mirror = A2aClientConsole.mirror(context, request)
        var result: A2aOutput.Result? = null
        try {
            if (mirror != null) announceInbound(request.metadata)
            RuntimeSession.submitExternal(context, request.text, RunIntake(request.taskId, sessionId, external = true))
            for (message in subscription.events) {
                if (message == ExecutionMessage.Ended) break
                if (message is ExecutionMessage.Runtime && message.event is AgentEvent.ConfirmationRequired) {
                    result = A2aOutput.Result("操作需要本地确认，A2A 入口暂不支持批准", A2aOutput.State.REJECTED)
                    RuntimeSession.cancelExternal(sessionId, request.taskId)
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
            val terminal = result ?: A2aOutput.Result("运行结束但没有终态结果", A2aOutput.State.FAILED)
            mirror?.publish(terminal)
            emit(terminal)
        } finally {
            mirror?.close()
            subscription.close()
            withContext(NonCancellable) { RuntimeSession.cancelExternal(sessionId, request.taskId) }
        }
    }.flowOn(Dispatchers.IO)

    /** The host's own doing must not keep the task from running, nor its mirror and subscription from closing. */
    private suspend fun announceInbound(metadata: Map<String, String>) {
        try {
            AppHost.a2a.inboundStarted(metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "The host's inboundStarted failed; the task runs", failure)
        }
    }
}

private const val TAG = "AppA2a"

@InternalAppServerApi
fun a2aExecutionOutput(message: ExecutionMessage): A2aOutput? = when (message) {
    is ExecutionMessage.Runtime -> a2aRuntimeOutput(message.event)
    is ExecutionMessage.Speech -> A2aOutput.Speech(message.text, message.final)
    is ExecutionMessage.ToolStatus -> A2aOutput.Status(
        "${message.name} ${when (message.phase) { "started" -> "正在调用"; "completed" -> "调用完成"; else -> "调用失败" }}",
        message.phase, message.name, message.callId,
    )
    is ExecutionMessage.Failure -> A2aOutput.Result("执行服务失败，请查看设备日志", A2aOutput.State.FAILED)
    ExecutionMessage.Stopped -> A2aOutput.Result("任务已取消", A2aOutput.State.CANCELED)
    is ExecutionMessage.ObserverIssue -> A2aOutput.Status("展示订阅中断，执行继续", "observer-failed")
    else -> null
}
