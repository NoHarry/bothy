package cc.noharry.bothy.app.phone

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.USER_TURN_LABEL
import cc.noharry.bothy.protocol.view.PickableReply
import cc.noharry.bothy.protocol.view.TTS_PROGRESS_PREFIX
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConversationItemView
import cc.noharry.bothy.app.console.ConversationRenderRow
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.turnHeaderLabel
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.overlay.StepKind
import cc.noharry.bothy.app.overlay.stepKind
import cc.noharry.bothy.app.plans.describeOnScreen
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable
internal fun PhoneRowView(row: PhoneRow, actions: ConsoleActions, pickable: PickableReply?, plans: List<Plan>, links: PhoneLinks) {
    when (row) {
        is PhoneRow.Asked -> AskedRow(row.header, actions)
        is PhoneRow.Steps -> StepsRow(row)
        is PhoneRow.Shown -> ShownRow(row.item, actions, pickable)
        is PhoneRow.Approval -> if (row.item.status == ConfirmationStatus.PENDING) ApprovalCard(row, actions) else ApprovalLine(row.item)
        is PhoneRow.Folded -> FoldedRow(row, actions, pickable)
        is PhoneRow.Footnote -> FootnoteRow(row, links.onTrajectory)
        is PhoneRow.Made -> ConsoleTypeOnPhone { ConversationRenderRow(row.render, actions) }
        is PhoneRow.PlanMade -> plans.firstOrNull { it.id == row.planId }?.let { PlanMadeCard(it, links.onPlans) }
    }
}

