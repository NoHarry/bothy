@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cc.noharry.bothy.runtime.SubagentStatus
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DebugBoardPane(
    state: DebugUiState,
    actions: DebugConsoleActions,
    onOpenMcpProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagentBoard by DebugSubagents.board.collectAsState()
    val subagents = subagentBoard[state.activeSessionId].orEmpty()
    val sections = debugBoardSections().filter { state.shouldShowBoardSection(it) }
        .filterNot { it == DebugBoardSection.SUBAGENTS && subagents.isEmpty() }
    DebugPanelBox("Board", modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs),
        ) {
            for (section in boardSectionOrder()) {
                if (section !in sections) continue
                item(key = section.name) {
                    when (section) {
                        DebugBoardSection.SESSION_STATUS -> SessionCard(state.board, state)
                        DebugBoardSection.PLAN -> TodoCard(state.contextPanel.todo)
                        DebugBoardSection.TOKEN_USAGE -> TokenUsageCard(state.tokenUsage)
                        DebugBoardSection.SUBAGENTS -> SubagentCard(subagents)
                        DebugBoardSection.ARTIFACT -> ArtifactPreviewCard(state.artifactPreview, actions)
                        DebugBoardSection.WORKSPACE -> WorkspaceOneLiner(state.workspaceSelection, actions)
                        DebugBoardSection.MCP -> McpOneLiner(state.mcpProviders, onOpenMcpProviders)
                        DebugBoardSection.MEMORY -> MemoryCard(state.contextPanel.lessons)
                        DebugBoardSection.SKILLS -> SkillCard(state.contextPanel.skills)
                        DebugBoardSection.RUNTIME_VISIBILITY -> RuntimeAlertCard(state.runtimeDemo, actions)
                        DebugBoardSection.CURRENT_REQUEST,
                        DebugBoardSection.LAST_ACTION,
                        DebugBoardSection.RECENT_TURNS,
                        -> Unit
                    }
                }
            }
        }
    }
}

private fun boardSectionOrder(): List<DebugBoardSection> =
    listOf(
        DebugBoardSection.SESSION_STATUS,
        DebugBoardSection.PLAN,
        DebugBoardSection.TOKEN_USAGE,
        DebugBoardSection.SUBAGENTS,
        DebugBoardSection.ARTIFACT,
        DebugBoardSection.WORKSPACE,
        DebugBoardSection.MCP,
        DebugBoardSection.RUNTIME_VISIBILITY,
        DebugBoardSection.MEMORY,
        DebugBoardSection.SKILLS,
    )

