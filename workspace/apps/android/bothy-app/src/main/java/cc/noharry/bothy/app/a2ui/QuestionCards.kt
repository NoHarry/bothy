package cc.noharry.bothy.app.a2ui
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.QuestionAnswerMode
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.questionCards
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.Dimens

@Composable
internal fun QuestionCards(state: ServerState, actions: ConsoleActions) {
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
private fun QuestionCard(question: UserQuestion, mode: QuestionAnswerMode, answer: (UserQuestion, String) -> Unit) {
    var draft by remember(question.id) { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(Dimens.spaceXs), shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.spaceSm)) {
        Column(Modifier.padding(Dimens.spaceSm)) {
            Text(question.title)
            if (mode != QuestionAnswerMode.NONE) {
                val active = mode == QuestionAnswerMode.ACTIVE
                Text(stringResource(if (active) R.string.a2ui_question_running else R.string.a2ui_question_ended))
                question.options.forEach { option -> TextButton(onClick = { answer(question, option) }) { Text(option) } }
                val typeAnswer = stringResource(R.string.a2ui_question_type_answer)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(typeAnswer) })
                TextButton(onClick = { answer(question, draft.trim()) }, enabled = draft.isNotBlank()) {
                    Text(stringResource(R.string.a2ui_question_submit))
                }
            } else Text(stringResource(when (question.state) {
                "ANSWER_RECEIVED" -> R.string.a2ui_question_answer_received
                "ANSWERED" -> R.string.a2ui_question_answered
                else -> R.string.a2ui_question_closed
            }))
        }
    }
}

private val QUESTION_PANEL_HEIGHT = 280.dp
