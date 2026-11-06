package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.shownPage
import cc.noharry.agent.appserver.protocol.view.toHtmlArtifactPreview
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.uniqueKeys
import cc.noharry.bothy.protocol.view.TTS_PROGRESS_PREFIX
import cc.noharry.bothy.protocol.view.generatedPicture
import cc.noharry.bothy.protocol.view.pickableReply

/** A row of the phone's conversation. */
internal sealed interface PhoneRow {
    val key: String

    data class Asked(val header: ConversationItem.TurnHeader) : PhoneRow {
        override val key = "asked-${header.id}"
    }

    /**
     * A turn's steps (thoughts and tool calls) as one card: while the turn goes on ([live]), or where one that failed or
     * was stopped ([failed]) ended. [waiting] is the approval the turn waits for, if any.
     */
    data class Steps(
        val turn: Long,
        val steps: List<ConversationItem>,
        val live: Boolean,
        val failed: Boolean,
        val waiting: ConversationItem.ConfirmationItem? = null,
    ) : PhoneRow {
        override val key = "steps-$turn"
    }

    /** A reply, an error or a notice. */
    data class Shown(val item: ConversationItem) : PhoneRow {
        override val key = "item-${item.id}"
    }

    /** An approval, with the tool call that asked for it ([tool], when it is in the turn). */
    data class Approval(val item: ConversationItem.ConfirmationItem, val tool: ConversationItem.ToolCallItem?) : PhoneRow {
        override val key = "approval-${item.id}"
    }

    /** A turn that ended well, its steps and passing messages ([interim]) folded into one chip; the answer stays outside. */
    data class Folded(val turn: Long, val steps: List<ConversationItem>, val interim: List<ConversationItem.MessageItem>) : PhoneRow {
        override val key = "fold-$turn"
        val thoughts: Int get() = steps.count { it is ConversationItem.MessageItem }
        val tools: Int get() = steps.count { it is ConversationItem.ToolCallItem }
    }

    /** Under a finished turn: copy its answer ([reply]), how many tool calls it made, and the way to its trajectory. */
    data class Footnote(val turn: Long, val reply: String?, val steps: Int, val failed: Boolean) : PhoneRow {
        override val key = "foot-$turn"
    }

    data class Made(val render: ConversationRenderItem) : PhoneRow {
        override val key = render.key
    }

    /** A plan the turn made or changed, by its id ([planId]); it opens the plans page. */
    data class PlanMade(val turn: Long, val planId: String) : PhoneRow {
        override val key = "plan-$turn-$planId"
    }
}

/** Where a turn's rows lead: its trajectory, and the plans page. */
internal class PhoneLinks(val onTrajectory: (turn: Long) -> Unit, val onPlans: () -> Unit)

/**
 * The conversation as a phone shows it, turn by turn, as the design draws it: the question; while a turn goes on, its
 * steps as one card under what it is doing, then what it says and any approval it waits for; once it ended well, its
 * steps folded into one chip with the answer left outside and a footnote under it. A turn that failed or was stopped
 * keeps its card open, so where it stopped shows. Status lines and the runtime's own entries are not shown.
 */
internal fun phoneRows(items: List<ConversationItem>, running: Boolean): List<PhoneRow> {
    val turns = mutableListOf<MutableList<ConversationItem>>()
    items.forEach { item -> if (item is ConversationItem.TurnHeader || turns.isEmpty()) turns += mutableListOf(item) else turns.last() += item }
    return turns.flatMapIndexed { index, turn -> turnRows(turn, live = running && index == turns.lastIndex) }
}