@Composable
private fun WorkspaceOneLiner(selection: DebugWorkspaceSelectionState, actions: DebugConsoleActions) {
    val colors = DebugColors
    BoardSection("Workspace") {
        Text(
            selection.label,
            style = DebugType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.pickSafWorkspace) { Text("Pick SAF") }
            if (selection.safEnabled) {
                TextButton(onClick = actions.clearSafWorkspace) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun McpOneLiner(providers: List<DebugMcpProviderState>, onOpen: () -> Unit) {
    val colors = DebugColors
    val enabled = providers.filter { it.enabled }
    val names = enabled.joinToString { it.label }.ifBlank { "none" }
    BoardSection("MCP", Modifier.clickable(onClick = onOpen)) {
        Text(
            "${enabled.size}/${providers.size} loaded · $names",
            style = DebugType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DebugDimens.spaceXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap to manage", style = DebugType.meta, color = colors.muted)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
            )
        }
    }
}

/** The conversation's background subagents: state, time, latest step; a tap shows the task and the result. */
@Composable
private fun SubagentCard(rows: List<SubagentStatus>) {
    val colors = DebugColors
    val running = rows.any { !it.state.finished }
    // Running ones count their seconds even between events.
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(SUBAGENT_TICK_MS)
        }
    }
    BoardSection("Subagents") {
        rows.forEach { row ->
            var open by remember(row.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = DebugDimens.spaceXs)) {
                Text(
                    "● ${row.label} · ${row.state.chinese()}",
                    style = DebugType.meta,
                    color = row.state.wireName.planStatusColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.logLine(now), style = DebugType.meta, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!row.state.finished && row.latest.isNotEmpty()) {
                    Text("最近：${row.latest}", style = DebugType.meta, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (open) {
                    Text("任务：${row.task}", style = DebugType.meta, color = colors.text)
                    val outcome = row.result.ifEmpty { row.error }
                    if (outcome.isNotEmpty()) {
                        Text(outcome.take(SUBAGENT_RESULT_PREVIEW_CHARS), style = DebugType.meta, color = colors.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeAlertCard(demo: DebugRuntimeDemoState, actions: DebugConsoleActions) {
    val colors = DebugColors
    val pending = demo.pendingConfirmationActionIds()
    BoardSection("Needs attention") {
        Text(
            demo.status,
            style = DebugType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        pending.take(MAX_CONFIRMATION_ACTION_ROWS).forEach { pendingId ->
            Spacer(Modifier.height(DebugDimens.spaceSm))
            Text(
                pendingId,
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DebugDimens.spaceXs))
            DebugApproveDenyRow(
                onApprove = { actions.approveConfirmation(pendingId) },
                onDeny = { actions.denyConfirmation(pendingId) },
            )
        }
    }
}

@Composable
private fun ArtifactPreviewCard(preview: DebugArtifactPreview?, actions: DebugConsoleActions) {
    val colors = DebugColors
    if (preview == null) return
    BoardSection("Artifact") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                preview.path,
                style = DebugType.meta,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DebugDimens.spaceSm))
            Icon(
                imageVector = DebugIconFullscreen,
                contentDescription = "Open fullscreen preview",
                tint = colors.muted,
                modifier = Modifier.size(MANAGE_CHEVRON_SIZE),
            )
        }
        Text("${preview.bytes.formatCount()} bytes", style = DebugType.meta, color = colors.muted)
        Spacer(Modifier.height(DebugDimens.spaceXsMd))
        DebugHtmlPreviewBox(
            html = preview.html,
            refreshToken = preview.refreshToken,
            onOpen = { actions.openHtmlViewer(preview.path, preview.html) },
            modifier = Modifier
                .fillMaxWidth()
                .height(ARTIFACT_PREVIEW_HEIGHT),
        )
    }
}

@Composable
private fun SessionCard(board: DebugSessionBoardState, state: DebugUiState) {
    val session = board.activeSession
    BoardSection("Run") {
        SessionStatusHeader(state = state, sessionTitle = session?.title)
        if ((session?.turnCount ?: 0) > 0) {
            Spacer(Modifier.height(DebugDimens.spaceXs))
            Text(
                formatTurnCount(session?.turnCount ?: 0),
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
        session?.compactedSummary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(DebugDimens.spaceXs))
            Text(
                it,
                style = DebugType.meta,
                color = DebugColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionStatusHeader(state: DebugUiState, sessionTitle: String?) {
    val colors = DebugColors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (state.isRunning) {
            ActivityDot(color = colors.activity)
        } else {
            DebugStatusDot(color = colors.success.copy(alpha = IDLE_DOT_ALPHA))
        }
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Column(Modifier.weight(1f)) {
            if (state.isRunning) {
                ShimmerText(text = statusHeader(state), baseColor = colors.muted, highlightColor = colors.text)
            } else {
                Text(
                    text = state.lastRunSummary?.takeIf { it.isNotBlank() } ?: "Ready",
                    style = DebugType.meta,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = sessionTitle ?: "No active session",
                style = DebugType.meta,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TodoCard(items: List<DebugTodoEntry>) {
    if (items.isEmpty()) return
    BoardSection("Plan") {
        items.take(MAX_PLAN_ROWS).forEach { item ->
            PlanStatusRow(item)
        }
        if (items.size > MAX_PLAN_ROWS) {
            Spacer(Modifier.height(DebugDimens.spaceXs))
            Text(
                "+${items.size - MAX_PLAN_ROWS} more",
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
    }
}

@Composable
private fun TokenUsageCard(usage: DebugTokenUsageState) {
    if (!usage.hasUsage) return
    val colors = DebugColors
    BoardSection("Usage") {
        Text(
            usage.compactUsageLine(),
            style = DebugType.meta,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DebugDimens.spaceSm))
        // Thin cache-hit-rate bar; empty track until the provider reports cache activity.
        UsageProgressBar(fraction = usage.cacheHitRate?.let { it / PERCENT_FLOAT } ?: 0f)
        if (usage.hasCacheActivity || usage.reasoningTokens > 0) {
            Spacer(Modifier.height(DebugDimens.spaceXs))
            val detail = buildList {
                if (usage.reasoningTokens > 0) add("reason ${usage.reasoningTokens.formatCompact()}")
                if (usage.hasCacheActivity) {
                    add("r/w ${usage.cachedInputTokens.formatCompact()}/${usage.cacheWriteTokens.formatCompact()}")
                }
                if (usage.requestCount > 1) add("${usage.requestCount} req")
            }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = DebugType.meta,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UsageProgressBar(fraction: Float) {
    val colors = DebugColors
    Box(
        Modifier
            .fillMaxWidth()
            .height(USAGE_BAR_HEIGHT)
            .background(colors.borderSubtle, RoundedCornerShape(DebugDimens.radiusFull)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(USAGE_BAR_HEIGHT)
                .background(colors.accent, RoundedCornerShape(DebugDimens.radiusFull)),
        )
    }
}

private fun DebugTokenUsageState.compactUsageLine(): String {
    val cache = cacheHitRate?.let { " · cache $it%" }.orEmpty()
    return "${inputTokens.formatCompact()} in · ${outputTokens.formatCompact()} out$cache"
}

@Composable
private fun PlanStatusRow(item: DebugTodoEntry) {
    val colors = DebugColors
    val active = item.status.isPlanActive()
    Row(
        Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXxs),
        verticalAlignment = Alignment.Top,
    ) {
        DebugStatusDot(
            color = item.status.planStatusColor(),
            pulsing = active,
            modifier = Modifier.padding(top = DebugDimens.spaceXs),
        )
        Spacer(Modifier.width(DebugDimens.spaceSm))
        Text(
            text = item.content,
            style = DebugType.meta,
            color = colors.text,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MemoryCard(lessons: List<DebugLessonEntry>) {
    if (lessons.isEmpty()) return
    val colors = DebugColors
    BoardSection("Memory") {
        lessons.take(MAX_MEMORY_ROWS).forEach { lesson ->
            Text(
                lesson.content,
                style = DebugType.meta,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DebugDimens.spaceXs))
        }
    }
}

@Composable
private fun SkillCard(skills: List<DebugSkillEntry>) {
    if (skills.isEmpty()) return
    val colors = DebugColors
    BoardSection("Skills") {
        Text(
            skills.take(MAX_SKILL_NAMES).joinToString { it.name },
            style = DebugType.meta,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Board rail section: label-style header, content, hairline — no elevated card wall.
 * Section padding stays on DebugDimens.spaceMd; inner element gaps use spaceXs/spaceSm.
 */
@Composable
private fun BoardSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = DebugColors
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = DebugDimens.spaceMd),
    ) {
        DebugSectionLabel(title)
        Spacer(Modifier.height(DebugDimens.spaceSm))
        content()
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderSubtle),
        )
    }
}

private val ARTIFACT_PREVIEW_HEIGHT = 180.dp
private val USAGE_BAR_HEIGHT = 3.dp
private val MANAGE_CHEVRON_SIZE = 16.dp
private const val MAX_CONFIRMATION_ACTION_ROWS = 3
private const val SUBAGENT_TICK_MS = 1_000L
private const val SUBAGENT_RESULT_PREVIEW_CHARS = 600
private const val MAX_PLAN_ROWS = 8
private const val MAX_MEMORY_ROWS = 3
private const val MAX_SKILL_NAMES = 4
private const val PERCENT_FLOAT = 100f
private const val IDLE_DOT_ALPHA = 0.75f

@Composable
private fun String.planStatusColor(): Color {
    val colors = DebugColors
    return when {
        isPlanActive() -> colors.activity
        lowercase() in setOf("completed", "done", "success") -> colors.success
        lowercase().contains("fail") || lowercase() in setOf("stalled", "timed_out") -> colors.danger
        else -> colors.muted
    }
}

private fun String.isPlanActive(): Boolean =
    lowercase() in setOf("in_progress", "running", "active", "doing")

internal fun Int.formatCount(): String = "%,d".format(this)

internal fun Long.formatCount(): String = "%,d".format(this)

/** Compact counts for rail density (1.2k, 15). */
private fun Int.formatCompact(): String =
    when {
        this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
        this >= 10_000 -> "${this / 1000}k"
        this >= 1000 -> "%.1fk".format(this / 1000.0)
        else -> toString()
    }
