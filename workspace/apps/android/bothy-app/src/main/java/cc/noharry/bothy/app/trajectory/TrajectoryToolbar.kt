@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.MessageField
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.singleLine
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
                    text = "${snapshot.visibleRunCount}/${snapshot.totalKnownRunCount} runs",
                    style = ConsoleType.label,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
            if (onOpenSessions != null) TrajectoryTextButton("Sessions", onOpenSessions)
            TrajectoryTextButton("Refresh", onRefresh)
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrajectoryHealthBadge(focusedRun?.health)
            TrajectoryRichBadge(focusedRun?.health?.richAvailability)
            if (focusedRun?.isActive == true) TrajectoryBadge("Live", colors.activity)
            if (focusedRun?.loadFailed == true) TrajectoryBadge("Load failed", colors.danger)
            if (hasRange) TrajectoryTextButton("Clear range", onClearRange)
        }
        Spacer(Modifier.height(Dimens.spaceXs))
        MessageField(
            text = searchQuery,
            onTextChange = onSearchQueryChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(SEARCH_FIELD_HEIGHT),
            placeholder = "Search trajectory",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (searchQuery.isBlank()) "" else "$hitCount hits",
                style = ConsoleType.label,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            if (hitCount > 0) {
                TrajectoryTextButton("Prev") { onStepHit(false) }
                TrajectoryTextButton("Next") { onStepHit(true) }
            }
            if (compact) {
                Box {
                    TrajectoryTextButton("More") { overflowOpen = true }
                    PopupMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        minWidth = OVERFLOW_MIN_WIDTH,
                        maxWidth = OVERFLOW_MAX_WIDTH,
                    ) {
                        PopupSectionLabel("Trajectory")
                        PopupMenuItem(
                            label = timelineMode.toggleLabel(),
                            selected = false,
                            onClick = {
                                overflowOpen = false
                                onTimelineModeChange(timelineMode.next())
                            },
                        )
                        PopupMenuItem("Collapse runs", false, onClick = { overflowOpen = false; onCollapseRuns() })
                        PopupMenuItem(
                            "Collapse tool calls",
                            false,
                            onClick = { overflowOpen = false; onCollapseToolCalls() },
                        )
                    }
                }
            } else {
                TrajectoryTextButton(timelineMode.toggleLabel()) { onTimelineModeChange(timelineMode.next()) }
                TrajectoryTextButton("Collapse runs", onCollapseRuns)
                TrajectoryTextButton("Collapse tools", onCollapseToolCalls)
            }
        }
    }
}

@Composable
private fun TrajectoryHealthBadge(health: TrajectoryHealth?) {
    val colors = ConsoleColors
    val resolved = health ?: TrajectoryHealth()
    val tint = when (resolved.eventLogStatus) {
        SessionEventLogHealthStatus.COMPLETE -> colors.success
        SessionEventLogHealthStatus.DEGRADED -> colors.warning
        SessionEventLogHealthStatus.UNAVAILABLE -> colors.danger
        SessionEventLogHealthStatus.VOLATILE -> colors.warning
        SessionEventLogHealthStatus.UNKNOWN -> colors.muted
    }
    val suffix = when {
        resolved.eventLogStatus == SessionEventLogHealthStatus.VOLATILE -> " · current process only"
        resolved.hasSequenceGap -> " · gap"
        resolved.failedCount > 0 -> " · ${resolved.failedCount} failed"
        else -> ""
    }
    TrajectoryBadge("EventLog ${resolved.badgeLabel}$suffix", tint)
}

@Composable
private fun TrajectoryRichBadge(availability: TrajectoryRichAvailability?) {
    val colors = ConsoleColors
    val label = when (availability ?: TrajectoryRichAvailability.UNAVAILABLE) {
        TrajectoryRichAvailability.AVAILABLE -> "Details available"
        TrajectoryRichAvailability.PARTIAL -> "Details partial"
        TrajectoryRichAvailability.REDACTED -> "Details redacted"
        TrajectoryRichAvailability.HASH_ONLY -> "Details hash only"
        TrajectoryRichAvailability.UNAVAILABLE -> "Details unavailable"
    }
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

private fun TrajectoryTimelineMode.toggleLabel(): String =
    if (this == TrajectoryTimelineMode.ACTUAL_TIME) "Actual time" else "Equal width"

private val SEARCH_FIELD_HEIGHT = 52.dp
private val OVERFLOW_MIN_WIDTH = 180.dp
private val OVERFLOW_MAX_WIDTH = 240.dp
private const val BADGE_BORDER_ALPHA = 0.5f
