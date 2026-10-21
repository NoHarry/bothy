package cc.noharry.bothy.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where the record inspector lives at a given window class. */
internal enum class TrajectoryInspectorPresentation {
    SIDE_PANE,
    BOTTOM_SHEET,
    FULL_SCREEN,
}

internal enum class TrajectoryInspectorTab {
    SUMMARY,
    PREVIEW,
    RAW,
    SOURCE,
    REQUEST,
    OPTIONS,
    TOOLS,
    THINKING,
    OUTPUT,
    USAGE,
    PAYLOAD,
    RESULT,
    SCHEMA,
    TIMING,
    HEALTH,
}

internal data class TrajectoryLayoutPolicy(
    val inspector: TrajectoryInspectorPresentation,
    val showTimeline: Boolean,
    val allowDragRange: Boolean,
    val inspectorWidth: Dp,
)

internal fun trajectoryLayoutPolicy(policy: DebugConsoleLayoutPolicy): TrajectoryLayoutPolicy =
    when (policy.windowClass) {
        DebugWindowClass.COMPACT -> TrajectoryLayoutPolicy(
            inspector = TrajectoryInspectorPresentation.FULL_SCREEN,
            // Ultra-compact height keeps the ledger usable instead of stacking three panes.
            showTimeline = !policy.useUltraCompactHeightChrome,
            allowDragRange = false,
            inspectorWidth = 0.dp,
        )
        DebugWindowClass.MEDIUM -> TrajectoryLayoutPolicy(
            inspector = TrajectoryInspectorPresentation.BOTTOM_SHEET,
            showTimeline = true,
            allowDragRange = true,
            inspectorWidth = 0.dp,
        )
        DebugWindowClass.EXPANDED -> TrajectoryLayoutPolicy(
            inspector = TrajectoryInspectorPresentation.SIDE_PANE,
            showTimeline = true,
            allowDragRange = true,
            inspectorWidth = TRAJECTORY_INSPECTOR_DEFAULT_WIDTH,
        )
    }

/**
 * Read-only run trajectory for the selected session.
 *
 * Consumes an immutable [TrajectorySnapshot]; it never holds an [cc.noharry.bothy.kernel.api.AgentRuntime]
 * or reads the EventLog directly, and switching to it has no Runtime side effect.
 */
