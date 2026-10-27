package cc.noharry.bothy.app.a2a
import android.content.Context
import cc.noharry.agent.a2a.server.*
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.bothy.app.overlay.OverlayCommand
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.overlay.isOverlayControlOnly
import cc.noharry.bothy.app.overlay.overlayCommand
import cc.noharry.bothy.app.runtime.ExecutionEvents
import cc.noharry.bothy.app.runtime.ExecutionMessage
import cc.noharry.bothy.app.runtime.RunIntake
import cc.noharry.bothy.app.runtime.RuntimeSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Transport adapter only: subscribe before submitting to the same service used by the console. */
internal class BothyA2aRequestHandler(context: Context) : A2aRequestHandler {
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
        // The overlay pops up before the task, so its mirror is seen from the first event.
        val overlay = applyOverlayCommand(request)
        // A control-only message never runs its placeholder text, even when its command is missing or unknown.
        if (isOverlayControlOnly(request.metadata)) {
            emit(overlay ?: A2aOutput.Result("carOverlayOnly 需要同时设置 carOverlay=show 或 hide", A2aOutput.State.REJECTED))
            return@flow
        }
        val sessionId = "a2a-${request.contextId}"
        val subscription = ExecutionEvents.subscribe(request.taskId)
        // Every caller's task is mirrored onto the device; never the device's own client's, which it already shows.
        val mirror = A2aClientConsole.mirror(context, request)
        // Every other client's task brings the card up, so the device visibly responds; an explicit hide wins.
        if (mirror != null && overlay == null) OverlayControl.apply(context, OverlayCommand.SHOW)
        var result: A2aOutput.Result? = null
        try {
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

    /**
     * Applies `carOverlay=show|hide` for any caller: the device is meant to respond to A2A without its console being
     * opened. While the full-screen console is in front a `show` waits until it leaves. Returns how a control-only
     * message ends, or null when there is no command.
     */
    private fun applyOverlayCommand(request: A2aRequest): A2aOutput.Result? {
        val command = overlayCommand(request.metadata) ?: return null
        val failure = OverlayControl.apply(context, command)
        return if (failure == null) {
            A2aOutput.Result(command.done, A2aOutput.State.COMPLETED)
        } else {
            A2aOutput.Result(failure, A2aOutput.State.FAILED)
        }
    }
}

internal fun a2aExecutionOutput(message: ExecutionMessage): A2aOutput? = when (message) {
    is ExecutionMessage.Runtime -> a2aRuntimeOutput(message.event)
    is ExecutionMessage.Speech -> A2aOutput.Speech(message.text, message.final)
    is ExecutionMessage.ToolStatus -> A2aOutput.Status(
        "${message.name} ${when (message.phase) { "started" -> "正在调用"; "completed" -> "调用完成"; else -> "调用失败" }}",
        message.phase, message.name, message.callId,
    )
    is ExecutionMessage.Failure -> A2aOutput.Result("执行服务失败，请检查 sample 日志", A2aOutput.State.FAILED)
    ExecutionMessage.Stopped -> A2aOutput.Result("任务已取消", A2aOutput.State.CANCELED)
    is ExecutionMessage.ObserverIssue -> A2aOutput.Status("展示订阅中断，执行继续", "observer-failed")
    else -> null
}
