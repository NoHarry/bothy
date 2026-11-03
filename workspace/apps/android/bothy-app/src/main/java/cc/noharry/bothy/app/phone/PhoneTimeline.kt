package cc.noharry.bothy.app.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.ConversationItemView
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.ConversationRenderRow
import cc.noharry.bothy.app.console.UserTurnBubble
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.toHtmlArtifactPreview
import cc.noharry.bothy.app.console.turnHeaderLabel
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.console.uniqueKeys
import cc.noharry.bothy.app.overlay.generatedPicture
import cc.noharry.bothy.app.overlay.pickableReply
import cc.noharry.bothy.app.overlay.stepPhrase
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.UiState

/** A row of the phone's conversation: what was asked, how its run went, what came back, and what a tool made. */
internal sealed interface PhoneRow {
    val key: String

    data class Asked(val header: ConversationItem.TurnHeader) : PhoneRow {
        override val key = "asked-${header.id}"
    }

    /** The turn's thinking and tool calls in order, its result ("COMPLETED", "FAILED"…), and whether it is still going. */
    data class Run(val turn: Long, val steps: List<ConversationItem>, val result: String?, val live: Boolean) : PhoneRow {
        override val key = "run-$turn"
    }

    data class Shown(val item: ConversationItem) : PhoneRow {
        override val key = "item-${item.id}"
    }

    data class Made(val render: ConversationRenderItem) : PhoneRow {
        override val key = render.key
    }
}

/**
 * The phone design's conversation from the transcript: per turn, the question, one line for the run (its thinking and
 * tools folded into it), then the replies, approvals and what tools made. Status lines and the runtime's own entries
 * are not shown; the result's status goes into the run's line.
 */
internal fun phoneRows(items: List<ConversationItem>, running: Boolean): List<PhoneRow> {
    val turns = mutableListOf<MutableList<ConversationItem>>()
    items.forEach { item -> if (item is ConversationItem.TurnHeader || turns.isEmpty()) turns += mutableListOf(item) else turns.last() += item }
    return turns.flatMapIndexed { index, turn -> turnRows(turn, live = running && index == turns.lastIndex) }
}

private fun turnRows(turn: List<ConversationItem>, live: Boolean): List<PhoneRow> {
    val rows = mutableListOf<PhoneRow>()
    (turn.first() as? ConversationItem.TurnHeader)?.let { rows += PhoneRow.Asked(it) }
    val steps = turn.filter { it.isPhoneStep() }
    val result = turn.filterIsInstance<ConversationItem.RuntimePhaseItem>().lastOrNull { it.title.startsWith(RESULT_PREFIX) }
        ?.title?.removePrefix(RESULT_PREFIX)?.substringBefore(' ')
    if (steps.isNotEmpty() || result != null || live) rows += PhoneRow.Run(turn.first().id, steps, result, live)
    turn.forEach { item ->
        val shown = item is ConversationItem.ConfirmationItem ||
            item is ConversationItem.MessageItem && item.role in SHOWN_ROLES
        if (shown) rows += PhoneRow.Shown(item)
    }
    turn.filterIsInstance<ConversationItem.ToolCallItem>().forEach { tool ->
        tool.toHtmlArtifactPreview()?.let { rows += PhoneRow.Made(ConversationRenderItem.HtmlArtifact(tool.id, it)) }
        tool.generatedPicture()?.let { rows += PhoneRow.Made(ConversationRenderItem.GeneratedImage(tool.id, it)) }
    }
    return rows
}

/** A step the run's line counts and lists: a thought, or a tool call other than the runtime's own [FINISH_TOOL]. */
internal fun ConversationItem.isPhoneStep(): Boolean = when (this) {
    is ConversationItem.ToolCallItem -> toolName != FINISH_TOOL
    is ConversationItem.MessageItem -> role == TranscriptRole.THINKING
    else -> false
}

/** The conversation, newest at the bottom over the composer; it follows a run as it grows. */
@Composable
internal fun PhoneTimeline(state: UiState, actions: ConsoleActions, onTrajectory: () -> Unit, modifier: Modifier) {
    val rows = remember(state.items, state.isRunning) { phoneRows(state.items, state.isRunning) }
    val keys = remember(rows) { rows.map { it.key }.uniqueKeys() }
    val bottomFirst = remember(rows) { rows.asReversed() }
    val pickable = remember(state) { state.pickableReply() }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size, state.isRunning) { if (rows.isNotEmpty()) list.animateScrollToItem(0) }
    LazyColumn(modifier, state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(bottomFirst, key = { index, _ -> keys[keys.lastIndex - index] }) { _, row ->
            when (row) {
                is PhoneRow.Asked -> AskedRow(row.header, actions)
                is PhoneRow.Run -> RunRow(row, onTrajectory)
                is PhoneRow.Shown -> ConversationItemView(row.item, actions, pickable = pickable)
                is PhoneRow.Made -> ConversationRenderRow(row.render, actions)
            }
        }
    }
}