private fun turnRows(turn: List<ConversationItem>, live: Boolean): List<PhoneRow> {
    val rows = mutableListOf<PhoneRow>()
    val header = turn.first() as? ConversationItem.TurnHeader
    header?.let { rows += PhoneRow.Asked(it) }
    val body = if (header != null) turn.drop(1) else turn
    val result = body.filterIsInstance<ConversationItem.RuntimePhaseItem>().lastOrNull { it.title.startsWith(RESULT_PREFIX) }
        ?.title?.removePrefix(RESULT_PREFIX)?.substringBefore(' ')
    val reply = body.lastOrNull { it is ConversationItem.MessageItem && it.role == TranscriptRole.ASSISTANT } as? ConversationItem.MessageItem
    val failed = result != null && result != COMPLETED
    val id = turn.first().id
    val steps = body.filter { it.isPhoneStep() }
    // Pages it drew show before the answer, as they came: the answer only says what they are for.
    val pages = body.filterIsInstance<ConversationItem.ToolCallItem>()
        .mapNotNull { tool -> tool.shownPage()?.let { PhoneRow.Made(ConversationRenderItem.DrawnPage(tool.id, it)) } }
    if (!live && !failed && reply != null) {
        val interim = body.filter { it.isInterim(reply) }.filterIsInstance<ConversationItem.MessageItem>()
        if (steps.isNotEmpty() || interim.isNotEmpty()) rows += PhoneRow.Folded(id, steps, interim)
        rows += pages
        rows += PhoneRow.Shown(reply)
    } else {
        val waiting = body.lastOrNull { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING } as? ConversationItem.ConfirmationItem
        if (steps.isNotEmpty() || live) rows += PhoneRow.Steps(id, steps, live, failed, waiting)
        rows += pages
        body.forEachIndexed { index, item ->
            when {
                item is ConversationItem.ConfirmationItem -> {
                    val asking = body.subList(0, index).lastOrNull { it is ConversationItem.ToolCallItem } as? ConversationItem.ToolCallItem
                    rows += PhoneRow.Approval(item, asking)
                }
                item is ConversationItem.MessageItem && item.role in SHOWN_ROLES -> rows += PhoneRow.Shown(item)
            }
        }
    }
    // What a copy takes: the reply's words without the voice's marks, as the screen shows them.
    val copied = reply?.content?.replace(TTS_PROGRESS_PREFIX, "")
    if (!live && (reply != null || failed)) rows += PhoneRow.Footnote(id, copied, body.count { it.isTool() }, failed)
    body.filterIsInstance<ConversationItem.ToolCallItem>().mapNotNull { it.madePlan() }.distinct().forEach { rows += PhoneRow.PlanMade(id, it) }
    body.filterIsInstance<ConversationItem.ToolCallItem>().forEach { tool ->
        tool.toHtmlArtifactPreview()?.let { rows += PhoneRow.Made(ConversationRenderItem.HtmlArtifact(tool.id, it)) }
        tool.generatedPicture()?.let { rows += PhoneRow.Made(ConversationRenderItem.GeneratedImage(tool.id, it)) }
    }
    return rows
}

/** A step the run counts: a thought, or a tool call other than the runtime's own [FINISH_TOOL]. */
internal fun ConversationItem.isPhoneStep(): Boolean = when (this) {
    is ConversationItem.ToolCallItem -> toolName != FINISH_TOOL
    is ConversationItem.MessageItem -> role == TranscriptRole.THINKING
    else -> false
}

private fun ConversationItem.isTool(): Boolean = this is ConversationItem.ToolCallItem && isPhoneStep()

private fun ConversationItem.isInterim(reply: ConversationItem): Boolean =
    this !== reply && this is ConversationItem.MessageItem && role == TranscriptRole.ASSISTANT && content.isNotBlank()

/** The conversation, newest at the bottom over the composer; it follows a run as it grows. */
@Composable
internal fun PhoneTimeline(state: ServerState, actions: ConsoleActions, links: PhoneLinks, modifier: Modifier) {
    val rows = remember(state.items, state.isRunning) { phoneRows(state.items, state.isRunning) }
    val keys = remember(rows) { rows.map { it.key }.uniqueKeys() }
    val bottomFirst = remember(rows) { rows.asReversed() }
    val pickable = remember(state) { state.pickableReply() }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size, state.isRunning) { if (rows.isNotEmpty()) list.animateScrollToItem(0) }
    LazyColumn(modifier, state = list, reverseLayout = true, contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(bottomFirst, key = { index, _ -> keys[keys.lastIndex - index] }) { _, row ->
            // One node per item: a reversed list would turn a row's own lines (a step and what it opens to) upside down.
            Column(Modifier.fillMaxWidth()) { PhoneRowView(row, actions, pickable, state.plans, links) }
        }
    }
}

private const val RESULT_PREFIX = "Runtime result "
private const val COMPLETED = "COMPLETED"
/** The tool a run ends itself with; not a step anyone asked for. */
private const val FINISH_TOOL = "finish"
private val SHOWN_ROLES = setOf(TranscriptRole.ASSISTANT, TranscriptRole.ERROR, TranscriptRole.NOTICE)
