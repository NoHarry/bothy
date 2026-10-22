@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.a2a.server.A2aOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugUserQuestionsTest {
    @Test fun coldCanonicalRecordsRecoverLatestQuestionStateAndKeepSessionsBound() {
        val pending = UserInteraction("QUESTION", "PENDING", questionId = "run/q/0", text = "去哪？", options = listOf("苏州"))
        val answered = pending.copy(state = "ANSWERED", inputRequestId = "answer")
        val records = listOf(pending, answered).mapIndexed { index, payload ->
            sessionEvent(eventId = "q-$index", sequence = index + 1L, type = SessionEventType.USER_INTERACTION)
                .copy(payload = payload).toTrajectoryRecord()
        }
        val snapshot = TrajectorySnapshot("s1", runGroups = listOf(
            TrajectoryRunGroup(sessionId = "s1", runId = "run-1", requestId = "req-1", records = records),
        ))
        val question = snapshot.userQuestions().single()
        assertEquals("ANSWERED", question.state)
        assertEquals("s1", question.sessionId)
        assertEquals(listOf("苏州"), question.options)
        assertEquals("run/q/0", question.id)
    }

    @Test fun cardsShowOnlyTheActiveRunOrTheLatestRunAndKeepEndedRunsAnswerableAsLateReplies() {
        fun group(runId: String, state: String?, startedAt: Long) = TrajectoryRunGroup(sessionId = "s1", runId = runId, startedAtEpochMillis = startedAt, records = listOfNotNull(
            state?.let {
                sessionEvent(eventId = "$runId-q", sequence = 1L, type = SessionEventType.USER_INTERACTION)
                    .copy(payload = UserInteraction("QUESTION", it, questionId = "$runId/q/0", text = "去哪？")).toTrajectoryRecord()
            },
        ))
        val snapshot = TrajectorySnapshot("s1", runGroups = listOf(group("run-2", "PENDING", 2L), group("run-1", "EXPIRED", 1L)))
        assertEquals(listOf("run-2/q/0" to DebugQuestionAnswerMode.ACTIVE), snapshot.questionCards("run-2").map { it.first.id to it.second })
        assertEquals(listOf("run-2/q/0" to DebugQuestionAnswerMode.LATE), snapshot.questionCards(null).map { it.first.id to it.second })
        assertTrue(snapshot.questionCards("run-3").isEmpty())
        val answered = TrajectorySnapshot("s1", runGroups = listOf(group("run-2", "ANSWERED", 2L), group("run-1", "EXPIRED", 1L)))
        assertEquals(listOf(DebugQuestionAnswerMode.NONE), answered.questionCards(null).map { it.second })
        // A later turn without questions (for example the late reply itself) retires the older run's cards.
        val replied = TrajectorySnapshot("s1", runGroups = listOf(group("run-3", null, 3L), group("run-2", "EXPIRED", 2L)))
        assertTrue(replied.questionCards(null).isEmpty())
        val late = lateAnswerText(DebugUserQuestion("run-2/q/0", "run-2", "s1", "去哪？", emptyList(), "EXPIRED"), "苏州")
        assertTrue(late.contains("去哪？") && late.endsWith("苏州"))
        assertEquals(DebugAnswerRoute.Late, debugAnswerRoute(cc.noharry.agent.kernel.api.PostUserInputResult.RunNotActive, selected = true, running = false, ownsRun = false))
        assertEquals(DebugAnswerRoute.Failed("任务已结束且当前会话正在执行其他任务"), debugAnswerRoute(cc.noharry.agent.kernel.api.PostUserInputResult.NotFound, selected = true, running = true, ownsRun = false))
        assertEquals(DebugAnswerRoute.Silent, debugAnswerRoute(cc.noharry.agent.kernel.api.PostUserInputResult.RunNotActive, selected = false, running = false, ownsRun = false))
        assertEquals(DebugAnswerRoute.Delivered, debugAnswerRoute(cc.noharry.agent.kernel.api.PostUserInputResult.Duplicate, selected = true, running = true, ownsRun = true))
        assertEquals(DebugAnswerRoute.Failed("QUESTION_NOT_FOUND"), debugAnswerRoute(cc.noharry.agent.kernel.api.PostUserInputResult.Rejected("QUESTION_NOT_FOUND"), selected = true, running = true, ownsRun = true))
    }

    @Test fun asyncQuestionsSpeakOncePerIdWithoutFinalizingTheOriginalTask() = runBlocking {
        val spoken = mutableListOf<Pair<String, Boolean>>()
        val speaker = DebugTtsAnnouncer(this, textOutput = { text, final -> spoken += text to final })
        val scope = DebugRuntimeRunScope("run", "session")
        val question = UserInteraction("QUESTION", "PENDING", questionId = "run/q/0", text = "去哪？")
        val event = AgentEvent.UserInteraction("run", question)
        repeat(2) { speaker.observe(scope, event, DebugTtsConfig()) { true } }
        speaker.observe(scope, event.copy(interaction = question.copy(questionId = "run/q/1")), DebugTtsConfig()) { true }
        assertEquals(listOf("去哪？" to false, "去哪？" to false), spoken)
        assertTrue(a2aRuntimeOutput(event) is A2aOutput.Question)
        assertNull(a2aRuntimeOutput(event.copy(interaction = UserInteraction("INPUT", "RECEIVED", text = "private answer"))))
        speaker.stop()
    }
}
