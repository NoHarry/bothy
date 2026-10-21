package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Run groups and record rows.
 *
 * Search highlights and dims rather than filtering, so surrounding context stays visible, and
 * the scroll anchor is restored after older runs are prepended.
 */
@Composable
internal fun TrajectoryLedger(
    snapshot: TrajectorySnapshot,
    collapsedRunIds: Set<String>,
    collapsedToolCallIds: Set<String>,
    selectedRecordId: TrajectoryRecordId?,
    searchHits: Set<TrajectoryRecordId>,
    dimmedRecordIds: Set<TrajectoryRecordId>,
    onToggleRun: (String) -> Unit,
    onToggleToolCall: (String) -> Unit,
    onSelectRecord: (TrajectoryRecordId) -> Unit,
    onRetryRun: (String) -> Unit,
    onLoadOlderRuns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val anchorKey = remember(snapshot.runGroups.firstOrNull()?.runId) {
        snapshot.runGroups.firstOrNull()?.runId
    }
    // Prepending older run headers must not teleport the viewport away from the current row.
    LaunchedEffect(anchorKey) { listState.scrollToItem(listState.firstVisibleItemIndex) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs),
    ) {
        snapshot.runGroups.forEach { group ->
            item(key = "run-${group.runId}") {
                TrajectoryRunHeader(
                    group = group,
                    collapsed = group.runId in collapsedRunIds,
                    loading = group.runId in snapshot.loadingRunIds,
                    onToggle = { onToggleRun(group.runId) },
                )
            }
            if (group.runId in collapsedRunIds) return@forEach
            group.records.forEach { record ->
                val callId = record.correlation.toolCallId
                val collapsedTool = callId != null && callId in collapsedToolCallIds
                // A collapsed tool call keeps one summary row instead of just hiding its finish.
                if (collapsedTool && record.kind == TrajectoryKind.TOOL_FINISH) return@forEach
                item(key = record.id.value) {
                    TrajectoryRecordRow(
                        record = record,
                        selected = record.id == selectedRecordId,
                        isHit = record.id in searchHits,
                        dimmed = record.id in dimmedRecordIds,
                        collapsedToolSummary = if (collapsedTool && record.kind == TrajectoryKind.TOOL_START) {
                            group.toolCallSummary(callId)
                        } else {
                            null
                        },
                        onClick = { onSelectRecord(record.id) },
                        onToggleToolCall = { callId?.let(onToggleToolCall) },
                    )
                }
            }
            if (group.loadFailed) {
                item(key = "retry-${group.runId}") {
                    TrajectoryRetryRow(group.health.safeReasonCode) { onRetryRun(group.runId) }
                }
            }
        }
        if (snapshot.hasOlderRuns) {
            item(key = "history-boundary") {
                TrajectoryHistoryBoundary(
                    remaining = snapshot.totalKnownRunCount - snapshot.visibleRunCount,
                    onLoadOlder = onLoadOlderRuns,
                )
            }
        }
    }
}

@Composable
private fun TrajectoryRunHeader(
    group: TrajectoryRunGroup,
    collapsed: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DebugColors
    val summary = when {
        loading -> "loading…"
        group.loadFailed -> "load failed"
        !group.recordsLoaded -> "not loaded"
        else -> "${group.records.size} events · ${group.toolCallCount} tool calls · ${group.lifecycleStatus}"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = "Run ${group.runId}, $summary, EventLog ${group.health.badgeLabel}" +
                    if (collapsed) ", collapsed" else ", expanded"
                role = Role.Button
            },
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DebugDimens.spaceSm, vertical = DebugDimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugDisclosure(expanded = !collapsed)
            Spacer(Modifier.width(DebugDimens.spaceXs))
            Text(
                text = group.runId,
                style = DebugType.code,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summary,
                style = DebugType.label,
                color = if (group.loadFailed) colors.danger else colors.muted,
                maxLines = 1,
            )
            if (group.isActive) {
                Spacer(Modifier.width(DebugDimens.spaceXs))
                TrajectoryBadge("Live", colors.activity)
            }
        }
    }
}

