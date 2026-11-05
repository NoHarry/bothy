package cc.noharry.bothy.app.trajectory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.view.TrajectoryTimelineMode
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.MessageField
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel

/**
 * Trajectory controls plus the two independent availability signals: EventLog health and rich
 * detail availability. Badges always carry text, so status is never conveyed by colour alone.
 */
@Composable
internal fun TrajectoryToolbar(
    snapshot: TrajectorySnapshot,
    focusedRun: TrajectoryRunGroup?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    hitCount: Int,
    onStepHit: (Boolean) -> Unit,
    timelineMode: TrajectoryTimelineMode,
    onTimelineModeChange: (TrajectoryTimelineMode) -> Unit,
    hasRange: Boolean,
    onClearRange: () -> Unit,
    onCollapseRuns: () -> Unit,
    onCollapseToolCalls: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSessions: (() -> Unit)?,
    compact: Boolean,
) {
    val colors = ConsoleColors
    var overflowOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = focusedRun?.runId ?: snapshot.sessionId,
                    style = ConsoleType.meta,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.trajectory_run_count,
                        snapshot.visibleRunCount,
                        snapshot.totalKnownRunCount,
                    ),
                    style = ConsoleType.label,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
            if (onOpenSessions != null) TrajectoryTextButton(stringResource(R.string.session_sessions), onOpenSessions)
            TrajectoryTextButton(stringResource(R.string.common_refresh), onRefresh)
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrajectoryHealthBadge(focusedRun?.health)
            TrajectoryRichBadge(focusedRun?.health?.richAvailability)
            if (focusedRun?.isActive == true) {
                TrajectoryBadge(stringResource(R.string.trajectory_live_badge), colors.activity)
            }
            if (focusedRun?.loadFailed == true) {
                TrajectoryBadge(stringResource(R.string.trajectory_load_failed_badge), colors.danger)
            }
            if (hasRange) TrajectoryTextButton(stringResource(R.string.trajectory_clear_range), onClearRange)
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        MessageField(
            text = searchQuery,
            onTextChange = onSearchQueryChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(SEARCH_FIELD_HEIGHT),
            placeholder = stringResource(R.string.trajectory_search_placeholder),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (searchQuery.isBlank()) {
                    ""
                } else {
                    pluralStringResource(R.plurals.trajectory_hits, hitCount, hitCount)
                },
                style = ConsoleType.label,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            if (hitCount > 0) {
                TrajectoryTextButton(stringResource(R.string.trajectory_prev)) { onStepHit(false) }
                TrajectoryTextButton(stringResource(R.string.trajectory_next)) { onStepHit(true) }
            }
            if (compact) {
                Box {
                    TrajectoryTextButton(stringResource(R.string.trajectory_more)) { overflowOpen = true }
                    PopupMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        minWidth = OVERFLOW_MIN_WIDTH,
                        maxWidth = OVERFLOW_MAX_WIDTH,
                    ) {
                        PopupSectionLabel(stringResource(R.string.trajectory_title))
                        PopupMenuItem(
                            label = stringResource(timelineMode.labelRes()),
                            selected = false,
                            onClick = {
                                overflowOpen = false
                                onTimelineModeChange(timelineMode.next())
                            },
                        )
                        PopupMenuItem(
                            stringResource(R.string.trajectory_collapse_runs),
                            false,
                            onClick = { overflowOpen = false; onCollapseRuns() },
                        )
                        PopupMenuItem(
                            stringResource(R.string.trajectory_collapse_tool_calls),
                            false,
                            onClick = { overflowOpen = false; onCollapseToolCalls() },
                        )
                    }
                }
            } else {
                TrajectoryTextButton(stringResource(timelineMode.labelRes())) {
                    onTimelineModeChange(timelineMode.next())
                }
                TrajectoryTextButton(stringResource(R.string.trajectory_collapse_runs), onCollapseRuns)
                TrajectoryTextButton(stringResource(R.string.trajectory_collapse_tools), onCollapseToolCalls)
            }
        }
    }
}

@Composable
private fun TrajectoryHealthBadge(health: TrajectoryHealth?) {
    val colors = ConsoleColors
    val resolved = health ?: TrajectoryHealth()
    val tint = when (resolved.eventLogStatus) {
        EventLogHealthStatus.COMPLETE -> colors.success
        EventLogHealthStatus.DEGRADED -> colors.warning
        EventLogHealthStatus.UNAVAILABLE -> colors.danger
        EventLogHealthStatus.VOLATILE -> colors.warning
        EventLogHealthStatus.UNKNOWN -> colors.muted
    }
    val suffix = when {
        resolved.eventLogStatus == EventLogHealthStatus.VOLATILE ->
            stringResource(R.string.trajectory_health_current_process_only)
        resolved.hasSequenceGap -> stringResource(R.string.trajectory_health_gap)
        resolved.failedCount > 0 ->
            pluralStringResource(R.plurals.trajectory_health_failed, resolved.failedCount, resolved.failedCount)
        else -> null
    }
    val label = stringResource(R.string.trajectory_eventlog_badge, stringResource(resolved.eventLogStatus.labelRes()))
    TrajectoryBadge(if (suffix == null) label else "$label · $suffix", tint)
}

@Composable
private fun TrajectoryRichBadge(availability: TrajectoryRichAvailability?) {
    val colors = ConsoleColors
    val label = stringResource((availability ?: TrajectoryRichAvailability.UNAVAILABLE).badgeRes())
    TrajectoryBadge(label, colors.muted)
}

@Composable
internal fun TrajectoryBadge(label: String, tint: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = ConsoleColors.elevated,
        border = BorderStroke(1.dp, tint.copy(alpha = BADGE_BORDER_ALPHA)),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            style = ConsoleType.label,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
        )
    }
}

private fun TrajectoryTimelineMode.next(): TrajectoryTimelineMode =
    if (this == TrajectoryTimelineMode.ACTUAL_TIME) {
        TrajectoryTimelineMode.EQUAL_WIDTH
    } else {
        TrajectoryTimelineMode.ACTUAL_TIME
    }

private val SEARCH_FIELD_HEIGHT = 52.dp
private val OVERFLOW_MIN_WIDTH = 180.dp
private val OVERFLOW_MAX_WIDTH = 240.dp
private const val BADGE_BORDER_ALPHA = 0.5f