/** A plan the turn made: when it comes due, what then happens, and the way to the plans page. */
@Composable
private fun PlanMadeCard(plan: Plan, onPlans: () -> Unit) {
    val colors = ConsoleColors
    val context = LocalContext.current
    val due = remember(plan) { plan.trigger.describeOnScreen(context, System.currentTimeMillis(), ZoneId.systemDefault()) }
    val doing = plan.actions.map { it.label }.ifEmpty { listOfNotNull(plan.instruction ?: plan.message.takeIf { it.isNotBlank() }) }
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = if (plan.active) colors.accent else colors.muted, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(8.dp))
                Text(plan.title.ifBlank { stringResource(R.string.plans_title) }, style = PhoneType.cardTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(Modifier.clickable(onClick = onPlans).padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.phone_plan_view), style = PhoneType.label, color = colors.accent)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
            Text(due, style = PhoneType.body, color = colors.muted, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
            if (doing.isNotEmpty()) {
                Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                doing.forEachIndexed { index, step ->
                    Row(Modifier.padding(bottom = if (index == doing.lastIndex) 0.dp else 10.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(colors.panel), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = PhoneType.small, color = colors.muted, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(step, style = PhoneType.body, color = colors.text, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The user's turn: a sage bubble on the right, its top corner on that side tucked in; a report or a plan keeps its name. */
@Composable
private fun AskedRow(header: ConversationItem.TurnHeader, actions: ConsoleActions) {
    val colors = ConsoleColors
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val widest = maxWidth * BUBBLE_SHARE
        Column(horizontalAlignment = Alignment.End) {
            if (header.label != USER_TURN_LABEL) Text(turnHeaderLabel(header.label), style = PhoneType.small, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(topStart = BUBBLE_RADIUS, topEnd = BUBBLE_TUCK, bottomEnd = BUBBLE_RADIUS, bottomStart = BUBBLE_RADIUS),
                modifier = Modifier.widthIn(max = widest).clickable { actions.toggleItem(header) },
            ) {
                Text(
                    text = if (actions.isFolded(header)) header.preview else header.fullText,
                    style = PhoneType.body.copy(lineHeight = BUBBLE_LINE_HEIGHT),
                    color = colors.text,
                    maxLines = if (actions.isFolded(header)) 3 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** What a turn says, and its approvals, in the tablet's words and the phone's colours. */
@Composable
private fun ShownRow(item: ConversationItem, actions: ConsoleActions, pickable: PickableReply?) {
    // The open picks are the newest reply's: drawn under it alone, or every answer would read as the newest.
    ConsoleTypeOnPhone { ConversationItemView(item.withoutSpokenMarks(), actions, pickable = pickable?.takeIf { it.messageId == item.id }) }
}

/** What the run says as it goes is marked 「TTS:」 for the voice; the screen shows the words alone, as the overlay does. */
internal fun ConversationItem.withoutSpokenMarks(): ConversationItem =
    if (this is ConversationItem.MessageItem && role == TranscriptRole.ASSISTANT) copy(content = content.replace(TTS_PROGRESS_PREFIX, "")) else this

/**
 * An approval waiting for the person: what the run wants to reach, the tool that asks and how approvals go, then deny
 * and approve side by side.
 */
@Composable
private fun ApprovalCard(row: PhoneRow.Approval, actions: ConsoleActions) {
    val colors = ConsoleColors
    val item = row.item
    val resources = LocalResources.current
    val title = item.domain.takeIf { it.isNotBlank() }?.let { stringResource(R.string.phone_approval_title, it) }
        ?: stringResource(R.string.phone_approval_title_online)
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = PhoneType.cardTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.tool?.let { Text(phoneStepTitle(it, resources), style = PhoneType.body, color = colors.muted, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (item.domain.isNotBlank()) DetailLine(stringResource(R.string.phone_approval_site), item.domain)
                row.tool?.let { DetailLine(stringResource(R.string.phone_approval_tool), it.toolName) }
                DetailLine(stringResource(R.string.console_approval), stringResource(item.mode.labelRes()))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                PillButton(stringResource(R.string.console_deny), fill = colors.panel, text = colors.text, Modifier.weight(1f)) { actions.denyConfirmation(item.pendingId) }
                Spacer(Modifier.width(12.dp))
                PillButton(stringResource(R.string.console_approve), fill = colors.accent, text = onAccent(), Modifier.weight(1f)) { actions.approveConfirmation(item.pendingId) }
            }
        }
    }
}

/** An approval already settled, as one line: how it went, and what it was for. */
@Composable
private fun ApprovalLine(item: ConversationItem.ConfirmationItem) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = item.status.color(), modifier = Modifier.size(6.dp))
        Spacer(Modifier.width(8.dp))
        val what = item.domain.ifBlank { stringResource(R.string.console_network_approval) }
        Text(stringResource(item.status.labelRes()) + " · " + what, style = PhoneType.label, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A label on the left and its value on the right, as in the design's detail panels. */
@Composable
internal fun DetailLine(label: String, value: String) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = PhoneType.body, color = colors.muted, modifier = Modifier.padding(end = 16.dp))
        Text(value, style = PhoneType.body, color = colors.text, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** A wide rounded button: a filled one for what goes ahead, a quiet one beside it. */
@Composable
internal fun PillButton(label: String, fill: Color, text: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = fill, modifier = modifier.heightIn(min = 48.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = PhoneType.label, color = text, fontWeight = FontWeight.SemiBold) }
    }
}

/** A turn under way, or one that ended unfinished: what it is doing, then its steps as one card. */
@Composable
private fun StepsRow(row: PhoneRow.Steps) {
    Column(Modifier.fillMaxWidth()) {
        StepsHeader(row)
        // While an approval waits, it is what the turn shows ([ShownRow]); the steps are in the trajectory.
        if (row.waiting == null && row.steps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            StepsCard(row.steps, live = row.live)
        }
    }
}

/** "● Working · Step 2 ⟳ Running", or that an approval waits, or that the turn did not finish. */
@Composable
private fun StepsHeader(row: PhoneRow.Steps) {
    val colors = ConsoleColors
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            row.waiting != null -> {
                StatusDot(color = colors.warning, pulsing = true, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.phone_steps_waiting), style = PhoneType.label, color = colors.warning, fontWeight = FontWeight.SemiBold)
                row.waiting.domain.takeIf { it.isNotBlank() }?.let { domain ->
                    Text(" · $domain", style = PhoneType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            row.live -> {
                StatusDot(color = colors.activity, pulsing = true, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.phone_steps_working), style = PhoneType.cardTitle, color = colors.text)
                if (row.steps.isNotEmpty()) {
                    Text(" · " + stringResource(R.string.phone_step_number, row.steps.size), style = PhoneType.label, color = colors.muted)
                }
                Spacer(Modifier.weight(1f))
                RunningChip()
            }
            else -> {
                StatusDot(color = if (row.failed) colors.danger else colors.muted, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.phone_steps_ended), style = PhoneType.label, color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(" · " + pluralStringResource(R.plurals.phone_steps, row.steps.size, row.steps.size), style = PhoneType.label, color = colors.muted)
            }
        }
    }
}

@Composable
private fun RunningChip() {
    val colors = ConsoleColors
    Row(
        Modifier.clip(CircleShape).background(colors.panel).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.phone_steps_running), style = PhoneType.small, color = colors.inkSoft)
    }
}

/**
 * A turn's steps on a white card, each on a line down its left side: done, going on, or failed, its name, the tool
 * and what it acted on. The card stops at [STEPS_MAX_HEIGHT] and scrolls inside; while the turn goes on it follows
 * the newest step, unless the person scrolled up to read an earlier one.
 */
@Composable
internal fun StepsCard(steps: List<ConversationItem>, live: Boolean) {
    val colors = ConsoleColors
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, live) {
        if (!live) return@LaunchedEffect
        // Follows from the start, also a card that comes back full; the person scrolling away from the end stops it,
        // and back to the end starts it again.
        var following = true
        launch {
            snapshotFlow { scroll.isScrollInProgress to scroll.value }.collect { (moving, at) ->
                if (moving) following = at >= scroll.maxValue - FOLLOW_SLACK_PX
            }
        }
        snapshotFlow { scroll.maxValue }.filter { it != Int.MAX_VALUE }.collect { end -> if (following) scroll.scrollTo(end) }
    }
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = STEPS_MAX_HEIGHT).verticalScroll(scroll).padding(16.dp)) {
            steps.forEachIndexed { index, step ->
                val last = index == steps.lastIndex
                StepLine(step, last = last, going = live && last)
            }
        }
    }
}

