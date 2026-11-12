package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.*
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.runtime.BLOCKED_ANSWER_CODE
import cc.noharry.agent.appserver.runtime.modelText

/** A2A exposes public progress and questions, never tool payloads or reasoning. */
internal fun a2aRuntimeOutput(event: ThreadEvent): A2aOutput? = when (event) {
    is ThreadEvent.TurnStarted -> A2aOutput.Status(modelText("运行已开始", "Run started"), "running")
    is ThreadEvent.ItemStarted -> when (val item = event.item) {
        is Question -> A2aOutput.Question(item.id, item.text, item.choices, "pending")
        is ThreadItem.WebSearch -> A2aOutput.Status(modelText("正在搜索", "Searching"), "search")
        else -> null
    }
    is ThreadEvent.ItemUpdated -> when (val item = event.item) {
        is Question -> questionOutput(item)
        is ThreadItem.ToolCall -> item.network?.let {
            A2aOutput.Status(modelText("网络工具状态更新", "Network tool update"), "network", item.tool, item.id)
        }
        else -> null
    }
    is ThreadEvent.ItemCompleted -> when (val item = event.item) {
        is Question -> questionOutput(item)
        is ThreadItem.Notice -> noticeOutput(item)
        else -> null
    }
    is ThreadEvent.TurnCompleted -> A2aOutput.Result(event.result.finalResponse.trim(), a2aTerminalState(event.result))
    is ThreadEvent.TurnFailed -> A2aOutput.Result(modelText("执行失败，请查看设备日志", "The run failed; see the device log"), A2aOutput.State.FAILED)
    else -> null
}

/** In the app's own words, not the runtime's English; a workspace change is not progress a caller needs. */
private fun noticeOutput(notice: ThreadItem.Notice): A2aOutput.Status? = when (notice.kind) {
    NoticeKind.RETRY -> notice.details.attempt?.let { modelText("正在重试，第 $it 次", "Retrying, attempt $it") }
        ?.let { A2aOutput.Status(it, "retry") } ?: A2aOutput.Status(modelText("正在重试", "Retrying"), "retry")
    NoticeKind.COMPACTION -> A2aOutput.Status(modelText("上下文已压缩", "Context compacted"), "compaction")
    NoticeKind.BUDGET_LOW -> A2aOutput.Status(modelText("执行预算即将用完", "The run's budget is nearly spent"), "budget-warning")
    NoticeKind.WORKSPACE_CHANGED -> null
}

private fun questionOutput(question: Question): A2aOutput.Question = A2aOutput.Question(
    question.id, question.text, question.choices, when (question.status) {
        ItemStatus.IN_PROGRESS -> "pending"
        ItemStatus.COMPLETED -> if (question.async) "answered" else "pending"
        ItemStatus.DECLINED, ItemStatus.FAILED -> "expired"
    },
)

/**
 * A turn's end as A2A names it. An answer is `completed`, a partial one too: the task ended and its text says what was
 * not done. A reply that asks the caller something, a partial or blocked one among them, is `input-required`. A blocked
 * answer that asks nothing is `rejected`: the agent has found it cannot proceed, which is A2A's rejected, and nothing the
 * caller answers would change that. A turn that failed (no answer, a broken tool loop, a required action left undone) is
 * `failed`.
 */
internal fun a2aTerminalState(result: TurnResult): A2aOutput.State = when (result.status) {
    TurnStatus.WAITING_FOR_ANSWER -> A2aOutput.State.INPUT_REQUIRED
    TurnStatus.COMPLETED -> if (result.error?.code == BLOCKED_ANSWER_CODE) A2aOutput.State.REJECTED else A2aOutput.State.COMPLETED
    TurnStatus.INTERRUPTED -> A2aOutput.State.CANCELED
    TurnStatus.FAILED -> A2aOutput.State.FAILED
}

