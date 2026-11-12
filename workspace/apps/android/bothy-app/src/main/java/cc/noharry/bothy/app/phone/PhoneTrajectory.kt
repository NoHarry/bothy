package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.isTurnStart
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.consoleLayoutPolicy
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.session.sessionTitleText
import cc.noharry.bothy.app.trajectory.TrajectoryScreen
import kotlinx.serialization.json.JsonPrimitive

/**
 * A turn's trajectory on a phone, as the design draws it: how many times it thought and how many tools it used, then
 * each step on a line down the left, a card each: a thought's words, or a tool call's name, how it went and how long
 * it took, what it was given and what came back as label–value rows (the raw text when they are not). The turn is the
 * one whose footnote was tapped ([turn]), else the latest. The full trajectory, every session and run, is one tap
 * further; back from it returns here.
 */
@Composable
internal fun PhoneTrajectory(state: ServerState, actions: ConsoleActions, turn: Long?, onBack: () -> Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = full) { full = false }
    val steps = remember(state.items, turn) { turnSteps(state.items, turn) }
    val title = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }?.title?.takeIf { it.isNotBlank() }?.let { sessionTitleText(it) }
    val subtitle = listOfNotNull(title, pluralStringResource(R.plurals.phone_steps, steps.size, steps.size)).joinToString(" · ")
    PhonePage(stringResource(R.string.console_view_trajectory), onBack = { if (full) full = false else onBack() }, subtitle = subtitle.takeUnless { full }) {
        if (full) {
            ConsoleTypeOnPhone {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    TrajectoryScreen(state, actions, consoleLayoutPolicy(maxWidth.value, maxHeight.value), modifier = Modifier.fillMaxSize())
                }
            }
        } else {
            TurnSteps(steps, { full = true }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun TurnSteps(steps: List<ConversationItem>, onFull: () -> Unit, modifier: Modifier) {
    val colors = ConsoleColors
    LazyColumn(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        item(key = "summary") {
            val thoughts = steps.count { it is ConversationItem.MessageItem }
            val tools = steps.count { it is ConversationItem.ToolCallItem }
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (steps.isEmpty()) Text(stringResource(R.string.console_trajectory_none), style = PhoneType.body, color = colors.muted)
                if (thoughts > 0) SummaryChip(pluralStringResource(R.plurals.phone_run_thoughts, thoughts, thoughts))
                if (tools > 0) SummaryChip(pluralStringResource(R.plurals.phone_run_tools, tools, tools))
            }
        }
        // A loaded run gives every call the run's times; only calls of their own times say how long each took.
        val timed = steps.filterIsInstance<ConversationItem.ToolCallItem>().mapNotNull { it.took() }.let { it.size == 1 || it.distinct().size > 1 }
        itemsIndexed(steps, key = { _, step -> step.id }) { index, step -> TrajectoryStep(step, last = index == steps.lastIndex, timed = timed) }
        item(key = "full") {
            Row(Modifier.padding(top = 8.dp).clip(CircleShape).clickable(onClick = onFull).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.console_trajectory_full), style = PhoneType.label, color = colors.accent)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    val colors = ConsoleColors
    Text(
        text.replaceFirstChar { it.uppercaseChar() },
        style = PhoneType.label,
        color = colors.inkSoft,
        modifier = Modifier.clip(CircleShape).background(colors.panel).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** One step: its mark on the line down the left, and its card. */
@Composable
private fun TrajectoryStep(step: ConversationItem, last: Boolean, timed: Boolean) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(RAIL_MARK).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            val failed = (step as? ConversationItem.ToolCallItem)?.status == ToolCallStatus.FAILED
            Box(
                Modifier.size(RAIL_MARK).clip(CircleShape).background(if (failed) colors.danger.copy(alpha = FAILED_TINT) else colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (failed) Icons.Filled.Close else Icons.Filled.Check, contentDescription = null, tint = if (failed) colors.danger else colors.accent, modifier = Modifier.size(16.dp))
            }
            if (!last) Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(colors.borderSubtle))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(bottom = 16.dp)) {
            when (step) {
                is ConversationItem.ToolCallItem -> CallCard(step, timed)
                is ConversationItem.MessageItem -> ThoughtCard(step)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ThoughtCard(thought: ConversationItem.MessageItem) {
    val colors = ConsoleColors
    var open by rememberSaveable(thought.id) { mutableStateOf(false) }
    StepCard(stringResource(R.string.phone_step_thought), tool = null, status = null) {
        Text(
            thought.content.trim(),
            style = PhoneType.body,
            color = colors.inkSoft,
            maxLines = if (open) Int.MAX_VALUE else THOUGHT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { open = !open },
        )
    }
}

@Composable
private fun CallCard(call: ConversationItem.ToolCallItem, timed: Boolean) {
    val resources = LocalResources.current
    val given = remember(call.argumentsJson) { keyValues(call.argumentsJson) }
    val returned = remember(call.resultJson) { keyValues(call.resultJson) }
    val took = call.took()?.takeIf { timed }
    val status = listOfNotNull(stringResource(call.status.labelRes()).replaceFirstChar { it.uppercaseChar() }, took?.let { seconds(it) }).joinToString(" · ")
    StepCard(phoneStepTitle(call, resources), tool = call.toolName, status = status to call.status.color()) {
        call.argumentsJson?.takeIf { it.isNotBlank() }?.let { CallPanel(stringResource(R.string.phone_trajectory_given), given, it) }
        call.resultJson?.takeIf { it.isNotBlank() }?.let { CallPanel(stringResource(R.string.phone_trajectory_returned), returned, it) }
    }
}

/** A step's card: its name, the tool's own name, how it went, then what it holds. */
@Composable
private fun StepCard(title: String, tool: String?, status: Pair<String, Color>?, content: @Composable ColumnScope.() -> Unit) {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = PhoneType.cardTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                status?.let { (words, dot) ->
                    Row(Modifier.padding(start = 8.dp).clip(CircleShape).background(colors.panel).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = dot, modifier = Modifier.size(6.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(words, style = PhoneType.small, color = colors.muted, maxLines = 1)
                    }
                }
            }
            tool?.let {
                Text(
                    it,
                    style = PhoneType.tool,
                    color = colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.panel).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            content()
        }
    }
}

/** What a call was given or gave back: label–value rows, or its raw text when it is no object; long ones open. */
@Composable
private fun CallPanel(title: String, rows: List<Pair<String, String>>?, raw: String) {
    val colors = ConsoleColors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp)) {
        Text(title, style = PhoneType.small, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
        if (rows == null) {
            Text(raw.take(if (open) RAW_MAX_CHARS else RAW_SHORT_CHARS), style = PhoneType.tool.copy(fontSize = 12.sp, lineHeight = 17.sp), color = colors.inkSoft, modifier = Modifier.clickable { open = !open })
        } else {
            val shown = if (open) rows else rows.take(ROWS_SHOWN)
            shown.forEach { (label, value) -> DetailLine(label, value) }
            if (rows.size > ROWS_SHOWN && !open) {
                Text(pluralStringResource(R.plurals.phone_trajectory_more, rows.size - ROWS_SHOWN, rows.size - ROWS_SHOWN), style = PhoneType.label, color = colors.accent, modifier = Modifier.clickable { open = true }.padding(top = 4.dp))
            }
        }
    }
}

/** How long a call took by its own times; null when they are not kept, or are the run's. */
private fun ConversationItem.ToolCallItem.took(): Long? = finishedAt?.minus(startedAt)?.takeIf { startedAt > 0 && it in 1 until MAX_SHOWN_MS }

/** A step's time: 0.8s, 12s. */
private fun seconds(millis: Long): String = if (millis < 10_000) "%.1fs".format(millis / 1000.0) else "${millis / 1000}s"

/**
 * A call's arguments or result as label–value rows: its top-level fields as they are, anything nested as compact JSON
 * cut short; null when it is no JSON object.
 */
internal fun keyValues(text: String?): List<Pair<String, String>>? = jsonObjectOf(text)?.map { (key, value) ->
    val shown = when (value) {
        is JsonPrimitive -> value.content
        else -> value.toString().let { if (it.length > NESTED_MAX_CHARS) it.take(NESTED_MAX_CHARS - 1) + "…" else it }
    }
    key to shown
}

/**
 * The thoughts and tool calls of the turn that starts at the item [turn] (the latest turn when it is null or no longer
 * there), up to the next turn: what the phone's trajectory shows.
 */
internal fun turnSteps(items: List<ConversationItem>, turn: Long?): List<ConversationItem> {
    val asked = turn?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    val start = asked ?: items.indexOfLast { it.isTurnStart }.coerceAtLeast(0)
    val next = items.drop(start + 1).indexOfFirst { it.isTurnStart }
    val end = if (next < 0) items.size else start + 1 + next
    return items.subList(start, end).filter { it.isPhoneStep() }
}

/** The tool calls of that turn ([turnSteps]): what a turn's footnote counts. */
internal fun turnCalls(items: List<ConversationItem>, turn: Long?): List<ConversationItem.ToolCallItem> =
    turnSteps(items, turn).filterIsInstance<ConversationItem.ToolCallItem>()

private val RAIL_MARK = 32.dp
private const val FAILED_TINT = 0.12f
private const val THOUGHT_LINES = 6
private const val ROWS_SHOWN = 6
private const val RAW_SHORT_CHARS = 400
private const val RAW_MAX_CHARS = 8_000
private const val NESTED_MAX_CHARS = 120
/** Longer than this, a call's times are the run's, not its own (loaded runs give every call the run's). */
private const val MAX_SHOWN_MS = 600_000L
