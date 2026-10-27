package cc.noharry.bothy.app.console
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
import cc.noharry.bothy.app.console.ui.Disclosure
import cc.noharry.bothy.app.console.ui.HtmlPreviewBox
import cc.noharry.bothy.app.console.ui.IconFile
import cc.noharry.bothy.app.console.ui.IconFullscreen
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.console.ui.TurnDividerLine
import cc.noharry.bothy.app.image.GeneratedImageRow
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.overlay.PickableReply
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import kotlinx.coroutines.delay

@Composable
internal fun ConversationRenderRow(
    item: ConversationRenderItem,
    actions: ConsoleActions,
    streamingThinkingId: Long? = null,
    turnNumber: Int = 0,
    pickable: PickableReply? = null,
) {
    when (item) {
        is ConversationRenderItem.Single -> ConversationItemView(
            item = item.item,
            actions = actions,
            isStreamingThinking = item.item is ConversationItem.MessageItem &&
                item.item.role == TranscriptRole.THINKING &&
                item.item.id == streamingThinkingId,
            pickable = pickable?.takeIf { it.messageId == item.item.id },
        )
        is ConversationRenderItem.ContextToolGroup -> ContextToolGroupRow(item)
        is ConversationRenderItem.BusinessToolGroup -> BusinessToolGroupRow(item, actions)
        is ConversationRenderItem.RuntimePhaseGroup -> RuntimePhaseGroupRow(item, actions)
        is ConversationRenderItem.TurnDivider -> TurnDividerRow(turnNumber)
        is ConversationRenderItem.HtmlArtifact -> HtmlArtifactRow(item.preview, actions)
        is ConversationRenderItem.GeneratedImage -> GeneratedImageRow(item.picture)
    }
}

/** Inline page preview message: header (path + bytes + fullscreen) over a live thumbnail. */
@Composable
private fun HtmlArtifactRow(preview: ArtifactPreview, actions: ConsoleActions) {
    val colors = ConsoleColors
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = IconFile,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.width(ARTIFACT_ROW_ICON_SIZE),
            )
            Spacer(Modifier.width(Dimens.spaceXsMd))
            Text(
                text = preview.path,
                style = ConsoleType.meta,
                color = colors.text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Dimens.spaceSm))
            Text(
                text = "${preview.bytes.formatCount()} bytes",
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(Dimens.spaceSm))
            Icon(
                imageVector = IconFullscreen,
                contentDescription = "Open fullscreen preview",
                tint = colors.muted,
                modifier = Modifier
                    .width(ARTIFACT_ROW_ICON_SIZE)
                    .clickable { actions.openHtmlViewer(preview.path, preview.html) },
            )
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        HtmlPreviewBox(
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
private fun ContextToolGroupRow(group: ConversationRenderItem.ContextToolGroup) {
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
            Spacer(Modifier.height(Dimens.spaceXsMd))
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                group.tools.forEach { tool -> ContextToolLine(tool) }
            }
        }
    }
}

@Composable
private fun BusinessToolGroupRow(
    group: ConversationRenderItem.BusinessToolGroup,
    actions: ConsoleActions,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val status = group.status()
    val elapsed = groupElapsedLabel(group.tools, group.key, status)
    val running = status == ToolCallStatus.RUNNING
    GroupCard(
        summary = group.summaryLabel(),
        expanded = expanded,
        elapsed = elapsed,
        statusColor = if (running) ConsoleColors.activity else status.color(),
        pulsing = running,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            group.tools.forEach { tool -> ConversationItemView(tool, actions) }
        }
    }
}

@Composable
private fun RuntimePhaseGroupRow(
    group: ConversationRenderItem.RuntimePhaseGroup,
    actions: ConsoleActions,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    GroupCard(
        summary = group.summaryLabel(),
        expanded = expanded,
        elapsed = null,
        statusColor = ConsoleColors.muted,
        pulsing = false,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            group.phases.forEach { phase -> ConversationItemView(phase, actions) }
        }
    }
}

@Composable
private fun TurnDividerRow(turnNumber: Int) {
    TurnDividerLine(label = if (turnNumber > 0) "turn $turnNumber" else "turn")
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
    val colors = ConsoleColors
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = RoundedCornerShape(Dimens.radiusMd),
            color = colors.elevated,
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Disclosure(expanded = expanded)
                Spacer(Modifier.width(Dimens.spaceXs))
                Text(
                    text = summary,
                    style = ConsoleType.code,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (elapsed != null) {
                    Spacer(Modifier.width(Dimens.spaceSm))
                    Text(elapsed, style = ConsoleType.meta, color = colors.muted, maxLines = 1)
                }
                Spacer(Modifier.width(Dimens.spaceSm))
                StatusDot(color = statusColor, pulsing = pulsing)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(Dimens.spaceXs))
            Column(Modifier.padding(start = Dimens.spaceMd)) { content() }
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
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Disclosure(expanded = expanded)
        Spacer(Modifier.width(Dimens.spaceXs))
        Text(
            text = summary,
            style = ConsoleType.label,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(meta, style = ConsoleType.meta, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun ContextToolLine(item: ConversationItem.ToolCallItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val colors = ConsoleColors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disclosure(expanded = expanded)
            Spacer(Modifier.width(Dimens.spaceXs))
            Text(
                text = item.contextLookupLabel(),
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Dimens.spaceSm))
            Text(
                text = listOfNotNull(item.status.name.lowercase(), toolElapsedLabel(item)).joinToString(" · "),
                style = ConsoleType.meta,
                color = colors.muted,
                maxLines = 1,
            )
        }
        if (expanded) ContextToolDetails(item)
    }
}

@Composable
private fun ContextToolDetails(item: ConversationItem.ToolCallItem) {
    Spacer(Modifier.height(Dimens.spaceXsMd))
    Column(
        Modifier.fillMaxWidth().padding(start = Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXsMd),
    ) {
        item.argumentsJson?.let { ToolDetailDisclosure("Arguments", it, defaultExpanded = true) }
        item.resultJson?.let { ToolDetailDisclosure("Result", it, defaultExpanded = true) }
    }
}

@Composable
private fun groupElapsedLabel(
    tools: List<ConversationItem.ToolCallItem>,
    groupKey: String,
    status: ToolCallStatus,
): String? {
    var now by remember(groupKey, status) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(groupKey, status) {
        if (status == ToolCallStatus.RUNNING) {
            while (true) {
                delay(GROUP_ELAPSED_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
    }
    val start = tools.minOfOrNull { it.startedAt } ?: return null
    val end = if (status == ToolCallStatus.RUNNING) now else tools.mapNotNull { it.finishedAt }.maxOrNull()
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