@Composable
internal fun TrajectoryScreen(
    state: DebugUiState,
    actions: DebugConsoleActions,
    policy: DebugConsoleLayoutPolicy,
    onOpenSessions: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.trajectory
    val layout = trajectoryLayoutPolicy(policy)
    // Only low-sensitivity view state is saved: a search query or selected rich content could
    // carry user text into saved instance state.
    var selectedRunId by rememberSaveable(snapshot.sessionId) { mutableStateOf<String?>(null) }
    var inspectorTab by rememberSaveable(snapshot.sessionId) { mutableStateOf(TrajectoryInspectorTab.SUMMARY.name) }
    var selectedRecordId by remember(snapshot.sessionId) { mutableStateOf<TrajectoryRecordId?>(null) }
    var searchQuery by remember(snapshot.sessionId) { mutableStateOf("") }
    var collapsedRunIds by remember(snapshot.sessionId) { mutableStateOf(emptySet<String>()) }
    var collapsedToolCallIds by remember(snapshot.sessionId) { mutableStateOf(emptySet<String>()) }
    var timelineMode by remember(snapshot.sessionId) { mutableStateOf(TrajectoryTimelineMode.ACTUAL_TIME) }
    var rangeSelection by remember(snapshot.sessionId) { mutableStateOf<TrajectoryRangeSelection?>(null) }
    var inspectorOpen by remember(snapshot.sessionId) { mutableStateOf(false) }

    val activeRun = snapshot.runGroups.firstOrNull { it.isActive } ?: snapshot.runGroups.firstOrNull()
    val focusedRun = snapshot.runGroups.firstOrNull { it.runId == selectedRunId } ?: activeRun
    val hits = remember(snapshot.revision, snapshot.runGroups, searchQuery) {
        trajectorySearchHits(snapshot.runGroups, searchQuery)
    }
    val timeline = remember(focusedRun?.records, timelineMode) {
        projectTrajectoryTimeline(focusedRun?.records.orEmpty(), timelineMode)
    }
    val selectedOwnedBySession = selectedRecordId
        ?.let { id -> snapshot.runGroups.flatMap { it.records }.firstOrNull { it.id == id } }
        ?.takeIf { it.sessionId == snapshot.sessionId }
        ?.id
    val resolvedSelectedId = selectedOwnedBySession?.let(snapshot::resolveRecordAlias)
    LaunchedEffect(resolvedSelectedId) {
        if (resolvedSelectedId != null && resolvedSelectedId != selectedRecordId) {
            selectedRecordId = resolvedSelectedId
        }
    }
    val selectedRecord = snapshot.runGroups
        .flatMap { it.records }
        .firstOrNull { it.id == resolvedSelectedId }
    // Health is read from the record's own group, not the focused one: search can jump across runs.
    val selectedRecordGroup = selectedRecord
        ?.let { record -> snapshot.runGroups.firstOrNull { it.runId == record.runId } }
    val selectedRecordHealth = selectedRecordGroup?.health
    val selectedDurationMs = selectedRecord?.let { record ->
        trajectoryDurationMs(record, selectedRecordGroup?.records.orEmpty())
    }
    // Selecting a record is also a run selection, so the timeline and toolbar follow the jump.
    val selectRecord = { id: TrajectoryRecordId ->
        selectedRecordId = id
        snapshot.runGroups.firstOrNull { group -> group.records.any { it.id == id } }
            ?.let { selectedRunId = it.runId }
        inspectorOpen = true
    }
    // Owned by the run it was drawn on, so a run switch cannot dim unrelated records.
    val selectedRange = rangeSelection.rangeFor(focusedRun?.runId)
    val dimmedRecordIds = remember(timeline, selectedRange) {
        if (selectedRange == null) {
            emptySet()
        } else {
            focusedRun?.records.orEmpty()
                .filterNot { timeline.isInRange(it.id, selectedRange) }
                .map { it.id }
                .toSet()
        }
    }

    DebugPanelBox("Trajectory", modifier) {
        TrajectoryToolbar(
            snapshot = snapshot,
            focusedRun = focusedRun,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            hitCount = hits.size,
            onStepHit = { forward ->
                nextTrajectoryHit(hits, resolvedSelectedId, forward)?.let(selectRecord)
            },
            timelineMode = timelineMode,
            onTimelineModeChange = { timelineMode = it },
            hasRange = selectedRange != null,
            onClearRange = { rangeSelection = null },
            onCollapseRuns = {
                collapsedRunIds = if (collapsedRunIds.size == snapshot.runGroups.size) {
                    emptySet()
                } else {
                    snapshot.runGroups.map { it.runId }.toSet()
                }
            },
            onCollapseToolCalls = {
                val allToolCallIds = snapshot.runGroups
                    .flatMap { it.records }
                    .mapNotNull { it.correlation.toolCallId }
                    .toSet()
                collapsedToolCallIds = if (collapsedToolCallIds.size == allToolCallIds.size) {
                    emptySet()
                } else {
                    allToolCallIds
                }
            },
            onRefresh = actions.trajectory.refresh,
            onOpenSessions = onOpenSessions,
            compact = policy.windowClass == DebugWindowClass.COMPACT,
        )
        Spacer(Modifier.height(DebugDimens.spaceSm))
        if (snapshot.isEmpty) {
            TrajectoryEmptyState(refreshing = snapshot.refreshing, modifier = Modifier.weight(1f))
            return@DebugPanelBox
        }
        if (layout.showTimeline) {
            TrajectoryTimeline(
                projection = timeline,
                selectedRecordId = resolvedSelectedId,
                selectedRange = selectedRange,
                onSelect = selectRecord,
                onSelectRange = if (layout.allowDragRange) {
                    { range: TrajectoryTimeRange? ->
                        val runId = focusedRun?.runId
                        rangeSelection = if (range == null || runId == null) {
                            null
                        } else {
                            TrajectoryRangeSelection(runId, range)
                        }
                    }
                } else {
                    null
                },
                compact = policy.windowClass == DebugWindowClass.COMPACT,
            )
            Spacer(Modifier.height(DebugDimens.spaceSm))
        }
        val ledger: @Composable (Modifier) -> Unit = { ledgerModifier ->
            TrajectoryLedger(
                snapshot = snapshot,
                collapsedRunIds = collapsedRunIds,
                collapsedToolCallIds = collapsedToolCallIds,
                selectedRecordId = resolvedSelectedId,
                searchHits = hits.toSet(),
                dimmedRecordIds = dimmedRecordIds,
                onToggleRun = { runId ->
                    collapsedRunIds = collapsedRunIds.toggle(runId)
                    if (runId !in collapsedRunIds) actions.trajectory.loadRun(runId)
                    // An explicit run switch must not leave the inspector showing another
                    // run's record while the timeline and toolbar follow the new run.
                    if (selectedRecord?.runId != runId) {
                        selectedRecordId = null
                        inspectorOpen = false
                    }
                    selectedRunId = runId
                },
                onToggleToolCall = { callId -> collapsedToolCallIds = collapsedToolCallIds.toggle(callId) },
                onSelectRecord = selectRecord,
                onRetryRun = actions.trajectory.loadRun,
                onLoadOlderRuns = actions.trajectory.loadOlderRuns,
                modifier = ledgerModifier,
            )
        }
        if (layout.inspector == TrajectoryInspectorPresentation.SIDE_PANE && inspectorOpen && selectedRecord != null) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ledger(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(DebugDimens.spaceSm))
                TrajectoryInspector(
                    record = selectedRecord,
                    health = selectedRecordHealth,
                    tab = inspectorTab.toInspectorTab(),
                    onTabChange = { inspectorTab = it.name },
                    onClose = { inspectorOpen = false },
                    modifier = Modifier.width(layout.inspectorWidth).fillMaxHeight(),
                    durationMs = selectedDurationMs,
                )
            }
            return@DebugPanelBox
        }
        ledger(Modifier.weight(1f).fillMaxWidth())
        if (inspectorOpen && selectedRecord != null) {
            Spacer(Modifier.height(DebugDimens.spaceSm))
            TrajectoryInspector(
                record = selectedRecord,
                health = selectedRecordHealth,
                tab = inspectorTab.toInspectorTab(),
                onTabChange = { inspectorTab = it.name },
                onClose = { inspectorOpen = false },
                durationMs = selectedDurationMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        if (layout.inspector == TrajectoryInspectorPresentation.FULL_SCREEN) {
                            TRAJECTORY_INSPECTOR_FULLSCREEN_HEIGHT
                        } else {
                            TRAJECTORY_INSPECTOR_SHEET_HEIGHT
                        },
                    ),
            )
        }
    }
}

