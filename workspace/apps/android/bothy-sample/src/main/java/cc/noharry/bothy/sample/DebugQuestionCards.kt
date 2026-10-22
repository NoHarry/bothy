@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.noharry.agent.kernel.api.SessionEventPayload

/** Derived only from canonical interaction records, including after a cold history load. */
internal data class DebugUserQuestion(
    val id: String,
    val runId: String,
    val sessionId: String,
    val title: String,
    val options: List<String>,
    val state: String,
)

internal fun TrajectorySnapshot.userQuestions(): List<DebugUserQuestion> = runGroups.flatMap { group ->
    val questions = linkedMapOf<String, DebugUserQuestion>()
    group.records.sortedBy { it.sequence }.forEach { record ->
        val fact = record.rich?.rawEvent?.payload as? SessionEventPayload.UserInteraction ?: return@forEach
        if (fact.kind == "QUESTION") questions[fact.questionId] = DebugUserQuestion(
            fact.questionId, group.runId, group.sessionId, fact.text, fact.options, fact.state,
        )
    }
    questions.values.toList()
}

/** How a card may be answered: into the running run, as a new turn after the run ended, or not at all. */
internal enum class DebugQuestionAnswerMode { ACTIVE, LATE, NONE }

/**
 * Only the active run's questions, or when idle the latest run's questions, are shown. A question whose run
 * ended without an answer stays answerable as a late reply that starts a new turn quoting the question.
 */
internal fun TrajectorySnapshot.questionCards(activeRunId: String?): List<Pair<DebugUserQuestion, DebugQuestionAnswerMode>> {
    val all = userQuestions()
    val active = all.filter { it.runId == activeRunId }
    if (active.isNotEmpty()) return active.map { it to if (it.state == "PENDING") DebugQuestionAnswerMode.ACTIVE else DebugQuestionAnswerMode.NONE }
    if (activeRunId != null) return emptyList()
    // Only the session's most recent run may still be answered late; once a later turn exists, older cards go away.
    val latestRun = (runGroups.firstOrNull { it.isActive } ?: runGroups.maxByOrNull { it.startedAtEpochMillis ?: Long.MIN_VALUE })?.runId
        ?: return emptyList()
    return all.filter { it.runId == latestRun }.map {
        it to if (it.state == "PENDING" || it.state == "EXPIRED") DebugQuestionAnswerMode.LATE else DebugQuestionAnswerMode.NONE
    }
}

@Composable
internal fun DebugQuestionCards(state: DebugUiState, actions: DebugConsoleActions) {
    val activeRun = state.currentRequest?.requestId.takeIf { state.isRunning }
    val cards = remember(state.trajectory.sessionId, state.trajectory.revision, activeRun) { state.trajectory.questionCards(activeRun) }
    if (cards.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = QUESTION_PANEL_HEIGHT)) {
        items(cards, key = { it.first.id }) { (question, mode) ->
            QuestionCard(question, mode, actions.answerQuestion)
        }
    }
}

@Composable
private fun QuestionCard(question: DebugUserQuestion, mode: DebugQuestionAnswerMode, answer: (DebugUserQuestion, String) -> Unit) {
    var draft by remember(question.id) { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(DebugDimens.spaceXs), shape = androidx.compose.foundation.shape.RoundedCornerShape(DebugDimens.spaceSm)) {
        Column(Modifier.padding(DebugDimens.spaceSm)) {
            Text(question.title)
            if (mode != DebugQuestionAnswerMode.NONE) {
                Text(if (mode == DebugQuestionAnswerMode.ACTIVE) "任务继续执行中，可随时补充回答" else "任务已结束，回答将作为新消息发送")
                question.options.forEach { option -> TextButton(onClick = { answer(question, option) }) { Text(option) } }
                OutlinedTextField(draft, { draft = it }, label = { Text("也可以直接输入回答") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { answer(question, draft.trim()) }, enabled = draft.isNotBlank()) { Text("提交回答") }
            } else Text(when (question.state) {
                "ANSWER_RECEIVED" -> "回答已提交，等待处理"
                "ANSWERED" -> "回答已用于后续处理"
                else -> "当前不可回答"
            })
        }
    }
}

private val QUESTION_PANEL_HEIGHT = 280.dp