@Composable
private fun AskedRow(header: ConversationItem.TurnHeader, actions: ConsoleActions) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.End) {
        // The user's own turns go by their bubble; a delegation's report or a plan's keeps its name.
        if (header.label != USER_LABEL) Text(turnHeaderLabel(header.label), style = ConsoleType.label, color = ConsoleColors.tertiary, modifier = Modifier.padding(bottom = 4.dp))
        UserTurnBubble(if (header.collapsed) header.preview else header.fullText, collapsed = header.collapsed) {
            actions.toggleItem(header.copy(collapsed = !header.collapsed))
        }
    }
}

/**
 * One line for the run, as the phone design draws it: how it went, how often it thought and how many tools it used.
 * A run going on is open, its steps listed as they come; a finished one opens on a tap, with the trajectory under it.
 */
@Composable
private fun RunRow(run: PhoneRow.Run, onTrajectory: () -> Unit) {
    var open by rememberSaveable(run.key) { mutableStateOf(run.live) }
    LaunchedEffect(run.live) { if (run.live) open = true }
    val colors = ConsoleColors
    val failed = !run.live && run.result != null && run.result != COMPLETED
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = when { run.live -> colors.activity; failed -> colors.danger; else -> colors.success }, pulsing = run.live, modifier = Modifier.size(8.dp))
            Spacer(Modifier.width(10.dp))
            Text(runLine(run, failed), style = ConsoleType.meta, color = if (run.live) colors.activity else colors.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted)
        }
        if (open) {
            run.steps.forEach { StepLine(it) }
            if (!run.live) TextButton(onClick = onTrajectory) { Text(stringResource(R.string.phone_run_trajectory)) }
        }
    }
}

@Composable
private fun runLine(run: PhoneRow.Run, failed: Boolean): String {
    val resources = LocalResources.current
    val thoughts = run.steps.count { it is ConversationItem.MessageItem }
    val tools = run.steps.count { it is ConversationItem.ToolCallItem }
    if (run.live) {
        val now = run.steps.lastOrNull()?.let { stepTitle(it, resources) } ?: resources.getString(R.string.overlay_step_thinking)
        return stringResource(R.string.phone_run_working, run.steps.size.coerceAtLeast(1), now)
    }
    return listOfNotNull(
        stringResource(if (failed) R.string.console_status_failed else R.string.console_run_done),
        thoughts.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_thoughts, it, it) },
        tools.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_tools, it, it) },
    ).joinToString(" · ")
}

/** A step under the run's line: what kind of work it was, then the tool and how it ended, or the thought's start. */
@Composable
private fun StepLine(step: ConversationItem) {
    val colors = ConsoleColors
    val resources = LocalResources.current
    val (dot, detail) = when (step) {
        is ConversationItem.ToolCallItem -> step.status.color() to step.toolName
        is ConversationItem.MessageItem -> colors.success to step.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        else -> colors.muted to ""
    }
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        StatusDot(color = dot, modifier = Modifier.padding(top = 7.dp).size(7.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stepTitle(step, resources), style = ConsoleType.body, color = colors.text)
            if (detail.isNotEmpty()) Text(detail, style = ConsoleType.meta, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A step in the words a user would use: the overlay's names for tools, or "Thinking". */
internal fun stepTitle(step: ConversationItem, resources: android.content.res.Resources): String = when (step) {
    is ConversationItem.ToolCallItem -> step.toolName.stepPhrase(resources) ?: step.toolName
    else -> resources.getString(R.string.overlay_step_thinking)
}

private const val RESULT_PREFIX = "Runtime result "
private const val COMPLETED = "COMPLETED"
/** The tool a run ends itself with; not a step anyone asked for. */
private const val FINISH_TOOL = "finish"
/** How a user's own turn is stored ([cc.noharry.bothy.app.a2a.turnLabel]). */
private const val USER_LABEL = "You"
private val SHOWN_ROLES = setOf(TranscriptRole.ASSISTANT, TranscriptRole.ERROR, TranscriptRole.NOTICE)
