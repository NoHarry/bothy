package cc.noharry.bothy.app.trajectory
import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.view.toolCallSummary
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.ui.Disclosure

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
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
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
    val colors = ConsoleColors
    val summary = when {
        loading -> stringResource(R.string.trajectory_run_loading)
        group.loadFailed -> stringResource(R.string.trajectory_run_load_failed)
        !group.recordsLoaded -> stringResource(R.string.trajectory_run_not_loaded)
        else -> stringResource(
            R.string.trajectory_run_summary,
            group.records.size,
            group.toolCallCount,
            group.lifecycleStatus,
        )
    }
    val description = stringResource(
        if (collapsed) R.string.trajectory_run_description_collapsed else R.string.trajectory_run_description_expanded,
        group.runId,
        summary,
        stringResource(group.health.eventLogStatus.labelRes()),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disclosure(expanded = !collapsed)
            Spacer(Modifier.width(Dimens.spaceXs))
            Text(
                text = group.runId,
                style = ConsoleType.code,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summary,
                style = ConsoleType.label,
                color = if (group.loadFailed) colors.danger else colors.muted,
                maxLines = 1,
            )
            if (group.isActive) {
                Spacer(Modifier.width(Dimens.spaceXs))
                TrajectoryBadge(stringResource(R.string.trajectory_live_badge), colors.activity)
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
    val colors = ConsoleColors
    if (record.kind == TrajectoryKind.GAP || record.kind == TrajectoryKind.LOAD_ERROR) {
        TrajectoryBoundaryRow(record)
        return
    }
    val alpha = if (dimmed) DIMMED_ALPHA else 1f
    val description = record.rowDescription(LocalContext.current.resources, dimmed, isHit, collapsedToolSummary)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    selected -> colors.accentSoft
                    isHit -> colors.activitySoft
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                shape = RoundedCornerShape(Dimens.radiusSm),
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            }
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = record.sequence?.toString() ?: "—",
            style = ConsoleType.label,
            color = colors.tertiary.copy(alpha = alpha),
            modifier = Modifier.width(SEQUENCE_WIDTH),
        )
        Text(
            text = record.displayTitle(),
            style = ConsoleType.code,
            color = colors.text.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (record.correlation.toolCallId != null) {
            TrajectoryTextButton(stringResource(R.string.trajectory_group), onToggleToolCall)
        }
        if (record.isLive) {
            Text(
                text = stringResource(R.string.trajectory_live),
                style = ConsoleType.label,
                color = colors.activity.copy(alpha = alpha),
            )
            Spacer(Modifier.width(Dimens.spaceXs))
        }
        Text(
            text = collapsedToolSummary ?: record.summary.take(ROW_SUMMARY_CHARS),
            style = ConsoleType.label,
            color = colors.muted.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrajectoryRetryRow(reasonCode: String?, onRetry: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reasonCode?.let { "reason=$it" }.orEmpty(),
            style = ConsoleType.label,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        TrajectoryTextButton(stringResource(R.string.common_retry), onRetry)
    }
}

private fun TrajectoryRecord.rowDescription(
    resources: Resources,
    dimmed: Boolean,
    isHit: Boolean,
    collapsedToolSummary: String?,
): String = buildList {
    add(
        sequence?.let { resources.getString(R.string.trajectory_row_sequence, it) }
            ?: resources.getString(R.string.trajectory_row_not_persisted),
    )
    add(kind.name.lowercase().replace('_', ' '))
    add(resources.trajectoryTitleText(kind, title))
    status?.let { add(resources.getString(R.string.trajectory_row_status, it)) }
    if (isLive) add(resources.getString(R.string.trajectory_row_live_only))
    if (isHit) add(resources.getString(R.string.trajectory_row_search_hit))
    if (dimmed) add(resources.getString(R.string.trajectory_row_outside_range))
    collapsedToolSummary?.let { add(resources.getString(R.string.trajectory_row_collapsed, it)) }
}.joinToString(", ")

@Composable
private fun TrajectoryBoundaryRow(record: TrajectoryRecord) {
    val colors = ConsoleColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.warning.copy(alpha = BOUNDARY_BORDER_ALPHA)),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceSm)) {
            Text(record.displayTitle(), style = ConsoleType.meta, color = colors.text)
            if (record.summary.isNotBlank()) {
                Text(record.summary, style = ConsoleType.label, color = colors.muted)
            }
        }
    }
}

@Composable
private fun TrajectoryHistoryBoundary(remaining: Int, onLoadOlder: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.trajectory_older_runs, remaining, remaining),
            style = ConsoleType.label,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        TrajectoryTextButton(stringResource(R.string.trajectory_load_older), onLoadOlder)
    }
}

private val SEQUENCE_WIDTH = 36.dp
private const val ROW_SUMMARY_CHARS = 160
private const val DIMMED_ALPHA = 0.45f
private const val BOUNDARY_BORDER_ALPHA = 0.5f
