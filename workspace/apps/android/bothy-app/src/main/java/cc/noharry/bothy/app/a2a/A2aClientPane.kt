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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
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

internal fun a2aClientStatusLine(state: A2aClientState): String {
    val run = when {
        state.running || state.mirrors.isNotEmpty() -> "Running"
        state.lastOutcome != null -> state.lastOutcome
        else -> "Ready"
    }
    return "$run · A2A ${state.endpointLabel()} · ${if (state.speechReady) "TTS 播报" else "TTS 未配置，仅显示"}"
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
    PanelBox("A2A 客户端", modifier) {
        header()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "经 A2A 发送到 ${state.endpoint}；其他客户端发来的 A2A 任务也在这里和悬浮窗显示，回复仍发回原客户端。" +
                    if (state.speakExternal) "收到的任务也会播报。" else "收到的任务只显示不播报（本机输入和 PC 调试照常播报）。",
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
                modifier = Modifier.weight(1f),
            )
            Text("收到 A2A 播报", style = ConsoleType.meta, color = ConsoleColors.text)
            Switch(state.speakExternal, actions.setSpeakExternal, colors = switchColors())
            TextButton(onClick = actions.newConversation, enabled = !state.running) { Text("新对话") }
            TextButton(onClick = actions.openOverlay) { Text("悬浮窗") }
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
                    "这里就是一个 A2A 客户端：输入框、悬浮窗、adb 和 ASR 的文字都会经 A2A 发出。",
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
                label = { Text(if (state.running) "补充要求（原任务继续执行）" else "经 A2A 发送") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            TextButton(onClick = submit, enabled = input.isNotBlank()) { Text(if (state.running) "补充" else "发送") }
            if (state.running) TextButton(onClick = actions.cancel) { Text("停止") }
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
                "工具 ${item.name} · ${item.phase.ifBlank { "running" }}$duration",
                style = ConsoleType.bodySmall,
                color = if (item.phase == "failed") colors.danger else colors.muted,
            )
        }
        is A2aClientItem.Speech -> Column {
            Text("播报 · ${if (item.final) "final" else "progress"}", style = ConsoleType.meta, color = colors.activity)
            Text(item.text, style = ConsoleType.body, color = colors.text)
        }
        is A2aClientItem.Display -> A2aDisplayRow(item, pickable, onReply)
        is A2aClientItem.Question -> Text(
            "问题 · ${item.phase}：${item.text}${if (item.options.isEmpty()) "" else "（${item.options.joinToString(" / ")}）"}",
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
            val prefix = when (item.kind) {
                A2aClientItem.User.Kind.MESSAGE -> ""
                A2aClientItem.User.Kind.STEER -> "补充 · "
                A2aClientItem.User.Kind.ANSWER -> "回答 · "
            }
            Text("$prefix${item.source.label}", style = ConsoleType.meta, color = colors.muted)
            Text(item.text, style = ConsoleType.body, color = colors.text)
        }
    }
}

@Composable
private fun A2aDisplayRow(item: A2aClientItem.Display, pickable: PickableReply?, onReply: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.radiusMd), color = ConsoleColors.elevated) {
        Column(Modifier.padding(Dimens.spaceSm)) {
            Text("展示 · 不播报", style = ConsoleType.meta, color = ConsoleColors.muted)
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
            "结束 · ${item.state.wireLabel()}$elapsed",
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
                        if (mode == QuestionAnswerMode.ACTIVE) "任务继续执行中，可随时回答" else "任务已结束，回答将作为同会话新消息发送",
                        style = ConsoleType.meta,
                        color = ConsoleColors.muted,
                    )
                    question.options.forEach { option ->
                        TextButton(onClick = { actions.answer(question.questionId, option) }) { Text(option) }
                    }
                    OutlinedTextField(draft, { draft = it }, label = { Text("也可以直接输入回答") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { actions.answer(question.questionId, draft.trim()) }, enabled = draft.isNotBlank()) { Text("提交回答") }
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
