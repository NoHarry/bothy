package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ConversationRenderRow(
    item: DebugConversationRenderItem,
    actions: DebugConsoleActions,
    streamingThinkingId: Long? = null,
    turnNumber: Int = 0,
    pickable: DebugPickableReply? = null,
) {
    when (item) {
        is DebugConversationRenderItem.Single -> ConversationItem(
            item = item.item,
            actions = actions,
            isStreamingThinking = item.item is DebugConversationItem.MessageItem &&
                item.item.role == DebugTranscriptRole.THINKING &&
                item.item.id == streamingThinkingId,
            pickable = pickable?.takeIf { it.messageId == item.item.id },
        )
        is DebugConversationRenderItem.ContextToolGroup -> ContextToolGroupRow(item)
        is DebugConversationRenderItem.BusinessToolGroup -> BusinessToolGroupRow(item, actions)
        is DebugConversationRenderItem.RuntimePhaseGroup -> RuntimePhaseGroupRow(item, actions)
        is DebugConversationRenderItem.TurnDivider -> TurnDividerRow(turnNumber)
        is DebugConversationRenderItem.HtmlArtifact -> HtmlArtifactRow(item.preview, actions)
        is DebugConversationRenderItem.GeneratedImage -> GeneratedImageRow(item.picture)
    }
}

/** Inline page preview message: header (path + bytes + fullscreen) over a live thumbnail. */
@Composable
private fun HtmlArtifactRow(preview: DebugArtifactPreview, actions: DebugConsoleActions) {
    val colors = DebugColors
    Column(Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = DebugIconFile,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.width(ARTIFACT_ROW_ICON_SIZE),
            )
            Spacer(Modifier.width(DebugDimens.spaceXsMd))
            Text(
                text = preview.path,
                style = DebugType.meta,
                color = colors.text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(
                text = "${preview.bytes.formatCount()} bytes",
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Icon(
                imageVector = DebugIconFullscreen,
                contentDescription = "Open fullscreen preview",
                tint = colors.muted,
                modifier = Modifier
                    .width(ARTIFACT_ROW_ICON_SIZE)
                    .clickable { actions.openHtmlViewer(preview.path, preview.html) },
            )
        }
        Spacer(Modifier.height(DebugDimens.spaceXs))
        DebugHtmlPreviewBox(
            html = preview.html,
            refreshToken = preview.refreshToken,
            onOpen = { actions.openHtmlViewer(preview.path, preview.html) },
            modifier = Modifier
                .fillMaxWidth()
                .height(ARTIFACT_ROW_PREVIEW_HEIGHT),
        )
    }
}

private val ARTIFACT_ROW_ICON_SIZE = 16.dp
private val ARTIFACT_ROW_PREVIEW_HEIGHT = 200.dp

@Composable
private fun ContextToolGroupRow(group: DebugConversationRenderItem.ContextToolGroup) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val status = group.status()
    val elapsed = groupElapsedLabel(group.tools, group.key, status)
    TimelineRow(status.color(), null) {
        GroupHeader(
            summary = group.summaryLabel(),
            expanded = expanded,
            meta = listOfNotNull(status.name.lowercase(), elapsed).joinToString(" · "),
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(DebugDimens.spaceXsMd))
            Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
                group.tools.forEach { tool -> ContextToolLine(tool) }
            }
        }
    }
}

@Composable
private fun BusinessToolGroupRow(
    group: DebugConversationRenderItem.BusinessToolGroup,
    actions: DebugConsoleActions,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val status = group.status()
    val elapsed = groupElapsedLabel(group.tools, group.key, status)
    val running = status == DebugToolCallStatus.RUNNING
    GroupCard(
        summary = group.summaryLabel(),
        expanded = expanded,
        elapsed = elapsed,
        statusColor = if (running) DebugColors.activity else status.color(),
        pulsing = running,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
            group.tools.forEach { tool -> ConversationItem(tool, actions) }
        }
    }
}