/** One step: its mark on the line, its name and tool, and what it acted on or thought. */
@Composable
private fun StepLine(step: ConversationItem, last: Boolean, going: Boolean) {
    val colors = ConsoleColors
    val resources = LocalResources.current
    val title = remember(step, resources) { phoneStepTitle(step, resources) }
    val detail = remember(step) { stepDetail(step) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(MARK_SIZE).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMark(step, going)
            if (!last) Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(colors.borderSubtle))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
            Row(Modifier.heightIn(min = MARK_SIZE), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = PhoneType.reply,
                    fontWeight = FontWeight.SemiBold,
                    color = if (going || step.failed()) colors.text else colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                (step as? ConversationItem.ToolCallItem)?.let { ToolChip(it.toolName) }
            }
            if (detail.isNotBlank()) Text(detail, style = PhoneType.body, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A step's mark: a spinner while it goes on, a cross if it failed, else a check. */
@Composable
private fun StepMark(step: ConversationItem, going: Boolean) {
    val colors = ConsoleColors
    val running = going || (step as? ConversationItem.ToolCallItem)?.status == ToolCallStatus.RUNNING
    Box(Modifier.size(MARK_SIZE), contentAlignment = Alignment.Center) {
        when {
            running -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            step.failed() -> Mark(Icons.Filled.Close, colors.danger, colors.danger.copy(alpha = MARK_TINT_ALPHA))
            else -> Mark(Icons.Filled.Check, colors.accent, colors.accentSoft)
        }
    }
}

@Composable
private fun Mark(icon: ImageVector, tint: Color, fill: Color) {
    Box(Modifier.size(MARK_SIZE).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

/** The tool's own name, in a small mono chip after the step's. */
@Composable
private fun ToolChip(tool: String) {
    val colors = ConsoleColors
    Text(
        tool,
        style = PhoneType.tool,
        color = colors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = 8.dp)
            .widthIn(max = TOOL_CHIP_MAX_WIDTH)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.accentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A turn that ended well, as one chip: done, how many times it thought, how many tools it used and passing messages.
 * It opens to the steps card and those messages.
 */
@Composable
private fun FoldedRow(row: PhoneRow.Folded, actions: ConsoleActions, pickable: PickableReply?) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val colors = ConsoleColors
    val parts = listOfNotNull(
        stringResource(R.string.console_run_done),
        row.thoughts.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_thoughts, it, it) },
        row.tools.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_tools, it, it) },
        row.interim.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.phone_run_messages, it, it) },
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(CircleShape).background(colors.panel).clickable { open = !open }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = colors.accent, modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(8.dp))
            Text(parts.joinToString(" · "), style = PhoneType.label, color = colors.inkSoft)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(start = 2.dp).size(18.dp))
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (row.steps.isNotEmpty()) StepsCard(row.steps, live = false)
            row.interim.forEach { message ->
                Spacer(Modifier.height(8.dp))
                ShownRow(message, actions, pickable)
            }
        }
    }
}

