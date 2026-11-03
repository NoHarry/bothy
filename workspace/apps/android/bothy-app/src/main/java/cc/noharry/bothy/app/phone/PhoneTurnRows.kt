package cc.noharry.bothy.app.phone

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.ConversationItemView
import cc.noharry.bothy.app.console.ConversationRenderRow
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.turnHeaderLabel
import cc.noharry.bothy.app.console.ui.CodeBlock
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.overlay.PickableReply
import cc.noharry.bothy.app.overlay.stepPhrase
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus

@Composable
internal fun PhoneRowView(row: PhoneRow, actions: ConsoleActions, pickable: PickableReply?, onTrajectory: (turn: Long) -> Unit) {
    when (row) {
        is PhoneRow.Asked -> AskedRow(row.header, actions)
        is PhoneRow.Thought -> ThoughtRow(row.item)
        is PhoneRow.Tool -> ToolRow(row.item)
        is PhoneRow.ToolGroup -> ToolGroupRow(row)
        is PhoneRow.Shown -> ConversationItemView(row.item, actions, pickable = pickable)
        is PhoneRow.Folded -> FoldedRow(row, actions, pickable, onTrajectory)
        is PhoneRow.Footnote -> FootnoteRow(row, onTrajectory)
        is PhoneRow.Made -> ConversationRenderRow(row.render, actions)
    }
}

/** The user's turn: a tinted bubble on the right, no wider than most of the row; a report or a plan keeps its name. */
@Composable
private fun AskedRow(header: ConversationItem.TurnHeader, actions: ConsoleActions) {
    val colors = ConsoleColors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
        val widest = maxWidth * BUBBLE_SHARE
        Column(horizontalAlignment = Alignment.End) {
            if (header.label != USER_LABEL) Text(turnHeaderLabel(header.label), style = ConsoleType.label, color = colors.tertiary, modifier = Modifier.padding(bottom = 4.dp))
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(BUBBLE_RADIUS),
                modifier = Modifier.widthIn(max = widest).clickable { actions.toggleItem(header.copy(collapsed = !header.collapsed)) },
            ) {
                Text(
                    text = if (header.collapsed) header.preview else header.fullText,
                    style = ConsoleType.body,
                    color = colors.text,
                    maxLines = if (header.collapsed) 3 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun ThoughtRow(item: ConversationItem.MessageItem) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val preview = item.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    StepLine(stringResource(R.string.overlay_step_thinking), preview, ConsoleColors.tertiary, live = false, open = open.takeIf { item.content.isNotBlank() }) { open = !open }
    if (open) Text(item.content, style = ConsoleType.bodySmall, color = ConsoleColors.muted, modifier = Modifier.padding(start = STEP_INDENT, bottom = 6.dp))
}

@Composable
private fun ToolRow(item: ConversationItem.ToolCallItem) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val resources = LocalResources.current
    val running = item.status == ToolCallStatus.RUNNING
    val acted = remember(item.argumentsJson) { item.stepObject() }
    StepLine(stepTitle(item, resources), acted, item.status.color(), live = running, open = open) { open = !open }
    if (open) {
        Column(Modifier.padding(start = STEP_INDENT, bottom = 6.dp)) {
            Text(item.toolName, style = ConsoleType.label, color = ConsoleColors.tertiary, modifier = Modifier.padding(bottom = 4.dp))
            item.argumentsJson?.takeIf { it.isNotBlank() }?.let { CodeBlock(it.take(PART_MAX_CHARS)) }
            item.resultJson?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(6.dp))
                CodeBlock(it.take(PART_MAX_CHARS))
            }
        }
    }
}

@Composable
private fun ToolGroupRow(group: PhoneRow.ToolGroup) {
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    val resources = LocalResources.current
    val running = group.tools.any { it.status == ToolCallStatus.RUNNING }
    val failed = group.tools.any { it.status == ToolCallStatus.FAILED }
    val last = stepTitle(group.tools.last(), resources)
    val title = pluralStringResource(R.plurals.phone_run_tool_calls, group.tools.size, group.tools.size)
    val dot = when { running -> ConsoleColors.activity; failed -> ConsoleColors.danger; else -> ConsoleColors.success }
    StepLine(title, last, dot, live = running, open = open) { open = !open }
    if (open) Column(Modifier.padding(start = 8.dp)) { group.tools.forEach { ToolRow(it) } }
}

/** One line of a run: a dot, what kind of step it was and on what; [open] null when there is nothing to open. */
@Composable
private fun StepLine(title: String, detail: String, dot: androidx.compose.ui.graphics.Color, live: Boolean, open: Boolean?, onToggle: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).then(if (open != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = dot, pulsing = live, modifier = Modifier.size(7.dp))
        Spacer(Modifier.width(12.dp))
        val line = if (detail.isBlank() || detail == title) title else "$title · $detail"
        Text(line, style = ConsoleType.meta, color = if (live) colors.activity else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (open != null) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
    }
}

/** A finished turn's process in one line: done, how many tool calls and passing messages; it opens to the steps. */
@Composable
private fun FoldedRow(row: PhoneRow.Folded, actions: ConsoleActions, pickable: PickableReply?, onTrajectory: (turn: Long) -> Unit) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val colors = ConsoleColors
    val parts = listOfNotNull(
        stringResource(R.string.console_run_done),
        row.toolCalls.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_tool_calls, it, it) },
        row.messages.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_messages, it, it) },
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(parts.joinToString(" · "), style = ConsoleType.meta, color = colors.muted, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
    }
    if (open) Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { row.rows.forEach { PhoneRowView(it, actions, pickable, onTrajectory) } }
}

/** Under an answer: copy it, and on the right how many steps (tool calls) the turn took, which opens its trajectory. */
@Composable
private fun FootnoteRow(row: PhoneRow.Footnote, onTrajectory: (turn: Long) -> Unit) {
    val colors = ConsoleColors
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        row.reply?.takeIf { it.isNotBlank() }?.let { reply ->
            IconButton(onClick = { clipboard.setText(AnnotatedString(reply)) }, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_phone_copy), contentDescription = stringResource(R.string.phone_copy), tint = colors.tertiary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        val steps = pluralStringResource(R.plurals.phone_steps, row.steps, row.steps).takeIf { row.steps > 0 }
        val text = listOfNotNull(stringResource(R.string.console_status_failed).takeIf { row.failed }, steps).joinToString(" · ")
        if (text.isNotEmpty()) Row(Modifier.clickable { onTrajectory(row.turn) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = ConsoleType.meta, color = if (row.failed) colors.danger else colors.tertiary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A step in the words a user would use: the overlay's names for tools, or "Thinking". */
internal fun stepTitle(step: ConversationItem, resources: Resources): String = when (step) {
    is ConversationItem.ToolCallItem -> step.toolName.stepPhrase(resources) ?: step.toolName
    else -> resources.getString(R.string.overlay_step_thinking)
}

/** How a user's own turn is stored ([cc.noharry.bothy.app.a2a.turnLabel]). */
private const val USER_LABEL = "You"
private val BUBBLE_RADIUS = 20.dp
private const val BUBBLE_SHARE = 0.82f
private val STEP_INDENT = 19.dp
private const val PART_MAX_CHARS = 2_000
