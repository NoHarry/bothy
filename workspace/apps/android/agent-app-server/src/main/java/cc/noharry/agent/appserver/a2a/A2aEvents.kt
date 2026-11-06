@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2a
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.protocol.view.displayChoiceReply
import cc.noharry.agent.appserver.runtime.followUpQuestion
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.runtime.shownReply
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

/** Metadata-only projection; thinking, tool arguments/results and transport failures stay local. */
@InternalAppServerApi
fun a2aRuntimeOutput(event: AgentEvent): A2aOutput? = when (event) {
    is AgentEvent.UserInteraction -> when (event.interaction.kind) {
        "QUESTION" -> event.interaction.let { A2aOutput.Question(it.questionId, it.text, it.options, it.state.lowercase()) }
        else -> null
    }
    is AgentEvent.RuntimeBlocked -> event.followUpQuestion()?.let { (_, question) ->
        A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED)
    }
    is AgentEvent.Started -> A2aOutput.Status(modelText("运行已开始", "Run started"), "running")
    is AgentEvent.SearchActivity -> A2aOutput.Status(modelText("正在搜索", "Searching"), "search")
    is AgentEvent.NetworkToolActivity ->
        A2aOutput.Status(modelText("网络工具状态更新", "Network tool update"), "network", event.toolName, event.toolCallId)
    is AgentEvent.RetryAttempted -> A2aOutput.Status(modelText("正在重试，第 ${event.attempt} 次", "Retrying, attempt ${event.attempt}"), "retry")
    is AgentEvent.CompactionApplied -> A2aOutput.Status(modelText("上下文已压缩", "Context compacted"), "compaction")
    is AgentEvent.BudgetWarning -> A2aOutput.Status(modelText("执行预算即将用完", "The run's budget is nearly spent"), "budget-warning")
    is AgentEvent.ResultAvailable -> A2aOutput.Result(event.result.response.shownReply(), a2aTerminalState(event.result))
    else -> null
}

/**
 * A finished run whose reply asks the user something (a partial or blocked outcome with a question or picks)
 * waits for an answer: `input-required`, as the integration guide defines it. Clients showed it as a failure.
 */
internal fun a2aTerminalState(result: RuntimeResult): A2aOutput.State = when {
    result.status == RuntimeRunStatus.WAITING_USER_INPUT -> A2aOutput.State.INPUT_REQUIRED
    RuntimeCompletionSummary.firstPartyPass(result.status, result.completion) -> A2aOutput.State.COMPLETED
    result.status == RuntimeRunStatus.CANCELLED -> A2aOutput.State.CANCELED
    result.status == RuntimeRunStatus.COMPLETED && result.completion.outcome in ASKING_OUTCOMES && result.response.asksDriver() ->
        A2aOutput.State.INPUT_REQUIRED
    else -> A2aOutput.State.FAILED
}

/** The spoken part asks a question, or the reply lists picks. */
internal fun String.asksDriver(): Boolean {
    val shown = shownReply()
    val spoken = shown.substringBefore(PARAGRAPH_BREAK)
    return spoken.contains('？') || spoken.contains('?') || shown.displayChoiceReply().choices.isNotEmpty()
}

private const val PARAGRAPH_BREAK = "\n\n"
private val ASKING_OUTCOMES = setOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)