/** Under an answer: copy it, and on the right how many steps (tool calls) the turn took, which opens its trajectory. */
@Composable
private fun FootnoteRow(row: PhoneRow.Footnote, onTrajectory: (turn: Long) -> Unit) {
    val colors = ConsoleColors
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        row.reply?.takeIf { it.isNotBlank() }?.let { reply ->
            IconButton(onClick = { clipboard.setText(AnnotatedString(reply)) }, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_phone_copy), contentDescription = stringResource(R.string.phone_copy), tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        val steps = pluralStringResource(R.plurals.phone_steps, row.steps, row.steps).takeIf { row.steps > 0 }
        val text = listOfNotNull(stringResource(R.string.console_status_failed).takeIf { row.failed }, steps).joinToString(" · ")
        if (text.isNotEmpty()) Row(Modifier.clickable { onTrajectory(row.turn) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = PhoneType.label, color = if (row.failed) colors.danger else colors.muted)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** A step's name on the phone, a noun for the kind of work ("Route"); the overlay's says it is under way. */
internal fun phoneStepTitle(step: ConversationItem, resources: Resources): String = when (step) {
    is ConversationItem.ToolCallItem -> resources.getString(stepKind(step.toolName).phoneTitle)
    else -> resources.getString(R.string.phone_step_thought)
}

/** What a step acted on ([stepObject]), or the first line of what it thought. */
internal fun stepDetail(step: ConversationItem): String = when (step) {
    is ConversationItem.ToolCallItem -> step.stepObject()
    is ConversationItem.MessageItem -> step.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    else -> ""
}

private fun ConversationItem.failed(): Boolean = this is ConversationItem.ToolCallItem && status == ToolCallStatus.FAILED

private val StepKind.phoneTitle: Int
    get() = when (this) {
        StepKind.PREPARING -> R.string.phone_step_kind_preparing
        StepKind.PAGE -> R.string.phone_step_kind_page
        StepKind.NOTING -> R.string.phone_step_kind_noting
        StepKind.PLANNING -> R.string.phone_step_kind_planning
        StepKind.DRAWING -> R.string.phone_step_kind_drawing
        StepKind.NAVIGATION -> R.string.phone_step_kind_navigation
        StepKind.MUSIC -> R.string.phone_step_kind_music
        StepKind.SETTINGS -> R.string.phone_step_kind_settings
        StepKind.READING -> R.string.phone_step_kind_reading
        StepKind.STEPS -> R.string.phone_step_kind_steps
        StepKind.WEB -> R.string.phone_step_kind_web
        StepKind.A2A -> R.string.phone_step_kind_a2a
        StepKind.OTHER -> R.string.phone_step_kind_other
    }

private val BUBBLE_RADIUS = 16.dp
private val BUBBLE_TUCK = 4.dp
private val BUBBLE_LINE_HEIGHT = 22.sp
private const val BUBBLE_SHARE = 0.82f
private val MARK_SIZE = 24.dp
private val TOOL_CHIP_MAX_WIDTH = 140.dp
private const val MARK_TINT_ALPHA = 0.12f
/** How many of a long turn's steps its card shows before "N earlier steps". */
/** About four steps; the rest scroll inside the card. */
private val STEPS_MAX_HEIGHT = 280.dp
/** How far from the end still counts as following the newest step. */
private const val FOLLOW_SLACK_PX = 24
