@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.app.a2a
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2ui.ConsoleWidgets
import cc.noharry.bothy.app.a2ui.QuestionAnswerMode
import cc.noharry.bothy.app.a2ui.pickableDisplay
import cc.noharry.bothy.app.a2ui.questionCards
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.PanelBox
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.console.ui.Markdown
import cc.noharry.bothy.app.overlay.PickableReply
import cc.noharry.bothy.app.runtime.formatWorkedDuration

/** Client-mode actions; all of them go through [A2aClientConsole], never the local runtime. */
internal data class A2aClientActions(
    val setEnabled: (Boolean) -> Unit,
    val send: (String) -> Unit,
    val steer: (String) -> Unit,
    val answer: (questionId: String, text: String) -> Unit,
    val cancel: () -> Unit,
    val newConversation: () -> Unit,
    val openOverlay: () -> Unit,
    val setSpeakExternal: (Boolean) -> Unit = {},
)

@Composable
internal fun a2aClientStatusLine(state: A2aClientState): String {
    val run = when {
        state.running || state.mirrors.isNotEmpty() -> stringResource(R.string.a2a_status_running)
        state.lastOutcome != null -> state.lastOutcome
        else -> stringResource(R.string.a2a_status_ready)
    }
    val speech = stringResource(if (state.speechReady) R.string.a2a_tts_speaks else R.string.a2a_tts_not_set_up)
    return stringResource(R.string.a2a_client_status_line, run, state.endpointLabel(), speech)
}

@Composable
internal fun A2aClientPane(
    state: A2aClientState,
    actions: A2aClientActions,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val submit = {
        val text = input.trim()
        if (text.isNotEmpty()) {
            input = ""
            if (state.running) actions.steer(text) else actions.send(text)
        }
    }
    PanelBox(stringResource(R.string.a2a_client_title), modifier) {
        header()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (state.speakExternal) R.string.a2a_client_intro_speaking else R.string.a2a_client_intro_silent,
                    state.endpoint,
                ),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.a2a_speak_received), style = ConsoleType.meta, color = ConsoleColors.text)
            Switch(state.speakExternal, actions.setSpeakExternal, colors = switchColors())
            TextButton(onClick = actions.newConversation, enabled = !state.running) {
                Text(stringResource(R.string.a2a_new_conversation))
            }
            TextButton(onClick = actions.openOverlay) { Text(stringResource(R.string.a2a_floating_card)) }
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        val listState = rememberLazyListState()
        val items = remember(state.items) { state.items.asReversed() }
        LaunchedEffect(state.items.lastOrNull()?.id) {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.a2a_client_empty),
                    style = ConsoleType.bodySmall,
                    color = ConsoleColors.muted,
                )
            }
        } else {
            val displayedTasks = remember(state.items) {
                state.items.filterIsInstance<A2aClientItem.Display>().map { it.taskId }.toSet()
            }
            // The overlay card's open picks; a tap answers the pending question, or goes out as the next message.
            val pickable = remember(state) { state.pickableDisplay() }
            val reply: (String) -> Unit = { text ->
                val question = state.questionCards.lastOrNull { it.second == QuestionAnswerMode.ACTIVE }?.first
                if (question != null) actions.answer(question.questionId, text) else actions.send(text)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                items(items, key = { it.id }) { item ->
                    A2aClientRow(item, item.taskIdOrNull() in displayedTasks, pickable?.takeIf { it.messageId == item.id }, reply)
                }
            }
        }
        A2aQuestionCards(state, actions)
        Spacer(Modifier.height(Dimens.spaceSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = {
                    Text(stringResource(if (state.running) R.string.a2a_steer_label else R.string.a2a_send_label))
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            TextButton(onClick = submit, enabled = input.isNotBlank()) {
                Text(stringResource(if (state.running) R.string.a2a_steer else R.string.a2a_send))
            }
            if (state.running) TextButton(onClick = actions.cancel) { Text(stringResource(R.string.a2a_stop)) }
        }
    }
}

@Composable
private fun A2aClientRow(
    item: A2aClientItem,
    taskHasDisplay: Boolean,
    pickable: PickableReply? = null,
    onReply: (String) -> Unit = {},
) {
    val colors = ConsoleColors
    when (item) {
        is A2aClientItem.User -> A2aUserRow(item)
        is A2aClientItem.Status -> Text("· ${item.text}", style = ConsoleType.bodySmall, color = colors.muted)
        is A2aClientItem.Tool -> {
            val duration = item.finishedAtEpochMillis?.let { " · ${formatWorkedDuration((it - item.startedAtEpochMillis).coerceAtLeast(0L))}" }.orEmpty()
            Text(
                stringResource(R.string.a2a_tool_row, item.name, item.phase.ifBlank { "running" }, duration),
                style = ConsoleType.bodySmall,
                color = if (item.phase == "failed") colors.danger else colors.muted,
            )
        }
        is A2aClientItem.Speech -> Column {
            Text(
                stringResource(R.string.a2a_speech_row, if (item.final) "final" else "progress"),
                style = ConsoleType.meta,
                color = colors.activity,
            )
            Text(item.text, style = ConsoleType.body, color = colors.text)
        }
        is A2aClientItem.Display -> A2aDisplayRow(item, pickable, onReply)
        is A2aClientItem.Question -> Text(
            if (item.options.isEmpty()) {
                stringResource(R.string.a2a_question_row, item.phase, item.text)
            } else {
                val options = item.options.joinToString(" / ")
                stringResource(R.string.a2a_question_row_options, item.phase, item.text, options)
            },
            style = ConsoleType.bodySmall,
            color = colors.accent,
        )
        is A2aClientItem.Terminal -> A2aTerminalRow(item, taskHasDisplay)
        is A2aClientItem.Notice -> Text(
            item.text,
            style = ConsoleType.bodySmall,
            color = if (item.error) colors.danger else colors.tertiary,
        )
    }
}

