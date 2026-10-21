package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState

@Composable
internal fun ConversationItem(
    item: DebugConversationItem,
    actions: DebugConsoleActions,
    isStreamingThinking: Boolean = false,
    pickable: DebugPickableReply? = null,
) {
    when (item) {
        is DebugConversationItem.TurnHeader -> TurnHeaderRow(item, actions)
        is DebugConversationItem.MessageItem -> MessageRow(item, actions, isStreamingThinking, pickable)
        is DebugConversationItem.RuntimePhaseItem -> RuntimePhaseRow(item, actions)
        is DebugConversationItem.ToolCallItem -> ToolRow(item, actions)
        is DebugConversationItem.ConfirmationItem -> ConfirmationRow(item, actions)
    }
}

@Composable
private fun TurnHeaderRow(item: DebugConversationItem.TurnHeader, actions: DebugConsoleActions) {
    val colors = DebugColors
    val body = if (item.collapsed) item.preview else item.fullText
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DebugDimens.spaceXs),
    ) {
        Text(item.label, style = DebugType.label, color = colors.tertiary)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.toggleItem(item.copy(collapsed = !item.collapsed)) },
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            color = colors.userBubble,
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Text(
                text = body,
                style = DebugType.body,
                color = colors.text,
                maxLines = if (item.collapsed) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(DebugDimens.spaceMd),
            )
        }
    }
}

@Composable
private fun MessageRow(
    item: DebugConversationItem.MessageItem,
    actions: DebugConsoleActions,
    isStreamingThinking: Boolean = false,
    pickable: DebugPickableReply? = null,
) {
    val colors = DebugColors
    // Assistant: body only (CC/Codex — no loud "Assistant" chrome).
    if (item.role == DebugTranscriptRole.ASSISTANT) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 2.dp),
        ) {
            if (pickable == null) {
                DebugMarkdown(item.content)
            } else {
                // The listed picks become the A2UI rows below; the rest of the reply stays text.
                DebugMarkdown(pickable.reply.text)
                Spacer(Modifier.height(DebugDimens.spaceSm))
                DebugConsoleWidgets(pickable, actions.submit)
            }
        }
        return
    }
    // Thinking: fixed one-line stream (tail) while live; collapsed one-line when done;
    // tap expands full body only after the stream settles.
    if (item.role == DebugTranscriptRole.THINKING) {
        // 显示思考过程 off: the model's thinking is not shown at all.
        if (!DebugDisplaySettings.showThinking.collectAsState().value) return
        val streaming = isStreamingThinking
        val expanded = !streaming && !item.collapsed
        val line = thinkingDisplayLine(item.content, streaming = streaming, collapsed = item.collapsed)
        TimelineRow(
            accent = if (streaming) colors.activity else colors.tertiary,
            onClick = if (streaming) {
                null
            } else {
                { actions.toggleItem(item.copy(collapsed = !item.collapsed)) }
            },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (streaming) {
                    DebugStatusDot(color = colors.activity, pulsing = true, modifier = Modifier.size(7.dp))
                } else {
                    DebugDisclosure(expanded = expanded)
                }
                Spacer(Modifier.width(DebugDimens.spaceSm))
                Text(
                    text = line,
                    style = DebugType.meta,
                    color = colors.muted,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(DebugDimens.spaceXsMd))
                Text(
                    text = item.content,
                    style = DebugType.body,
                    color = colors.muted,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        return
    }
    // STATUS is filtered in toRenderItems; keep a quiet branch for safety.
    if (item.role == DebugTranscriptRole.STATUS) {
        return
    }
    val expandable = item.role != DebugTranscriptRole.ERROR
    val collapsed = expandable && item.collapsed
    val toggle = if (expandable) ({ actions.toggleItem(item.copy(collapsed = !item.collapsed)) }) else null
    val rowColor = if (item.role == DebugTranscriptRole.ERROR) colors.danger else colors.muted
    TimelineRow(item.role.color(), toggle) {
        Text(item.role.compactTitle(), style = DebugType.meta, color = rowColor)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        when {
            collapsed -> PreviewText(item.content)
            else -> Text(item.content, style = DebugType.body, color = colors.text)
        }
    }
}

/**
 * Streaming: fixed-width tail so new tokens appear to push left (like a ticker).
 * Collapsed/done: head summary. Expanded header stays a short "Thinking" label.
 */
internal fun thinkingDisplayLine(
    content: String,
    streaming: Boolean,
    collapsed: Boolean,
    maxChars: Int = THINKING_ONE_LINE_CHARS,
): String {
    val preview = content.lineSequence().joinToString(" ").trim().replace(WHITESPACE_RUN, " ")
    if (preview.isBlank()) return if (streaming) "Thinking…" else "Thinking"
    if (streaming) {
        val tail = if (preview.length <= maxChars) preview else "…${preview.takeLast(maxChars)}"
        return "Thinking · $tail"
    }
    if (!collapsed) return "Thinking"
    val head = if (preview.length <= maxChars) preview else "${preview.take(maxChars)}…"
    return "Thinking · $head"
}

@Composable
private fun RuntimePhaseRow(item: DebugConversationItem.RuntimePhaseItem, actions: DebugConsoleActions) {
    val colors = DebugColors
    TimelineRow(colors.muted, { actions.toggleItem(item.copy(collapsed = !item.collapsed)) }) {
        Text(item.title, style = DebugType.meta, color = colors.muted)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        when {
            item.collapsed -> PreviewText(item.body)
            item.markdown -> DebugMarkdown(item.body)
            else -> DebugCodeBlock(item.body)
        }
    }
}

@Composable
private fun ToolRow(
    item: DebugConversationItem.ToolCallItem,
    actions: DebugConsoleActions,
) {
    val colors = DebugColors
    val elapsed = toolElapsedLabel(item)
    val running = item.status == DebugToolCallStatus.RUNNING
    val statusColor = if (running) colors.activity else item.status.color()
    val expanded = !item.collapsed
    // Expanded always shows args/result when present. Users open a tool card
    // specifically to inspect the payload — e.g. web_search query.
    val summary = item.businessToolLineLabel()
        .removePrefix(item.toolName)
        .trimStart()
        .trimStart('·')
        .trim()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.toggleItem(item.copy(collapsed = !item.collapsed)) }
                    .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DebugDisclosure(expanded = expanded)
                Spacer(Modifier.width(DebugDimens.spaceXs))
                Text(
                    text = item.toolName,
                    style = DebugType.code,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (summary.isNotBlank()) {
                    Spacer(Modifier.width(DebugDimens.spaceXs))
                    Text(
                        text = summary,
                        style = DebugType.meta,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(DebugDimens.spaceSm))
                DebugStatusDot(color = statusColor, pulsing = running)
                if (elapsed != null) {
                    Spacer(Modifier.width(DebugDimens.spaceSm))
                    Text(elapsed, style = DebugType.meta, color = colors.muted, maxLines = 1)
                }
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderSubtle),
                )
                ToolDetails(
                    item = item,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceMd),
                )
            }
        }
    }
}

