@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

/** Metadata-only projection; thinking, tool arguments/results and transport failures stay local. */
internal fun a2aRuntimeOutput(event: AgentEvent): A2aOutput? = when (event) {
    is AgentEvent.UserInteraction -> when (event.interaction.kind) {
        "QUESTION" -> event.interaction.let { A2aOutput.Question(it.questionId, it.text, it.options, it.state.lowercase()) }
        else -> null
    }
    is AgentEvent.RuntimeBlocked -> event.followUpQuestion()?.let { (_, question) ->
        A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED)
    }
    is AgentEvent.Started -> A2aOutput.Status("运行已开始", "running")
    is AgentEvent.SearchActivity -> A2aOutput.Status("正在搜索", "search")
    is AgentEvent.NetworkToolActivity -> A2aOutput.Status("网络工具状态更新", "network", event.toolName, event.toolCallId)
    is AgentEvent.RetryAttempted -> A2aOutput.Status("正在重试，第 ${event.attempt} 次", "retry")
    is AgentEvent.CompactionApplied -> A2aOutput.Status("上下文已压缩", "compaction")
    is AgentEvent.BudgetWarning -> A2aOutput.Status("执行预算即将用完", "budget-warning")
    is AgentEvent.ResultAvailable -> A2aOutput.Result(event.result.response.displayReplyText(), a2aTerminalState(event.result))
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
    val shown = displayReplyText()
    val spoken = shown.substringBefore(PARAGRAPH_BREAK)
    return spoken.contains('？') || spoken.contains('?') || shown.displayChoiceReply().choices.isNotEmpty()
}

private const val PARAGRAPH_BREAK = "\n\n"
private val ASKING_OUTCOMES = setOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)
