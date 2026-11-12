@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2a

import cc.noharry.agent.InputPart
import cc.noharry.agent.ItemStatus
import cc.noharry.agent.MessagePhase
import cc.noharry.agent.NoticeKind
import cc.noharry.agent.Question
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.TurnError
import cc.noharry.agent.TurnResult
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.runtime.ExecutionMessage
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What another agent's task shows of a run (its A2A output): progress, questions and the result, never tool payloads,
 * reasoning or a failure's diagnostics; a reply that asks the person waits for an answer, a partial answer completes, a
 * blocked one that asks nothing is rejected. In the generic persona's English; the texts are the run's own.
 */
class A2aRuntimeOutputTest {
    @Test
    fun theResultKeepsTheWholeReplyAndOnlyAVerifiedOutcomeCompletes() {
        val response = "深圳北站有4个停车场，推荐东广场北区那个，离进站口最近，就去那儿吗？\n\n" +
            "1. 东广场北区地下停车场（推荐，离进站口最近）\n2. 西广场地下停车场"
        assertEquals(response, (a2aRuntimeOutput(completed(RuntimeCompletionOutcome.SUCCEEDED, response)) as A2aOutput.Result).text)
        listOf(RuntimeCompletionOutcome.UNKNOWN, RuntimeCompletionOutcome.FAILED).forEach { outcome ->
            assertEquals(A2aOutput.State.FAILED, state(outcome, "不能当作成功"))
        }
        assertEquals(A2aOutput.State.COMPLETED, state(RuntimeCompletionOutcome.SUCCEEDED, "好了"))
    }

    @Test
    fun aReplyThatAsksThePersonWaitsForAnAnswerInsteadOfFailing() {
        assertEquals(A2aOutput.State.INPUT_REQUIRED, state(RuntimeCompletionOutcome.PARTIAL, "想怎么调呢？说个数值，或者我给你拖个滑块。"))
        assertEquals(A2aOutput.State.INPUT_REQUIRED, state(RuntimeCompletionOutcome.BLOCKED, "播放模式有三种。\n\n1. 顺序播放\n2. 单曲循环（推荐）"))
        assertEquals(A2aOutput.State.FAILED, state(RuntimeCompletionOutcome.FAILED, "要重试吗？"))
    }

    @Test
    fun aPartialAnswerCompletesAndABlockedOneThatAsksNothingIsRejected() {
        // The route is set and the reply says which stop could not be added: the task ended with its answer.
        val partial = a2aRuntimeOutput(completed(RuntimeCompletionOutcome.PARTIAL, "路线已规划，途经点「面包店」没能加上。")) as A2aOutput.Result
        assertEquals(A2aOutput.Result("路线已规划，途经点「面包店」没能加上。", A2aOutput.State.COMPLETED), partial)
        // Nothing the caller could answer would let it proceed: the agent cannot do it.
        assertEquals(A2aOutput.State.REJECTED, state(RuntimeCompletionOutcome.BLOCKED, "空调服务现在连不上，没法调温度。"))
        // A partial or blocked end without any reply is no answer: failed.
        assertEquals(A2aOutput.State.FAILED, state(RuntimeCompletionOutcome.PARTIAL, " "))
        assertEquals(A2aOutput.State.FAILED, state(RuntimeCompletionOutcome.BLOCKED, ""))
    }

    @Test
    fun aQuestionIsPassedOnAndARunWaitingForItsAnswerAsksForInput() {
        val question = "找到了两个同名地点。一个在广州，一个在深圳。请告诉我选择哪一个。"
        val blocked = ThreadEvent.ItemCompleted(Question("question", "r", question, async = false))
        assertEquals(A2aOutput.Question("question", question, emptyList(), "pending"), a2aRuntimeOutput(blocked))
        assertEquals(A2aOutput.Result(question, A2aOutput.State.INPUT_REQUIRED), a2aRuntimeOutput(waiting(question)))
        val listed = "推荐东广场北区那个，离进站口最近，选它吗？\n1. 东广场北区地下停车场（推荐）\n2. 西广场地下停车场"
        assertEquals(A2aOutput.Result(listed, A2aOutput.State.INPUT_REQUIRED), a2aRuntimeOutput(waiting(listed)))
        // An asynchronous question is passed on as it is asked; the person's answer is never echoed.
        assertTrue(a2aRuntimeOutput(ThreadEvent.ItemStarted(Question("run/q/0", "run", "去哪？", async = true))) is A2aOutput.Question)
        assertNull(a2aRuntimeOutput(ThreadEvent.ItemCompleted(ThreadItem.UserMessage("answer", "run", listOf(InputPart.Text("private answer"))))))
    }