@Composable
private fun TrajectoryRecordRow(
    record: TrajectoryRecord,
    selected: Boolean,
    isHit: Boolean,
    dimmed: Boolean,
    collapsedToolSummary: String?,
    onClick: () -> Unit,
    onToggleToolCall: () -> Unit,
) {
    val colors = DebugColors
    if (record.kind == TrajectoryKind.GAP || record.kind == TrajectoryKind.LOAD_ERROR) {
        TrajectoryBoundaryRow(record)
        return
    }
    val alpha = if (dimmed) DIMMED_ALPHA else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    selected -> colors.accentSoft
                    isHit -> colors.activitySoft
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                shape = RoundedCornerShape(DebugDimens.radiusSm),
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = record.rowDescription(dimmed, isHit, collapsedToolSummary)
                role = Role.Button
                this.selected = selected
            }
            .padding(horizontal = DebugDimens.spaceSm, vertical = DebugDimens.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = record.sequence?.toString() ?: "—",
            style = DebugType.label,
            color = colors.tertiary.copy(alpha = alpha),
            modifier = Modifier.width(SEQUENCE_WIDTH),
        )
        Text(
            text = record.title,
            style = DebugType.code,
            color = colors.text.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (record.correlation.toolCallId != null) {
            TrajectoryTextButton("Group", onToggleToolCall)
        }
        if (record.isLive) {
            Text(text = "live", style = DebugType.label, color = colors.activity.copy(alpha = alpha))
            Spacer(Modifier.width(DebugDimens.spaceXs))
        }
        Text(
            text = collapsedToolSummary ?: record.summary.take(ROW_SUMMARY_CHARS),
            style = DebugType.label,
            color = colors.muted.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `toolName · duration · status` for a collapsed tool call, matching the run-header summary shape. */
internal fun TrajectoryRunGroup.toolCallSummary(callId: String?): String {
    if (callId == null) return ""
    val phases = records.filter { it.correlation.toolCallId == callId }
    val toolName = phases.firstNotNullOfOrNull { it.safeMetadata["toolName"] } ?: phases.firstOrNull()?.title.orEmpty()
    val duration = toolCallDurationMs(records, callId)?.let(::formatTrajectoryDuration) ?: "—"
    val finish = phases.firstOrNull { it.kind == TrajectoryKind.TOOL_FINISH }
    val status = finish?.safeMetadata?.get("status") ?: finish?.status ?: "running"
    return "$toolName · $duration · $status"
}

internal fun toolCallDurationMs(records: List<TrajectoryRecord>, callId: String?): Long? {
    if (callId.isNullOrBlank()) return null
    val start = records.firstOrNull {
        it.correlation.toolCallId == callId && it.kind == TrajectoryKind.TOOL_START
    }?.occurredAtEpochMillis ?: return null
    val finish = records.firstOrNull {
        it.correlation.toolCallId == callId && it.kind == TrajectoryKind.TOOL_FINISH
    }?.occurredAtEpochMillis ?: return null
    return (finish - start).coerceAtLeast(0L)
}

@Composable
private fun TrajectoryRetryRow(reasonCode: String?, onRetry: () -> Unit) {
    val colors = DebugColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reasonCode?.let { "reason=$it" }.orEmpty(),
            style = DebugType.label,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        TrajectoryTextButton("Retry", onRetry)
    }
}

private fun TrajectoryRecord.rowDescription(
    dimmed: Boolean,
    isHit: Boolean,
    collapsedToolSummary: String?,
): String = buildString {
    append(sequence?.let { "sequence $it" } ?: "not persisted")
    append(", ")
    append(kind.name.lowercase().replace('_', ' '))
    append(", ")
    append(title)
    status?.let { append(", status $it") }
    if (isLive) append(", live only")
    if (isHit) append(", search hit")
    if (dimmed) append(", outside selected range")
    collapsedToolSummary?.let { append(", collapsed: $it") }
}

@Composable
private fun TrajectoryBoundaryRow(record: TrajectoryRecord) {
    val colors = DebugColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusSm),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.warning.copy(alpha = BOUNDARY_BORDER_ALPHA)),
    ) {
        Column(Modifier.fillMaxWidth().padding(DebugDimens.spaceSm)) {
            Text(record.title, style = DebugType.meta, color = colors.text)
            if (record.summary.isNotBlank()) {
                Text(record.summary, style = DebugType.label, color = colors.muted)
            }
        }
    }
}

@Composable
private fun TrajectoryHistoryBoundary(remaining: Int, onLoadOlder: () -> Unit) {
    val colors = DebugColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$remaining older run${if (remaining == 1) "" else "s"}",
            style = DebugType.label,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        TrajectoryTextButton("Load older", onLoadOlder)
    }
}

private val SEQUENCE_WIDTH = 36.dp
private const val ROW_SUMMARY_CHARS = 160
private const val DIMMED_ALPHA = 0.45f
private const val BOUNDARY_BORDER_ALPHA = 0.5f