@Composable
private fun ConfirmationRow(item: DebugConversationItem.ConfirmationItem, actions: DebugConsoleActions) {
    val colors = DebugColors
    val pending = item.status == DebugConfirmationStatus.PENDING
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DebugDimens.spaceXs),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = colors.elevated,
        border = BorderStroke(
            1.dp,
            if (pending) colors.warning.copy(alpha = 0.5f) else colors.border,
        ),
    ) {
        ConfirmationContent(item = item, actions = actions, pending = pending)
    }
}

@Composable
private fun ConfirmationContent(
    item: DebugConversationItem.ConfirmationItem,
    actions: DebugConsoleActions,
    pending: Boolean,
) {
    val colors = DebugColors
    Column(Modifier.padding(DebugDimens.spaceMd)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DebugStatusDot(color = item.status.color())
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(
                text = "Network approval",
                style = DebugType.body,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(item.status.compactLabel(), style = DebugType.meta, color = colors.muted, maxLines = 1)
        }
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Text(
            text = item.domain.ifBlank { item.pendingId },
            style = DebugType.meta,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Mode: ${item.mode.label}",
            style = DebugType.meta,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (pending) {
            Spacer(Modifier.height(DebugDimens.spaceSm))
            DebugApproveDenyRow(
                onApprove = { actions.approveConfirmation(item.pendingId) },
                onDeny = { actions.denyConfirmation(item.pendingId) },
            )
        }
    }
}

@Composable
private fun ToolDetails(
    item: DebugConversationItem.ToolCallItem,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    // Expanded tool row must surface full payload immediately — no second Expand click.
    val args = item.argumentsJson?.takeIf { it.isNotBlank() }
    val result = item.resultJson?.takeIf { it.isNotBlank() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
            ToolMetaChip("Status", item.status.name.lowercase())
            toolElapsedLabel(item)?.let { ToolMetaChip("Elapsed", it) }
        }
        if (item.toolCallId.isNotBlank()) {
            Text(
                text = item.toolCallId,
                style = DebugType.meta,
                color = colors.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (args == null && result == null) {
            Text(
                if (item.status == DebugToolCallStatus.RUNNING) {
                    "Waiting for arguments / result…"
                } else {
                    "No arguments or result payload recorded."
                },
                style = DebugType.meta,
                color = colors.muted,
            )
        } else {
            args?.let { body -> ToolPayloadBlock(label = "Arguments", body = body) }
            result?.let { body -> ToolPayloadBlock(label = "Result", body = body) }
        }
    }
}

/** Full payload block shown when a tool row is open — always expanded, scrollable, pretty JSON. */
@Composable
private fun ToolPayloadBlock(label: String, body: String) {
    val colors = DebugColors
    val display = remember(body) { prettyToolPayload(body) }
    Column(Modifier.fillMaxWidth()) {
        Text(text = label, style = DebugType.label, color = colors.tertiary)
        Spacer(Modifier.height(DebugDimens.spaceXs))
        DebugCodeBlock(display)
    }
}

@Composable
private fun ToolMetaChip(label: String, value: String) {
    val colors = DebugColors
    Surface(
        shape = RoundedCornerShape(DebugDimens.radiusFull),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Text(
            text = "$label · $value",
            style = DebugType.label,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = DebugDimens.spaceXs, vertical = DebugDimens.spaceXxs),
        )
    }
}

@Composable
internal fun TimelineRow(accent: Color, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(Modifier.fillMaxWidth().then(click).padding(vertical = DebugDimens.spaceXs)) {
        DebugStatusDot(color = accent, modifier = Modifier.padding(top = 7.dp).size(7.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PreviewText(text: String) {
    val colors = DebugColors
    Text(
        text = text.compactPreview(),
        style = DebugType.meta,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ToolDetailDisclosure(label: String, body: String, defaultExpanded: Boolean) {
    // Context tool groups also prefer full body when opened (no second nested click).
    if (defaultExpanded) {
        ToolPayloadBlock(label = label, body = body)
    } else {
        ToolDetailSection(label = label, body = body, initiallyExpanded = false)
    }
}

@Composable
internal fun ToolDetailSection(
    label: String,
    body: String,
    initiallyExpanded: Boolean,
    maxCollapsedLines: Int = 4,
) {
    val colors = DebugColors
    var expanded by remember(label, body) { mutableStateOf(initiallyExpanded) }
    val display = remember(body) { prettyToolPayload(body) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = DebugDimens.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugDisclosure(expanded = expanded)
            Spacer(Modifier.width(DebugDimens.spaceXs))
            Text(
                text = label,
                style = DebugType.label,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(DebugDimens.spaceXs))
        if (expanded) {
            DebugCodeBlock(display)
        } else {
            Text(
                text = display,
                style = DebugType.code,
                color = colors.muted,
                maxLines = maxCollapsedLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun toolElapsedLabel(item: DebugConversationItem.ToolCallItem): String? {
    var now by remember(item.id, item.status, item.finishedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.id, item.status, item.finishedAt) {
        if (item.status == DebugToolCallStatus.RUNNING && item.finishedAt == null) {
            while (true) {
                delay(RUNNING_ELAPSED_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }
    val end = item.finishedAt ?: if (item.status == DebugToolCallStatus.RUNNING) now else null
    return end?.let { formatElapsed((it - item.startedAt).coerceAtLeast(0L)) }
}

@Composable
private fun DebugTranscriptRole.color(): Color {
    val colors = DebugColors
    return when (this) {
        DebugTranscriptRole.ERROR -> colors.danger
        DebugTranscriptRole.THINKING -> colors.warning
        DebugTranscriptRole.RUNTIME -> colors.muted
        DebugTranscriptRole.STATUS -> colors.muted
        DebugTranscriptRole.ASSISTANT -> colors.success
        DebugTranscriptRole.NOTICE -> colors.accent
    }
}

@Composable
internal fun DebugToolCallStatus.color(): Color {
    val colors = DebugColors
    return when (this) {
        DebugToolCallStatus.DONE -> colors.success
        DebugToolCallStatus.FAILED -> colors.danger
        DebugToolCallStatus.UNKNOWN -> colors.warning
        DebugToolCallStatus.RUNNING -> colors.accent
    }
}

@Composable
internal fun DebugConfirmationStatus.color(): Color {
    val colors = DebugColors
    return when (this) {
        DebugConfirmationStatus.APPROVED,
        DebugConfirmationStatus.AUTO_APPROVED,
        DebugConfirmationStatus.CONSUMED
        -> colors.success
        DebugConfirmationStatus.DENIED,
        DebugConfirmationStatus.AUTO_DENIED,
        DebugConfirmationStatus.FAILED
        -> colors.danger
        DebugConfirmationStatus.EXPIRED -> colors.warning
        DebugConfirmationStatus.PENDING -> colors.accent
    }
}

private fun String.compactPreview(): String =
    lineSequence().joinToString(" ").let { if (it.length <= PREVIEW_CHARS) it else it.take(PREVIEW_CHARS) + "..." }

private fun formatElapsed(millis: Long): String =
    if (millis < MILLIS_PER_SECOND) "${millis}ms" else "%.1fs".format(millis / MILLIS_PER_SECOND.toDouble())

private const val PREVIEW_CHARS = 140
private const val THINKING_ONE_LINE_CHARS = 72
private val WHITESPACE_RUN = Regex("""\s+""")
private const val MILLIS_PER_SECOND = 1000L
private const val RUNNING_ELAPSED_TICK_MS = 1000L