@Composable
private fun TrajectoryEmptyState(refreshing: Boolean, modifier: Modifier = Modifier) {
    val colors = DebugColors
    Box(modifier.fillMaxSize().padding(DebugDimens.spaceLg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (refreshing) "Loading trajectory…" else "暂无 v2 轨迹",
                style = DebugType.hero,
                color = colors.text,
            )
            Spacer(Modifier.height(DebugDimens.spaceXs))
            Text(
                text = "Run a turn in Debug to record a v2 SessionEventLog trajectory for this session.",
                style = DebugType.body,
                color = colors.muted,
            )
        }
    }
}

@Composable
internal fun TrajectorySectionHeader(text: String) {
    Text(text = text, style = DebugType.label, color = DebugColors.tertiary)
}

@Composable
internal fun TrajectoryMetaRow(entries: List<Pair<String, String>>) {
    val colors = DebugColors
    Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
        entries.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(text = key, style = DebugType.meta, color = colors.tertiary, modifier = Modifier.width(META_KEY_WIDTH))
                Text(text = value, style = DebugType.meta, color = colors.text)
            }
        }
    }
}

@Composable
internal fun TrajectoryTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, style = DebugType.meta) }
}

private fun String.toInspectorTab(): TrajectoryInspectorTab =
    runCatching { TrajectoryInspectorTab.valueOf(this) }.getOrDefault(TrajectoryInspectorTab.SUMMARY)

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

internal val TRAJECTORY_INSPECTOR_DEFAULT_WIDTH: Dp = 360.dp
internal val TRAJECTORY_INSPECTOR_MIN_WIDTH: Dp = 320.dp
internal val TRAJECTORY_INSPECTOR_MAX_WIDTH: Dp = 720.dp
private val TRAJECTORY_INSPECTOR_SHEET_HEIGHT: Dp = 260.dp
private val TRAJECTORY_INSPECTOR_FULLSCREEN_HEIGHT: Dp = 320.dp
private val META_KEY_WIDTH: Dp = 120.dp