@Composable
private fun A2aUserRow(item: A2aClientItem.User) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(colors.userBubble, RoundedCornerShape(Dimens.radiusMd))
                .padding(Dimens.spaceSm),
        ) {
            val source = stringResource(item.source.label)
            val header = when (item.kind) {
                A2aClientItem.User.Kind.MESSAGE -> source
                A2aClientItem.User.Kind.STEER -> stringResource(R.string.a2a_user_steer, source)
                A2aClientItem.User.Kind.ANSWER -> stringResource(R.string.a2a_user_answer, source)
            }
            Text(header, style = ConsoleType.meta, color = colors.muted)
            Text(item.text, style = ConsoleType.body, color = colors.text)
        }
    }
}

@Composable
private fun A2aDisplayRow(item: A2aClientItem.Display, pickable: PickableReply?, onReply: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.radiusMd), color = ConsoleColors.elevated) {
        Column(Modifier.padding(Dimens.spaceSm)) {
            Text(stringResource(R.string.a2a_display_row), style = ConsoleType.meta, color = ConsoleColors.muted)
            if (pickable == null) {
                Markdown(item.text)
            } else {
                Markdown(pickable.reply.text)
                Spacer(Modifier.height(Dimens.spaceSm))
                ConsoleWidgets(pickable, onReply)
            }
        }
    }
}

/** The terminal message repeats the display text on success, so it is shown only when no display arrived. */
@Composable
private fun A2aTerminalRow(item: A2aClientItem.Terminal, taskHasDisplay: Boolean) {
    val colors = ConsoleColors
    Column(Modifier.fillMaxWidth()) {
        val elapsed = item.elapsedMillis?.let { " · ${formatWorkedDuration(it)}" }.orEmpty()
        Text(
            stringResource(R.string.a2a_terminal_row, item.state.wireLabel(), elapsed),
            style = ConsoleType.meta,
            fontWeight = FontWeight.SemiBold,
            color = when (item.state) {
                A2aTask.Status.FAILED -> colors.danger
                A2aTask.Status.CANCELLED, A2aTask.Status.INPUT_REQUIRED -> colors.warning
                else -> colors.success
            },
        )
        if (!taskHasDisplay && item.text.isNotBlank()) Text(item.text, style = ConsoleType.bodySmall, color = colors.muted)
    }
}

@Composable
private fun A2aQuestionCards(state: A2aClientState, actions: A2aClientActions) {
    val cards = state.questionCards.filter { it.second != QuestionAnswerMode.NONE }
    if (cards.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        items(cards, key = { it.first.questionId }) { (question, mode) ->
            var draft by remember(question.questionId) { mutableStateOf("") }
            Surface(
                Modifier.fillMaxWidth().padding(Dimens.spaceXs),
                shape = RoundedCornerShape(Dimens.spaceSm),
            ) {
                Column(Modifier.padding(Dimens.spaceSm)) {
                    Text(question.text, style = ConsoleType.body)
                    Text(
                        stringResource(
                            if (mode == QuestionAnswerMode.ACTIVE) {
                                R.string.a2a_question_active
                            } else {
                                R.string.a2a_question_late
                            },
                        ),
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                    question.options.forEach { option ->
                        TextButton(onClick = { actions.answer(question.questionId, option) }) { Text(option) }
                    }
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        label = { Text(stringResource(R.string.a2a_answer_field)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { actions.answer(question.questionId, draft.trim()) },
                        enabled = draft.isNotBlank(),
                    ) { Text(stringResource(R.string.a2a_submit_answer)) }
                }
            }
        }
    }
}

private fun A2aClientItem.taskIdOrNull(): String? = when (this) {
    is A2aClientItem.Terminal -> taskId
    else -> null
}

@Composable
internal fun A2aClientLayout(
    state: A2aClientState,
    actions: A2aClientActions,
    tabs: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    A2aClientPane(
        state = state,
        actions = actions,
        header = { tabs?.let { Column { it(); Spacer(Modifier.height(4.dp)) } } },
        modifier = modifier.fillMaxSize(),
    )
}
