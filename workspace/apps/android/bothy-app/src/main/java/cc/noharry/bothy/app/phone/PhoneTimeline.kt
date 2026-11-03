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
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.toHtmlArtifactPreview
import cc.noharry.bothy.app.console.uniqueKeys
import cc.noharry.bothy.app.overlay.generatedPicture
import cc.noharry.bothy.app.overlay.pickableReply
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.UiState

/** A row of the phone's conversation. */
internal sealed interface PhoneRow {
    val key: String

    data class Asked(val header: ConversationItem.TurnHeader) : PhoneRow {
        override val key = "asked-${header.id}"
    }

    data class Thought(val item: ConversationItem.MessageItem) : PhoneRow {
        override val key = "thought-${item.id}"
    }

    data class Tool(val item: ConversationItem.ToolCallItem) : PhoneRow {
        override val key = "tool-${item.id}"
    }

    /** [TOOL_GROUP_MIN] or more tool calls in a row, as one line. */
    data class ToolGroup(val tools: List<ConversationItem.ToolCallItem>) : PhoneRow {
        override val key = "tools-${tools.first().id}"
    }

    /** A reply, an error, a notice or an approval. */
    data class Shown(val item: ConversationItem) : PhoneRow {
        override val key = "item-${item.id}"
    }

    /** A turn that ended well, its process folded into one line ([rows]); the answer stays outside. */
    data class Folded(val turn: Long, val rows: List<PhoneRow>, val toolCalls: Int, val messages: Int) : PhoneRow {
        override val key = "fold-$turn"
    }

    /** Under a finished turn: copy its answer ([reply]), how many steps it took, and the way to its trajectory. */
    data class Footnote(val turn: Long, val reply: String?, val steps: Int, val failed: Boolean) : PhoneRow {
        override val key = "foot-$turn"
    }

    data class Made(val render: ConversationRenderItem) : PhoneRow {
        override val key = render.key
    }
}

/**
 * The conversation as a phone shows it, turn by turn (after the official DeepSeek Harness clients): the question; while
 * a turn runs, its thoughts, tools and words in order, [TOOL_GROUP_MIN] or more tools in a row as one line; once it
 * ended well, all of that folded into one line with the answer left outside and a footnote under it. A turn that failed
 * or was stopped stays open, so where it stopped shows. Status lines and the runtime's own entries are not shown.
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
    if (!live && !failed && reply != null) {
        val process = processRows(body.filter { it !== reply })
        if (process.isNotEmpty()) rows += PhoneRow.Folded(id, process, body.count { it.isTool() }, body.count { it.isInterim(reply) })
        rows += PhoneRow.Shown(reply)
    } else {
        rows += processRows(body)
    }
    if (!live && (reply != null || failed)) rows += PhoneRow.Footnote(id, reply?.content, body.count { it.isPhoneStep() }, failed)
    body.filterIsInstance<ConversationItem.ToolCallItem>().forEach { tool ->
        tool.toHtmlArtifactPreview()?.let { rows += PhoneRow.Made(ConversationRenderItem.HtmlArtifact(tool.id, it)) }
        tool.generatedPicture()?.let { rows += PhoneRow.Made(ConversationRenderItem.GeneratedImage(tool.id, it)) }
    }
    return rows
}

/** A turn's items in order as rows: thoughts and tools one line each (a run of tools grouped), the words as they are. */
private fun processRows(items: List<ConversationItem>): List<PhoneRow> {
    val rows = mutableListOf<PhoneRow>()
    val tools = mutableListOf<ConversationItem.ToolCallItem>()
    fun flush() {
        if (tools.size >= TOOL_GROUP_MIN) rows += PhoneRow.ToolGroup(tools.toList()) else tools.forEach { rows += PhoneRow.Tool(it) }
        tools.clear()
    }
    items.forEach { item ->
        when {
            item is ConversationItem.ToolCallItem -> if (item.isPhoneStep()) tools += item
            item is ConversationItem.MessageItem && item.role == TranscriptRole.THINKING -> { flush(); rows += PhoneRow.Thought(item) }
            item is ConversationItem.MessageItem && item.role in SHOWN_ROLES -> { flush(); rows += PhoneRow.Shown(item) }
            item is ConversationItem.ConfirmationItem -> { flush(); rows += PhoneRow.Shown(item) }
        }
    }
    flush()
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
internal fun PhoneTimeline(state: UiState, actions: ConsoleActions, onTrajectory: () -> Unit, modifier: Modifier) {
    val rows = remember(state.items, state.isRunning) { phoneRows(state.items, state.isRunning) }
    val keys = remember(rows) { rows.map { it.key }.uniqueKeys() }
    val bottomFirst = remember(rows) { rows.asReversed() }
    val pickable = remember(state) { state.pickableReply() }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size, state.isRunning) { if (rows.isNotEmpty()) list.animateScrollToItem(0) }
    // Room at the foot for 小博, who sits on the composer's edge, so the newest line is not under it.
    LazyColumn(modifier, state = list, reverseLayout = true, contentPadding = PaddingValues(bottom = MASCOT_ROOM), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(bottomFirst, key = { index, _ -> keys[keys.lastIndex - index] }) { _, row ->
            // One node per item: a reversed list would turn a row's own lines (a step and what it opens to) upside down.
            Column(Modifier.fillMaxWidth()) { PhoneRowView(row, actions, pickable, onTrajectory) }
        }
    }
}

internal const val TOOL_GROUP_MIN = 3
private val MASCOT_ROOM = 40.dp
private const val RESULT_PREFIX = "Runtime result "
private const val COMPLETED = "COMPLETED"
/** The tool a run ends itself with; not a step anyone asked for. */
private const val FINISH_TOOL = "finish"
private val SHOWN_ROLES = setOf(TranscriptRole.ASSISTANT, TranscriptRole.ERROR, TranscriptRole.NOTICE)