@Composable
private fun RuntimePhaseGroupRow(
    group: DebugConversationRenderItem.RuntimePhaseGroup,
    actions: DebugConsoleActions,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    GroupCard(
        summary = group.summaryLabel(),
        expanded = expanded,
        elapsed = null,
        statusColor = DebugColors.muted,
        pulsing = false,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs)) {
            group.phases.forEach { phase -> ConversationItem(phase, actions) }
        }
    }
}

@Composable
private fun TurnDividerRow(turnNumber: Int) {
    DebugTurnDivider(label = if (turnNumber > 0) "turn $turnNumber" else "turn")
}

/** Group card aligned with the collapsed ToolRow: same container, ramp, and status dot. */
@Composable
private fun GroupCard(
    summary: String,
    expanded: Boolean,
    elapsed: String?,
    statusColor: Color,
    pulsing: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = DebugColors
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = RoundedCornerShape(DebugDimens.radiusMd),
            color = colors.elevated,
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = DebugDimens.spaceMd, vertical = DebugDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DebugDisclosure(expanded = expanded)
                Spacer(Modifier.width(DebugDimens.spaceXs))
                Text(
                    text = summary,
                    style = DebugType.code,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (elapsed != null) {
                    Spacer(Modifier.width(DebugDimens.spaceSm))
                    Text(elapsed, style = DebugType.meta, color = colors.muted, maxLines = 1)
                }
                Spacer(Modifier.width(DebugDimens.spaceSm))
                DebugStatusDot(color = statusColor, pulsing = pulsing)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(DebugDimens.spaceXs))
            Column(Modifier.padding(start = DebugDimens.spaceMd)) { content() }
        }
    }
}

@Composable
private fun GroupHeader(
    summary: String,
    expanded: Boolean,
    meta: String,
    onToggle: () -> Unit,
) {
    val colors = DebugColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DebugDisclosure(expanded = expanded)
        Spacer(Modifier.width(DebugDimens.spaceXs))
        Text(
            text = summary,
            style = DebugType.label,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Text(meta, style = DebugType.meta, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun ContextToolLine(item: DebugConversationItem.ToolCallItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val colors = DebugColors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugDisclosure(expanded = expanded)
            Spacer(Modifier.width(DebugDimens.spaceXs))
            Text(
                text = item.contextLookupLabel(),
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Text(
                text = listOfNotNull(item.status.name.lowercase(), toolElapsedLabel(item)).joinToString(" · "),
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
            )
        }
        if (expanded) ContextToolDetails(item)
    }
}

@Composable
private fun ContextToolDetails(item: DebugConversationItem.ToolCallItem) {
    Spacer(Modifier.height(DebugDimens.spaceXsMd))
    Column(
        Modifier.fillMaxWidth().padding(start = DebugDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXsMd),
    ) {
        item.argumentsJson?.let { ToolDetailDisclosure("Arguments", it, defaultExpanded = true) }
        item.resultJson?.let { ToolDetailDisclosure("Result", it, defaultExpanded = true) }
    }
}

@Composable
private fun groupElapsedLabel(
    tools: List<DebugConversationItem.ToolCallItem>,
    groupKey: String,
    status: DebugToolCallStatus,
): String? {
    var now by remember(groupKey, status) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(groupKey, status) {
        if (status == DebugToolCallStatus.RUNNING) {
            while (true) {
                delay(GROUP_ELAPSED_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }
    val start = tools.minOfOrNull { it.startedAt } ?: return null
    val end = if (status == DebugToolCallStatus.RUNNING) now else tools.mapNotNull { it.finishedAt }.maxOrNull()
    return end?.let { formatGroupElapsed((it - start).coerceAtLeast(0L)) }
}

private fun formatGroupElapsed(millis: Long): String =
    if (millis < GROUP_MILLIS_PER_SECOND) {
        "${millis}ms"
    } else {
        "%.1fs".format(millis / GROUP_MILLIS_PER_SECOND.toDouble())
    }

private const val GROUP_MILLIS_PER_SECOND = 1000L
private const val GROUP_ELAPSED_TICK_MS = 1000L