    @Test
    fun thinkingArgumentsAndDetailedFailuresAreNotProjected() {
        assertNull(a2aRuntimeOutput(ThreadEvent.ItemDelta("r-thinking", "private thought")))
        assertNull(a2aRuntimeOutput(ThreadEvent.ItemStarted(ThreadItem.ToolCall("tool", "r", "tool", ToolSource.BACKEND, arguments = "private args"))))
        val failure = a2aRuntimeOutput(ThreadEvent.TurnFailed("r", TurnError("HTTP", "credential-bearing diagnostic", false))) as A2aOutput.Result
        assertEquals(A2aOutput.State.FAILED, failure.state)
        assertFalse(failure.text.contains("credential-bearing diagnostic"))
        val retry = ThreadEvent.ItemCompleted(ThreadItem.Notice("r-retry-2", "r", NoticeKind.RETRY, "Retry 2 after 10 ms"))
        assertEquals("retry", (a2aRuntimeOutput(retry) as A2aOutput.Status).phase)
        // A note between calls is not output; what is said comes as speech.
        assertNull(a2aRuntimeOutput(ThreadEvent.ItemCompleted(ThreadItem.AgentMessage("r-message", "r", "", MessagePhase.COMMENTARY, ItemStatus.COMPLETED))))
    }

    @Test
    fun theRunsSpeechAndTypedToolStatusPassWithoutTheirPayload() {
        assertEquals(A2aOutput.Speech("正在核对目的地信息。", false), a2aExecutionOutput(ExecutionMessage.Speech("正在核对目的地信息。", false)))
        assertEquals(A2aOutput.Speech("地点信息已查到。", true), a2aExecutionOutput(ExecutionMessage.Speech("地点信息已查到。", true)))
        val statuses = listOf("started", "completed", "failed").map { phase ->
            a2aExecutionOutput(ExecutionMessage.ToolStatus("navigate", "c1", phase)) as A2aOutput.Status
        }
        assertEquals(listOf("started", "completed", "failed"), statuses.map { it.phase })
        assertEquals(listOf("navigate calling", "navigate done", "navigate failed"), statuses.map { it.text })
        assertTrue(statuses.all { it.toolName == "navigate" && it.toolCallId == "c1" })
        assertEquals(A2aOutput.State.CANCELED, (a2aExecutionOutput(ExecutionMessage.Stopped) as A2aOutput.Result).state)
        val failed = a2aExecutionOutput(ExecutionMessage.Failure("private-result")) as A2aOutput.Result
        assertEquals(A2aOutput.State.FAILED, failed.state)
        assertFalse(failed.text.contains("private-result"))
    }

    private fun state(outcome: RuntimeCompletionOutcome, response: String) =
        (a2aRuntimeOutput(completed(outcome, response)) as A2aOutput.Result).state

    /** A turn that completed with [outcome], as the server hands it on once it has read the run's result. */
    private fun completed(outcome: RuntimeCompletionOutcome, response: String) = AppServerTesting.completedTurn(
        ThreadEvent.TurnCompleted(TurnResult("r", TurnStatus.COMPLETED, response, emptyList())),
        RuntimeResult("r", RuntimeRunStatus.COMPLETED, response, completion = RuntimeCompletionSummary(outcome)),
    )

    private fun waiting(question: String) =
        ThreadEvent.TurnCompleted(TurnResult("r", TurnStatus.WAITING_FOR_ANSWER, question, emptyList()))
}
